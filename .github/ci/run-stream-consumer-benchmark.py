#!/usr/bin/env python3
"""Compare text consumption using the unchanged production replay infrastructure."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import time

from performance_report import SCHEMA_VERSION, aggregate, compare_baseline, markdown

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("performance_driver", HERE / "run-performance-benchmarks.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=HERE.parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--forks", type=driver.positive, default=3)
    parser.add_argument("--warmups", type=driver.positive, default=2)
    parser.add_argument("--samples", type=driver.positive, default=5)
    args = parser.parse_args()
    driver.ROOT = args.source_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("Output directory must be empty")
    javac, java, version = driver.jdk()
    production = sorted((driver.ROOT / "modules/core/src/main/java").rglob("*.java"))
    harness = sorted((driver.ROOT / "tests/performance").rglob("*.java"))
    consumer = HERE / "StreamConsumerBenchmark.java"
    if not production or not harness:
        parser.error("Source root must contain the production core and replay harness")
    consumer_hash = hashlib.sha256(consumer.read_bytes()).hexdigest()
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "source_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=driver.ROOT, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=driver.ROOT, text=True).strip()),
        "environment": driver.environment(version),
        "measurement_config": {"forks": args.forks, "warmups": args.warmups,
                               "samples": args.samples, "jvm_flags": driver.JVM_FLAGS},
        "production_sha256": driver.digest(production),
        "harness_sha256": hashlib.sha256((driver.digest(harness) + consumer_hash).encode()).hexdigest(),
        "consumer_sha256": consumer_hash,
        "driver_sha256": hashlib.sha256(Path(__file__).read_bytes()
            + (HERE / "performance_report.py").read_bytes()
            + (HERE / "run-performance-benchmarks.py").read_bytes()).hexdigest(),
        "started_at_unix": time.time(),
        "scope": "Supplemental full text consumption; no build scripts, Android, native PTY or network",
    }
    driver.write_json(output / "manifest.json", manifest)
    hashes = {path.relative_to(driver.ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in production + harness}
    hashes[".github/ci/StreamConsumerBenchmark.java"] = consumer_hash
    driver.write_json(output / "sources.json", hashes)
    forks = []
    with tempfile.TemporaryDirectory(prefix="agentcodi-consumer-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        sources = Path(temporary) / "sources.txt"
        sources.write_text("\n".join('"' + str(path).replace("\\", "\\\\").replace('"', '\\"') + '"'
            for path in production + harness + [consumer]), encoding="utf-8")
        compiled = subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                                   "@" + str(sources)], text=True, capture_output=True,
                                  cwd=driver.ROOT, timeout=120)
        (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        for fork in range(1, args.forks + 1):
            forks.append(driver.run_jvm([java, *driver.JVM_FLAGS, "-cp", str(classes),
                "de.agentcodi.performance.StreamConsumerBenchmark", str(args.warmups), str(args.samples)],
                output, "consumer-fork-" + str(fork)))
    report = {**manifest, "suite": "consumer", "workloads": aggregate(forks), "signals": []}
    if args.baseline:
        report["baseline"] = compare_baseline(report, json.loads(args.baseline.read_text()))
    driver.write_json(output / "consumer.json", report)
    summary = markdown(report)
    (output / "consumer.md").write_text(summary)
    driver.publish_summary(summary)


if __name__ == "__main__":
    main()
