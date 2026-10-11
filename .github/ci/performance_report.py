"""Aggregate host replay measurements without treating host timing as device FPS."""

import math
import statistics

SCHEMA_VERSION = 1


def aggregate(forks):
    if not forks or not forks[0]:
        raise ValueError("No benchmark measurements")
    grouped = {}
    expected = None
    for fork in forks:
        ids = [entry["id"] for entry in fork]
        if len(set(ids)) != len(ids):
            raise ValueError("Duplicate workload in a fork")
        if expected is None:
            expected = set(ids)
        if set(ids) != expected:
            raise ValueError("Workloads differ between forks")
        for entry in fork:
            if entry["operations"] <= 0 or entry["input_bytes"] < 0 or not entry["samples"]:
                raise ValueError("Invalid workload counters")
            grouped.setdefault(entry["id"], []).append(entry)
    results = []
    for workload_id, entries in grouped.items():
        first = entries[0]
        fields = ("boundary", "parameters", "operations", "input_bytes", "warmups")
        for entry in entries:
            if any(entry[field] != first[field] for field in fields):
                raise ValueError(f"Workload changed between forks: {workload_id}")
            if len(entry["samples"]) != len(first["samples"]):
                raise ValueError(f"Sample counts differ: {workload_id}")
        samples = [sample for entry in entries for sample in entry["samples"]]
        for sample in samples:
            values = [sample[key] for key in
                      ("elapsed_ns", "cpu_ns", "allocated_bytes", "gc_count", "gc_ms")]
            values += [sample["event_latency"][key] for key in
                       ("p50_ns", "p95_ns", "p99_ns", "max_ns")]
            if any(not isinstance(value, (int, float)) or not math.isfinite(value)
                   or value < 0 for value in values):
                raise ValueError(f"Invalid measurement: {workload_id}")
            if sample["elapsed_ns"] == 0:
                raise ValueError(f"Empty timed measurement: {workload_id}")
            latency = sample["event_latency"]
            if not latency["p50_ns"] <= latency["p95_ns"] <= latency["p99_ns"] <= latency["max_ns"]:
                raise ValueError(f"Event percentiles are out of order: {workload_id}")
        median = lambda key: statistics.median(sample[key] for sample in samples)
        allocated = median("allocated_bytes")
        results.append({
            "id": workload_id,
            **{field: first[field] for field in fields},
            "sample_count": len(samples),
            "median_ms": median("elapsed_ns") / 1e6,
            "median_cpu_ms": median("cpu_ns") / 1e6,
            "median_allocated_bytes": allocated,
            "allocated_bytes_per_operation": allocated / first["operations"],
            "allocation_per_input_byte": allocated / first["input_bytes"]
            if first["input_bytes"] else None,
            "median_event_p95_ms": statistics.median(
                sample["event_latency"]["p95_ns"] for sample in samples) / 1e6,
            "median_event_p99_ms": statistics.median(
                sample["event_latency"]["p99_ns"] for sample in samples) / 1e6,
            "max_event_ms": max(sample["event_latency"]["max_ns"] for sample in samples) / 1e6,
            "gc_count": sum(sample["gc_count"] for sample in samples),
            "gc_ms": sum(sample["gc_ms"] for sample in samples),
            "fork_median_ms": [statistics.median(sample["elapsed_ns"]
                               for sample in entry["samples"]) / 1e6 for entry in entries],
        })
    return results


def observations(workloads):
    by_id = {workload["id"]: workload for workload in workloads}
    signals = []
    idle = by_id.get("terminal.idle-full")
    if idle and idle["allocated_bytes_per_operation"] > 1024 * 1024:
        signals.append({
            "id": "terminal-idle-allocation",
            "workloads": [idle["id"]],
            "message": "Unchanged full terminal refresh allocates "
            f"{idle['allocated_bytes_per_operation'] / 1048576:.2f} MiB per refresh "
            "(two production snapshots). Diagnostic threshold: 1 MiB.",
        })
    pairs = [
        ("chat.assistant-16k-64b", "chat.assistant-32k-64b"),
        ("chat.assistant-32k-64b", "chat.assistant-64k-64b"),
        ("chat.tool-16k-128b", "chat.tool-32k-128b"),
        ("chat.reasoning-16k-128b", "chat.reasoning-32k-128b"),
    ]
    for small_id, large_id in pairs:
        if small_id not in by_id or large_id not in by_id:
            continue
        small, large = by_id[small_id], by_id[large_id]
        if small["median_allocated_bytes"] <= 0:
            continue
        ratio = large["median_allocated_bytes"] / small["median_allocated_bytes"]
        if ratio > 3:
            signals.append({
                "id": "stream-allocation-scaling:" + large_id,
                "workloads": [small_id, large_id],
                "allocation_ratio": ratio,
                "input_ratio": large["input_bytes"] / small["input_bytes"],
                "message": f"{large_id}: doubling stream input allocates {ratio:.2f}x "
                "as much memory. Diagnostic threshold: 3x; inspect accumulated-text work.",
            })
    small = by_id.get("chat.assistant-64k-64b")
    batched = by_id.get("chat.assistant-64k-4096b")
    if small and batched and batched["median_allocated_bytes"] > 0:
        ratio = small["median_allocated_bytes"] / batched["median_allocated_bytes"]
        if ratio > 8:
            signals.append({
                "id": "chat-fragmentation",
                "workloads": [small["id"], batched["id"]],
                "allocation_ratio": ratio,
                "message": "Same 64 KiB assistant text allocates "
                f"{ratio:.2f}x as much with 64-byte versus 4096-byte events. "
                "Diagnostic threshold: 8x; event counts differ by design.",
            })
    return signals


