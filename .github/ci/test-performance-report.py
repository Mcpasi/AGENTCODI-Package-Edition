#!/usr/bin/env python3
"""Replay compilation, diagnostics and numeric report contracts."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from performance_report import aggregate, compare_baseline, markdown, observations

SPEC = importlib.util.spec_from_file_location("performance_runner",
                                            Path(__file__).with_name("run-performance-benchmarks.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
CONSUMER = Path(__file__).with_name("run-stream-consumer-benchmark.py").resolve()


def raw(workload_id="fixture", elapsed=(1_000_000, 3_000_000), allocation=2048):
    return {"id": workload_id, "boundary": "fixture boundary", "parameters": {"chunk": 64},
            "operations": 2, "input_bytes": 1024, "warmups": 1,
            "samples": [{"elapsed_ns": value, "cpu_ns": value // 2,
                         "allocated_bytes": allocation, "gc_count": 1, "gc_ms": 2,
                         "event_latency": {"p50_ns": 100, "p95_ns": 200,
                                           "p99_ns": 300, "max_ns": 400}} for value in elapsed]}


def report(workloads):
    return {"schema_version": 1, "suite": "chat", "source_revision": "fixture-revision",
            "harness_sha256": "fixture-hash", "measurement_config": {
                "forks": 2, "warmups": 1, "samples": 2, "jvm_flags": []},
            "environment": {"java_version": "17", "cpu_model": "fixture-cpu",
                            "machine": "x86_64", "platform": "fixture-platform"},
            "workloads": workloads, "signals": observations(workloads)}


class PerformanceReportTest(unittest.TestCase):
    def test_multiple_forks_preserve_units_and_separate_event_percentiles(self):
        second = raw(elapsed=(5_000_000, 7_000_000))
        second["samples"][0]["event_latency"]["p95_ns"] = 600
        second["samples"][1]["event_latency"]["p95_ns"] = 800
        for sample in second["samples"]:
            sample["event_latency"].update(p99_ns=900, max_ns=1000)
        entry = aggregate([[raw()], [second]])[0]
        self.assertEqual(4.0, entry["median_ms"])
        self.assertEqual([2.0, 6.0], entry["fork_median_ms"])
        self.assertEqual(0.0004, entry["median_event_p95_ms"])
        self.assertEqual(1024, entry["allocated_bytes_per_operation"])
        self.assertEqual(2.0, entry["allocation_per_input_byte"])
        self.assertEqual(4, entry["gc_count"])

    def test_missing_duplicate_or_changed_workloads_are_rejected(self):
        changed = raw()
        changed["parameters"]["chunk"] = 4096
        for forks in ([], [[]], [[raw()], []], [[raw(), raw()]], [[raw()], [changed]]):
            with self.subTest(forks=forks), self.assertRaises(ValueError):
                aggregate(forks)

    def test_invalid_counters_and_nan_do_not_become_good_results(self):
        for key, value in (("allocated_bytes", -1), ("elapsed_ns", 0), ("cpu_ns", float("nan"))):
            invalid = raw()
            invalid["samples"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                aggregate([[invalid]])

    def test_idle_refresh_has_no_fabricated_input_throughput(self):
        idle = raw("terminal.idle-full", allocation=4 * 1024 * 1024)
        idle["input_bytes"] = 0
        entry = aggregate([[idle]])[0]
        self.assertIsNone(entry["allocation_per_input_byte"])
        self.assertEqual("terminal-idle-allocation", observations([entry])[0]["id"])

    def test_allocation_growth_is_measured_at_equal_chunk_size(self):
        small = raw("chat.tool-16k-128b", allocation=4096)
        large = raw("chat.tool-32k-128b", allocation=16_384)
        large["input_bytes"] *= 2
        large["operations"] *= 2
        signals = observations(aggregate([[small, large]]))
        self.assertEqual(1, len(signals))
        self.assertEqual(4, signals[0]["allocation_ratio"])
        self.assertEqual(2, signals[0]["input_ratio"])

    def test_baseline_ratios_and_zero_denominator(self):
        baseline = report(aggregate([[raw()]]))
        current = copy.deepcopy(baseline)
        current["workloads"][0]["median_ms"] *= 1.5
        baseline["workloads"][0]["median_event_p95_ms"] = 0
        comparison = compare_baseline(current, baseline)["workloads"][0]
        self.assertEqual(1.5, comparison["median_ms_ratio"])
        self.assertEqual(1, comparison["median_allocated_bytes_ratio"])
        self.assertIsNone(comparison["median_event_p95_ms_ratio"])

    def test_baseline_different_runtime_or_fixture_is_rejected(self):
        baseline = report(aggregate([[raw()]]))
        for target, key, value in (("environment", "cpu_model", "other-cpu"),
                                   ("workload", "operations", 3),
                                   ("root", "harness_sha256", "changed")):
            changed = copy.deepcopy(baseline)
            container = changed if target == "root" else changed["environment"] \
                if target == "environment" else changed["workloads"][0]
            container[key] = value
            with self.subTest(target=target), self.assertRaises(ValueError):
                compare_baseline(changed, baseline)

    def test_summary_states_boundaries_and_report_only_behavior(self):
        summary = markdown(report(aggregate([[raw()]])))
        for text in ("fixture boundary", "report-only", "exclude Android", "not retained heap"):
            self.assertIn(text, summary)

    def test_out_of_order_percentiles_are_rejected(self):
        invalid = raw()
        invalid["samples"][0]["event_latency"]["p99_ns"] = 150
        with self.assertRaises(ValueError):
            aggregate([[invalid]])

    def test_profile_cannot_record_environment_properties_or_unrelated_commands(self):
        events = ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.SystemProcess")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "jdk/lib/jfr/profile.jfc"
            profile.parent.mkdir(parents=True)
            profile.write_text('<configuration>' + ''.join(
                f'<event name="{event}"><setting name="enabled" control="dynamic">true</setting></event>'
                for event in events) + '</configuration>')
            result = RUNNER.profile_settings(str(root / "jdk/bin/java"), root, "fixture")
            settings = ET.parse(result).getroot()
            for event in events:
                enabled = settings.find(f"event[@name='{event}']/setting[@name='enabled']")
                self.assertEqual("false", enabled.text)
                self.assertNotIn("control", enabled.attrib)
            profile.write_text("<configuration/>")
            with self.assertRaises(ValueError):
                RUNNER.profile_settings(str(root / "jdk/bin/java"), root, "fixture")


class StreamConsumerRunnerTest(unittest.TestCase):
    def run_consumer(self, output, environment=None):
        return subprocess.run([sys.executable, "-B", str(CONSUMER), "--output", str(output),
                               "--forks", "1", "--warmups", "1", "--samples", "1"],
                              env=environment, text=True, capture_output=True, timeout=120)

    def test_current_sources_compile_and_replay_all_consumer_scenarios(self):
        with tempfile.TemporaryDirectory(prefix="consumer replay ") as temporary:
            output = Path(temporary) / "measurements"
            result = self.run_consumer(output)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            report = json.loads((output / "consumer.json").read_text())
            self.assertEqual(report["suite"], "consumer")
            self.assertEqual({entry["id"] for entry in report["workloads"]}, {
                "consumer.assistant-64k-64b", "consumer.tool-32k-128b",
                "consumer.reasoning-32k-128b", "consumer.reasoning-multipart-32k-128b"})
            samples = [json.loads(line) for line in (output / "consumer-fork-1.jsonl").read_text().splitlines()]
            for entry in samples:
                self.assertEqual(entry["warmups"], 1)
                self.assertEqual(len(entry["samples"]), 1)

    def test_compiler_diagnostics_are_visible_and_preserved_in_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="consumer compiler ") as temporary:
            root = Path(temporary)
            jdk = root / "jdk"
            (jdk / "bin").mkdir(parents=True)
            _, java, _ = RUNNER.jdk()
            (jdk / "bin/java").symlink_to(java)
            compiler = jdk / "bin/javac"
            compiler.write_text("#!/bin/sh\nprintf '%s\\n' 'consumer compiler diagnostic fixture' >&2\nexit 23\n")
            compiler.chmod(0o700)
            output = root / "measurements"
            result = self.run_consumer(output, {**os.environ, "JAVA_HOME": str(jdk)})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("consumer compiler diagnostic fixture", result.stderr)
            self.assertEqual((output / "compile.log").read_text(), "consumer compiler diagnostic fixture\n")


if __name__ == "__main__":
    unittest.main()
