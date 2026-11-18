"""JSON and Markdown ramp reports."""

from __future__ import annotations

import json
from pathlib import Path


def write_report(report: dict, directory: Path) -> tuple[Path, Path]:
    directory.mkdir(parents=True, exist_ok=True)
    json_path = directory / "report.json"
    md_path = directory / "report.md"
    json_path.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n")
    md_path.write_text(render_markdown(report))
    return json_path, md_path


def render_markdown(report: dict) -> str:
    kind = report.get("kind")
    if kind == "scale" and report.get("combined"):
        return _dual_scale_markdown(report)
    if kind == "scale":
        return _scale_markdown(report)
    if kind == "head-to-head":
        return _head_markdown(report)
    if kind == "head-to-head-series":
        return _head_series_markdown(report)
    if kind == "adr-0010":
        return _adr_markdown(report)
    lines = [
        f"# Load ramp: {report.get('target')} / {report.get('profile')}",
        "",
        report.get("description") or "",
        "",
        f"Stopped because **{report.get('stoppedBecause')}**.",
        "",
        f"Wall clock {report.get('elapsedSeconds')}s. "
        f"Cap {report.get('maxWallSeconds')}."
        + (
            f" Trial seconds left {report.get('trialSecondsLeft')}."
            if report.get("trialSecondsLeft") is not None
            else ""
        ),
        "",
        "Comparable drift is how far delivered ticks lag the wall clock, as a percent of the "
        "step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. "
        "Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. "
        "Values/s counts source ticks advanced on the sampled changing tags, scaled up to the "
        "whole point count. A read is slower than the source period, so one read can cover several ticks.",
        "",
        "| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |",
        "|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for step in report.get("steps") or []:
        lines.append("| " + " | ".join([
            str(step.get("points")),
            str(step.get("protocol")),
            str(step.get("periodMs")),
            _num((step.get("cpuPercentOfQuota") or {}).get("max")),
            _num((step.get("memoryPercent") or {}).get("max")),
            _mb((step.get("heapBytes") or {}).get("max")),
            _num((step.get("latencyMs") or {}).get("p50")),
            _num((step.get("latencyMs") or {}).get("p95")),
            _num(step.get("valuesPerSec")),
            _num(step.get("expectedValuesPerSec")),
            str(step.get("qualityErrors")),
            str(step.get("mismatches")),
            _num(step.get("driftPercent")),
        ]) + " |")
    history_rows = [step for step in report.get("steps") or [] if isinstance(step.get("history"), dict)]
    if history_rows:
        lines += [
            "",
            "Local history: rows the collector wrote between the first and last sample of the window "
            "(GET /api/v1/diagnostics/history/providers).",
            "",
            "| Points | Rows written | Rows/s | Dropped | Pending max |",
            "|---:|---:|---:|---:|---:|",
        ]
        for step in history_rows:
            history = step["history"]
            lines.append("| " + " | ".join([
                str(step.get("points")),
                str(history.get("rowsWritten")),
                _num(history.get("rowsPerSec")),
                str(history.get("dropped")),
                str(history.get("pendingMax")),
            ]) + " |")
    if report.get("error"):
        lines += ["", f"Error: {report['error']}"]
    notes = report.get("notes") or []
    if notes:
        lines += ["", "## Notes", ""]
        lines += [f"- {note}" for note in notes]
    lines.append("")
    return "\n".join(lines)


def _scale_markdown(report: dict) -> str:
    minimum = report.get("efficiencyMin")
    minimum_text = "" if minimum is None else f"{float(minimum):.2f}"
    lines = [
        f"# Scaling: {report.get('target')} / {report.get('profile')}",
        "",
        "Each tier ramps points at 100% changing and a 1 s period until a cutoff "
        "(CPU, memory, drift, or quality). T is the value changes per second at the last "
        "step that stayed inside the cutoffs.",
        "",
        f"Efficiency is `{report.get('formula') or 'E(n) = (T(n) / T(baseline)) / (n / baseline)'}`. "
        f"The ADR 0010 minimum is {minimum_text or '0.70'}. "
        "A tier that never reaches a cutoff, or whose simulator exceeds 80% of its cores first, "
        "has no efficiency: that number would just fall as the machine grows.",
        "",
        f"Baseline vCPU: {report.get('baselineCpus')}.",
        "",
        "| vCPU | Memory | Heap | Stopped | Cutoff | Points | Values/s | Efficiency | Meets minimum | Sim limited |",
        "|---:|---|---|---|---|---:|---:|---:|---|---|",
    ]
    for row in report.get("sizes") or []:
        meets = row.get("meetsMinimum")
        meets_text = "" if meets is None else ("yes" if meets else "no")
        lines.append("| " + " | ".join([
            str(row.get("cpus")),
            str(row.get("memory") or ""),
            str(row.get("heap") or ""),
            str(row.get("stoppedBecause") or ""),
            str(row.get("cutoff") or ""),
            str(row.get("points") if row.get("points") is not None else ""),
            _num(row.get("valuesPerSec")),
            _num(row.get("efficiency")),
            meets_text,
            "yes" if row.get("simulatorLimited") else "",
        ]) + " |")
    fleet = report.get("simulatorFleet")
    if fleet:
        lines += [
            "",
            f"Simulator fleet: {fleet.get('instances')} process(es), "
            f"{fleet.get('cpusEach')} vCPU and {fleet.get('memory')} each. "
            "One process is one core. The run flags a tier when any simulator exceeds 80% of its core.",
        ]
    notes = report.get("notes") or []
    if notes:
        lines += ["", "## Notes", ""]
        lines += [f"- {note}" for note in notes]
    lines.append("")
    return "\n".join(lines)


def _head_markdown(report: dict) -> str:
    size = report.get("size") or {}
    load = report.get("lastGoodLoad") or {}
    ours = report.get("ours") or {}
    comparison = report.get("comparison") or {}
    lines = [
        f"# Head-to-head {report.get('scenario')} {size.get('id')} "
        f"({size.get('vcpu')} vCPU / {size.get('ramGb')} GB)",
        "",
        f"the reference platform {report.get('referenceVersion')}. "
        f"Stopped because **{(report.get('reference') or {}).get('stoppedBecause')}**.",
        "",
        "One size is one 2 h trial. a manual license reset is manual. The harness pauses for it and does not call it.",
        "",
        "## Last good load",
        "",
        f"- Points: {load.get('points')}",
        f"- Period ms: {load.get('periodMs')}",
        f"- Percent changing: {load.get('percentChanging')}",
        f"- Protocol: {load.get('protocol')}",
        f"- Values/s: {_num(load.get('valuesPerSec'))}",
        f"- Good-quality tags: {load.get('goodQualityTags')}",
        "",
        "## Our result at that load",
        "",
        f"- Points: {ours.get('points')}",
        f"- Values/s: {_num(ours.get('valuesPerSec'))}",
        f"- Good-quality tags: {ours.get('goodQualityTags')}",
        f"- Steady cutoff: {ours.get('steadyCutoff') or ''}",
        f"- Held the load: {'yes' if comparison.get('held') else 'no'}",
        "",
    ]
    if report.get("dryRun"):
        lines += ["Dry-run. These numbers are not a measurement.", ""]
    notes = report.get("notes") or []
    if notes:
        lines += ["## Notes", ""]
        lines += [f"- {note}" for note in notes]
        lines.append("")
    return "\n".join(lines)


def _head_series_markdown(report: dict) -> str:
    lines = [
        f"# Head-to-head {report.get('scenario')} / the reference platform {report.get('referenceVersion')}",
        "",
        "Each size is its own trial. a manual license reset stays a manual pause.",
        "",
        "| Size | vCPU | RAM GB | Last-good points | Last-good values/s | Our points | Our values/s | Held |",
        "|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for size in report.get("sizes") or []:
        load = size.get("lastGoodLoad") or {}
        ours = size.get("ours") or {}
        lines.append("| " + " | ".join([
            str(size.get("id")),
            str(size.get("vcpu")),
            str(size.get("ramGb")),
            str(load.get("points") if load.get("points") is not None else ""),
            _num(load.get("valuesPerSec")),
            str(ours.get("points") if ours.get("points") is not None else ""),
            _num(ours.get("valuesPerSec")),
            "yes" if size.get("held") else "no",
        ]) + " |")
    notes = report.get("notes") or []
    if notes:
        lines += ["", "## Notes", ""]
        lines += [f"- {note}" for note in notes]
    lines.append("")
    return "\n".join(lines)


def _dual_scale_markdown(report: dict) -> str:
    minimum = report.get("efficiencyMin")
    minimum_text = "0.70" if minimum is None else f"{float(minimum):.2f}"
    lines = [
        f"# Scaling: {report.get('target')} / {report.get('profile')}",
        "",
        "E(n) is computed twice against the 2 vCPU baseline: value changes per second, "
        "and tag capacity (the last good point count on a 1 s scan with 10% changing).",
        "",
        f"Formula: `{report.get('formula') or 'E(n) = (T(n) / T(baseline)) / (n / baseline)'}`. "
        f"The minimum is {minimum_text}. A tier below that minimum fails.",
        "",
        "| vCPU | Memory | Values/s | E throughput | Tags | E capacity | Meets minimum |",
        "|---:|---|---:|---:|---:|---:|---|",
    ]
    for row in report.get("combined") or []:
        throughput = row.get("throughput") or {}
        capacity = row.get("tagCapacity") or {}
        meets = row.get("meetsMinimum")
        meets_text = "" if meets is None else ("yes" if meets else "no")
        lines.append("| " + " | ".join([
            str(row.get("cpus")),
            str(row.get("memory") or throughput.get("memory") or ""),
            _num(throughput.get("valuesPerSec")),
            _num(throughput.get("efficiency")),
            str(capacity.get("points") if capacity.get("points") is not None else ""),
            _num(capacity.get("efficiency")),
            meets_text,
        ]) + " |")
    notes = report.get("notes") or []
    if notes:
        lines += ["", "## Notes", ""]
        lines += [f"- {note}" for note in notes]
    lines.append("")
    return "\n".join(lines)


def _adr_markdown(report: dict) -> str:
    passed = "yes" if report.get("gatePassed") else "no"
    lines = [
        f"# ADR {report.get('adr')} {report.get('plan')}",
        "",
        "Dry-run against the local simulator. This is not a gate result.",
        "",
        f"Measured: {'yes' if report.get('measured') else 'no'}. "
        f"Gate passed: {passed}. "
        f"Comparison VM contacted: {'yes' if report.get('comparisonVmContacted') else 'no'}.",
        "",
        "Targets: " + ", ".join(str(item) for item in report.get("targets") or []) + ".",
        "",
        f"Trial window {((report.get('trial') or {}).get('trialMinutes'))} minutes. "
        f"Procedure cap {((report.get('trial') or {}).get('procedureCapMinutes'))} minutes. "
        "a manual license reset stays manual. The harness does not POST the trial route.",
        "",
    ]
    for section in report.get("sections") or []:
        columns = section.get("columns") or []
        lines += [f"## {section.get('title')}", ""]
        if columns:
            lines.append("| " + " | ".join(str(column) for column in columns) + " |")
            lines.append("|" + "|".join("---" for _ in columns) + "|")
            for row in section.get("rows") or []:
                cells = [str(cell).replace("|", "/") for cell in row]
                lines.append("| " + " | ".join(cells) + " |")
            lines.append("")
        if section.get("note"):
            lines += [section["note"], ""]
    sim = report.get("simulator") or {}
    if sim:
        lines += [
            "## Local simulator",
            "",
            f"Protocols: {', '.join(sim.get('protocols') or [])}. "
            f"Formula match: {'yes' if sim.get('formulaMatch') else 'no'}. "
            f"Changing index {sim.get('changingIndex')} value {sim.get('changingValue')}. "
            f"Steady index {sim.get('steadyIndex')} value {sim.get('steadyValue')}.",
            "",
        ]
    operator = report.get("operator") or {}
    for title, key in (
        ("Hardware Nick supplies", "hardware"),
        ("Trial window Nick supplies", "trialWindow"),
        ("Steps Nick starts himself", "stepsNickStarts"),
    ):
        rows = operator.get(key) or []
        if not rows:
            continue
        lines += [f"## {title}", ""]
        lines += [f"- {row}" for row in rows]
        lines.append("")
    notes = report.get("notes") or []
    if notes:
        lines += ["## Notes", ""]
        lines += [f"- {note}" for note in notes]
        lines.append("")
    return "\n".join(lines)


def _num(value) -> str:
    if value is None:
        return ""
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def _mb(value) -> str:
    if value is None:
        return ""
    return f"{value / (1024 * 1024):.1f}"