def compare_baseline(report, baseline):
    if baseline.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("Unsupported baseline schema")
    # Reject incompatible fixtures/runtime rather than call a changed experiment a regression.
    for field in ("suite", "measurement_config", "harness_sha256"):
        if report[field] != baseline.get(field):
            raise ValueError(f"Baseline differs in {field}")
    for field in ("java_version", "cpu_model", "machine", "platform"):
        if report["environment"][field] != baseline.get("environment", {}).get(field):
            raise ValueError(f"Baseline environment differs in {field}")
    previous = {entry["id"]: entry for entry in baseline["workloads"]}
    if set(previous) != {entry["id"] for entry in report["workloads"]}:
        raise ValueError("Baseline workloads differ")
    comparisons = []
    for entry in report["workloads"]:
        old = previous[entry["id"]]
        for field in ("parameters", "operations", "input_bytes", "boundary"):
            if entry[field] != old[field]:
                raise ValueError(f"Baseline workload differs: {entry['id']} {field}")
        comparison = {"id": entry["id"]}
        for field in ("median_ms", "median_allocated_bytes", "median_event_p95_ms"):
            comparison[field + "_ratio"] = entry[field] / old[field] if old[field] > 0 else None
        comparisons.append(comparison)
    return {"source_revision": baseline["source_revision"], "workloads": comparisons,
            "note": "Host timing ratios are observations; shared runner load can vary."}


def markdown(report):
    lines = [f"# {report['suite'].title()} performance reproduction", "",
             f"Revision: `{report['source_revision']}`. "
             f"{report['measurement_config']['forks']} fresh JVMs; "
             f"{report['measurement_config']['warmups']} warmups and "
             f"{report['measurement_config']['samples']} measured samples per workload and JVM.", "",
             "Production Java sources compiled directly with javac. Setup, fixture generation, "
             "correctness checks and teardown are outside measurement. Allocations and CPU time "
             "cover the replay thread; GC counters cover the JVM. p95 is the median of per-sample "
             "event p95 values. Allocation is churn, not retained heap.", "",
             "These host measurements exclude Android rendering/layout, device FPS, native "
             "PTY/process execution and network latency.", "",
             "| Workload | Median ms | Event p95 ms | Allocated MiB | Bytes/event | Alloc/input |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for entry in report["workloads"]:
        amplification = entry["allocation_per_input_byte"]
        amplification = f"{amplification:.1f}x" if amplification is not None else "—"
        lines.append(f"| `{entry['id']}` | {entry['median_ms']:.2f} | "
                     f"{entry['median_event_p95_ms']:.3f} | "
                     f"{entry['median_allocated_bytes'] / 1048576:.2f} | "
                     f"{entry['allocated_bytes_per_operation']:.0f} | {amplification} |")
    lines += ["", "## Observed signals", "",
              "Signals are diagnostic and report-only by default; they are not device budgets.", ""]
    lines += ["- " + signal["message"] for signal in report["signals"]] or [
        "No configured diagnostic threshold was exceeded."]
    if "baseline" in report:
        lines += ["", "## Baseline comparison", "", report["baseline"]["note"], "",
                  "| Workload | Time ratio | Allocation ratio | Event p95 ratio |",
                  "| --- | ---: | ---: | ---: |"]
        for entry in report["baseline"]["workloads"]:
            ratios = [entry[field + "_ratio"] for field in
                      ("median_ms", "median_allocated_bytes", "median_event_p95_ms")]
            ratios = [f"{ratio:.2f}x" if ratio is not None else "—" for ratio in ratios]
            lines.append(f"| `{entry['id']}` | " + " | ".join(ratios) + " |")
    lines += ["", "## Measurement boundaries", ""]
    lines += [f"- `{entry['id']}`: {entry['boundary']}" for entry in report["workloads"]]
    lines += ["", f"Java: `{report['environment']['java_version'].splitlines()[0]}`. "
              f"CPU: `{report['environment']['cpu_model']}`.", "",
              "Raw fork JSONL, per-event percentiles, JVM flags, source hashes and environment "
              "are attached. Optional JFR recordings come from a separate profiling replay, "
              "not the aggregated measurements. JFR covers that entire process, including setup "
              "and warmups; inspect stacks under Workload.execute for measured paths.", ""]
    return "\n".join(lines)
