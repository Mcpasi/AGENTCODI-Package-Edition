#!/usr/bin/env python3
"""Standalone performance replays: Python standard library plus a stock JDK 17+."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from performance_report import SCHEMA_VERSION, aggregate, compare_baseline, markdown, observations

ROOT = Path(__file__).resolve().parents[2]
JVM_FLAGS = ["-Xms128m", "-Xmx512m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2"]
SUITES = ("terminal", "chat", "protocol")


def positive(value):
    number = int(value)
    if not 1 <= number <= 30:
        raise argparse.ArgumentTypeError("Expected an integer from 1 to 30")
    return number


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def digest(paths):
    checksum = hashlib.sha256()
    for path in sorted(paths):
        checksum.update(path.relative_to(ROOT).as_posix().encode())
        checksum.update(b"\0")
        checksum.update(path.read_bytes())
        checksum.update(b"\0")
    return checksum.hexdigest()


def jdk():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    if not javac or not Path(javac).is_file():
        raise ValueError("A JDK 17+ with javac is required; set JAVA_HOME to its root")
    java = Path(javac).resolve().with_name("java")
    if not java.is_file():
        raise ValueError("No java binary beside javac")
    version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True)
    major = re.search(r'version "(\d+)', version)
    if not major or int(major[1]) < 17:
        raise ValueError("JDK 17+ is required")
    return str(javac), str(java), version.strip()


def environment(java_version):
    cpu = platform.processor() or "unknown"
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.partition(":")[2].strip()
                break
    return {"java_version": java_version, "platform": platform.platform(),
            "machine": platform.machine(), "cpu_model": cpu, "cpu_count": os.cpu_count(),
            "python_version": platform.python_version(),
            "runner_os": os.environ.get("RUNNER_OS"), "runner_arch": os.environ.get("RUNNER_ARCH")}


def run_jvm(command, output, stem, measurements=True):
    stdout_path = output / (stem + (".jsonl" if measurements else ".stdout.txt"))
    with stdout_path.open("w", encoding="utf-8") as stdout, \
            (output / (stem + ".stderr.txt")).open("w", encoding="utf-8") as stderr:
        result = subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr, timeout=300)
    if result.returncode:
        detail = (output / (stem + ".stderr.txt")).read_text()
        raise RuntimeError(f"{stem} failed ({result.returncode}):\n{detail}")
    # The JVM prints JFR startup notices to stdout; profiling is a separate, untallied run.
    return [json.loads(line) for line in stdout_path.read_text().splitlines()] if measurements else None


def publish_summary(text):
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")


def profile_settings(java, output, suite):
    settings = ET.parse(Path(java).parent.parent / "lib/jfr/profile.jfc")
    # JFR's stock profile also records process environment and system properties.
    # Performance artifacts must contain neither credentials nor unrelated process commands.
    for event_name in ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.SystemProcess"):
        event = settings.getroot().find(f"event[@name='{event_name}']")
        if event is None:
            raise ValueError(f"JFR profile lacks expected event: {event_name}")
        enabled = event.find("setting[@name='enabled']")
        if enabled is None:
            raise ValueError(f"JFR event lacks enabled setting: {event_name}")
        enabled.attrib.pop("control", None)
        enabled.text = "false"
    destination = output / (suite + "-profile.jfc")
    settings.write(destination, encoding="utf-8", xml_declaration=True)
    return destination


def run(args, output):
    javac, java, java_version = jdk()
    production = sorted((ROOT / "modules/core/src/main/java").rglob("*.java"))
    harness = sorted((ROOT / "tests/performance").rglob("*.java"))
    if not production or not harness:
        raise ValueError("Production sources or replay harness are missing")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    manifest = {"schema_version": SCHEMA_VERSION, "source_revision": revision, "dirty": dirty,
                "environment": environment(java_version),
                "measurement_config": {"forks": args.forks, "warmups": args.warmups,
                                       "samples": args.samples, "jvm_flags": JVM_FLAGS},
                "production_sha256": digest(production), "harness_sha256": digest(harness),
                "driver_sha256": digest([Path(__file__).resolve(),
                                          Path(__file__).with_name("performance_report.py")]),
                "started_at_unix": time.time(),
                "scope": "Portable production Java; no build scripts, APK, SDK, NDK or device"}
    write_json(output / "manifest.json", manifest)
    write_json(output / "sources.json", {path.relative_to(ROOT).as_posix():
               hashlib.sha256(path.read_bytes()).hexdigest() for path in production + harness})
    reports = []
    with tempfile.TemporaryDirectory(prefix="agentcodi-performance-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        source_list = Path(temporary) / "sources.txt"
        # javac argument files need quoting for checkout paths containing spaces.
        source_list.write_text("\n".join('"' + str(path).replace("\\", "\\\\")
                              .replace('"', '\\"') + '"' for path in production + harness),
                              encoding="utf-8")
        with (output / "compile.stdout.txt").open("w") as stdout, \
                (output / "compile.stderr.txt").open("w") as stderr:
            subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                            "@" + str(source_list)], check=True, cwd=ROOT,
                           stdout=stdout, stderr=stderr, timeout=120)
        suites = SUITES if args.suite == "all" else (args.suite,)
        for suite in suites:
            print(f"Running {suite}: {args.forks} JVM forks", flush=True)
            forks = []
            for fork in range(args.forks):
                print(f"  Fork {fork + 1}/{args.forks}", flush=True)
                command = [java, *JVM_FLAGS, "-cp", str(classes),
                           "de.agentcodi.performance.PerformanceMain", suite,
                           str(args.warmups), str(args.samples)]
                measured = run_jvm(command, output, f"{suite}-fork-{fork + 1}")
                if any(entry["warmups"] != args.warmups or len(entry["samples"]) != args.samples
                       for entry in measured):
                    raise ValueError("JVM returned unexpected warmup/sample counts")
                forks.append(measured)
            report = {**manifest, "suite": suite, "workloads": aggregate(forks)}
            report["signals"] = observations(report["workloads"])
            if args.baseline:
                report["baseline"] = compare_baseline(report, json.loads(args.baseline.read_text()))
            write_json(output / (suite + ".json"), report)
            with (output / (suite + ".csv")).open("w", newline="", encoding="utf-8") as handle:
                fields = ("id", "operations", "input_bytes", "median_ms", "median_cpu_ms",
                          "median_event_p95_ms", "median_event_p99_ms", "max_event_ms",
                          "median_allocated_bytes", "allocated_bytes_per_operation",
                          "allocation_per_input_byte", "gc_count", "gc_ms")
                writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
                writer.writeheader()
                writer.writerows(report["workloads"])
            summary = markdown(report)
            (output / (suite + ".md")).write_text(summary, encoding="utf-8")
            publish_summary(summary)
            if args.profile:
                recording = output / (suite + ".jfr")
                settings = profile_settings(java, output, suite)
                run_jvm([java, *JVM_FLAGS,
                         f"-XX:StartFlightRecording=filename={recording},settings={settings},dumponexit=true,maxsize=64m",
                         "-cp", str(classes),
                         "de.agentcodi.performance.PerformanceMain", suite, "1", "1"],
                        output, suite + "-profile", measurements=False)
                if not recording.is_file() or recording.stat().st_size == 0:
                    raise RuntimeError("JVM did not produce a JFR recording")
            reports.append(report)
    return 2 if args.fail_on_findings and any(report["signals"] for report in reports) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=(*SUITES, "all"), required=True)
    parser.add_argument("--output", type=Path, required=True, help="Empty directory for raw data and reports")
    parser.add_argument("--forks", type=positive, default=3)
    parser.add_argument("--warmups", type=positive, default=2)
    parser.add_argument("--samples", type=positive, default=5)
    parser.add_argument("--profile", action="store_true", help="Separate JFR replay after measurement")
    parser.add_argument("--baseline", type=Path, help="Compatible prior suite JSON for comparison")
    parser.add_argument("--fail-on-findings", action="store_true",
                        help="Opt-in exit code 2 on diagnostic signals; default is report-only")
    args = parser.parse_args()
    if args.baseline and args.suite == "all":
        parser.error("--baseline requires a single suite")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("--output must be empty to prevent mixing old measurements")
    try:
        return run(args, output)
    except (ValueError, KeyError, TypeError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        write_json(output / "failure.json", {"error": str(error), "suite": args.suite})
        publish_summary(f"Performance replay failed: {error}\n\nPartial artifacts: {output}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
