#!/usr/bin/env python3
"""Fail if ADR 0010 performance numbers drift, or if a scenario is marked done with no test.

PRF-004, PRF-005, and PRF-006 are the tag-capacity floors. PRF-007 is the CI tag-engine
scan. PRF-009 and PRF-010 are the driver floors on a container bridge. PRF-011 is the HMI
session farm (web/perf/prf011.spec.ts), run by the web job's Playwright step. Every other
scenario in test-infra/perf/perf-requirements.json stays implemented=false, and its FEATURES
row stays todo, until a later change adds a real test file and flips the flag.
PRF-003 and PRF-008 stay implemented=false: their CI-size runs (test-infra/load-soak) are
checked here through the size files in test-infra/perf and the CI-size reports.
PRF-013 is built (scripts/perf_regression.py, run by the Load bench job): more than 15% worse than result.baseline fails,
and a result 15% worse does not.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
REQ_PATH = ROOT / "test-infra/perf/perf-requirements.json"
SPEC = ROOT / "docs/agent/SPEC.md"
PLAN = ROOT / "docs/agent/PLAN.md"
PLAYBOOK = ROOT / "docs/agent/PLAYBOOK.md"
FEATURES = ROOT / "docs/agent/FEATURES.md"
ADR = ROOT / "docs/agent/DECISIONS/0010-performance-requirements.md"
LEGACY = ROOT / "gateway/tags/src/test/kotlin/org/oip/tags/TagBenchmarkTest.kt"
SIM_README = ROOT / "test-infra/perf/README.md"
CI = ROOT / ".github/workflows/ci.yml"

REQUIRED_IDS = [f"PRF-{i:03d}" for i in range(1, 14)]

# Floors Nick set on 2026-10-27. A silent edit of the JSON fails here.
EXPECT = {
    "PRF-003": {"threshold": 50000, "hardware": "S8", "durationMinutes": 60, "clients": 75, "phase": 3},
    "PRF-004": {"threshold": 100000, "hardware": "S8", "changePercent": 10, "scanSeconds": 1, "phase": 2},
    "PRF-005": {"threshold": 150000, "hardware": "S16", "changePercent": 6, "scanSeconds": 1, "phase": 2},
    "PRF-006": {"threshold": 88500, "hardware": "EDGE", "scanSeconds": 1, "phase": 2},
    "PRF-007": {"threshold": 100000, "changePercent": 100, "cpuPercentOfOneCoreMax": 25, "scanSeconds": 1, "phase": 2},
    "PRF-008": {"threshold": 10000, "stretch": 30000, "stretchGates": False, "durationMinutes": 180, "phase": 3},
    "PRF-009": {"threshold": 10000, "networkedSimulator": True, "loopbackAllowed": False, "phase": 2},
    "PRF-010": {"threshold": 50000, "networkedSimulator": True, "loopbackAllowed": False, "phase": 2},
    "PRF-011": {"thresholdMsP95": 250, "sessions": 50, "tagsPerSession": 300, "phase": 4},
    "PRF-012": {"minPoints": 150000, "phase": 2},
    "PRF-013": {"regressionFailPercentGreaterThan": 15, "phase": 2},
}

PRF007_TEST = "gateway/tags/src/test/kotlin/org/oip/tags/TagPrf007BenchmarkTest.kt"
DRIVER_FLOOR_TEST = "test-infra/driver-floors/src/driverFloor/kotlin/org/oip/testinfra/DriverFloorTest.kt"
CAPACITY_TEST = "gateway/server/src/test/kotlin/org/oip/gateway/TagCapacityFloorTest.kt"
CAPACITY_GRADLE = ROOT / "gateway/server/build.gradle.kts"
PRF011_TEST = "web/perf/prf011.spec.ts"
PRF011_SIZES = ROOT / "web/perf/prf011.ts"
PLAYWRIGHT_CONFIG = ROOT / "web/playwright.config.ts"
LOAD_SOAK_DIR = "test-infra/load-soak/src/loadSoak/kotlin/org/oip/loadsoak"
LOAD_SOAK_TESTS = {"PRF-003": "FullLoadTest.kt", "PRF-008": "HistorianSoakTest.kt"}
REPORTS = ROOT / "docs/agent/reports"
CAPACITY_PHRASES = (
    "100_000",
    "150_000",
    "88_500",
    "changePercent = 10,",
    "changePercent = 6,",
    "changePercent = 100,",
    "localHistory = true",
    "CPU_CUTOFF = 50.0",
    "MEMORY_CUTOFF = 50.0",
    "DRIFT_CUTOFF = 10.0",
    "vcpu = 8",
    "ramGb = 16",
    "vcpu = 16",
    "ramGb = 32",
    "vcpu = 4",
    "ramGb = 4",
    "VmRSS",
    "processCpuTime",
)


def worse_percent(baseline: float, measured: float, lower_is_better: bool) -> float:
    """Positive when [measured] is worse than [baseline]."""
    if baseline == 0:
        raise ValueError("baseline must be non-zero")
    change = (measured - baseline) / abs(baseline) * 100.0
    return change if lower_is_better else -change


def regression_fails(baseline: float, measured: float, lower_is_better: bool, limit_percent: float = 15.0) -> bool:
    """True when the result is more than limit_percent worse. Exactly limit_percent does not fail (PRF-013)."""
    return worse_percent(baseline, measured, lower_is_better) > limit_percent


def _self_check_regression() -> list[str]:
    errors: list[str] = []
    if regression_fails(0.10, 0.115, True):
        errors.append("a CPU fraction 15% worse must not fail PRF-013")
    if not regression_fails(0.10, 0.116, True):
        errors.append("a CPU fraction 16% worse must fail PRF-013")
    if regression_fails(100.0, 85.0, False):
        errors.append("throughput 15% lower must not fail PRF-013")
    if not regression_fails(100.0, 84.0, False):
        errors.append("throughput 16% lower must fail PRF-013")
    if regression_fails(0.0123, 0.0123, True):
        errors.append("an unchanged CPU fraction must not fail PRF-013")
    return errors


def check_runner_baselines(sid: str, result: dict, ceiling: float, text: str, errors: list[str]) -> None:
    """Optional result.runnerBaselines: the same metric measured on another hardware class (PRF-013
    compares a run only with the baseline of its own class). Each entry names its class, a number
    under the ceiling, and where it was measured; the test reads them from this file."""
    entries = result.get("runnerBaselines")
    if entries is None:
        return
    if not isinstance(entries, list) or not entries:
        errors.append(f"{sid} result.runnerBaselines must be a non-empty list")
        return
    seen = {result.get("hardware")}
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(f"{sid} runnerBaselines entries must be objects")
            continue
        hardware = entry.get("hardware")
        if not isinstance(hardware, str) or not hardware:
            errors.append(f"{sid} runnerBaselines entry needs a hardware class")
        elif hardware in seen:
            errors.append(f"{sid} runnerBaselines has a second baseline for {hardware}")
        else:
            seen.add(hardware)
        value = entry.get("baseline")
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 < float(value) < ceiling:
            errors.append(f"{sid} runnerBaselines {hardware} baseline must be above 0 and under the {ceiling} ceiling")
        if not isinstance(entry.get("measured"), str) or not entry.get("measured"):
            errors.append(f"{sid} runnerBaselines {hardware} must say where it was measured")
    if text and "runnerBaselines" not in text:
        errors.append(f"{sid} test must read result.runnerBaselines")


def check_prf007(sc: dict, text: str, errors: list[str]) -> None:
    if sc.get("implemented") is not True:
        errors.append("PRF-007 must be implemented")
    if sc.get("test") != PRF007_TEST:
        errors.append(f"PRF-007 test path must be {PRF007_TEST}")
    result = sc.get("result")
    if not isinstance(result, dict):
        errors.append("PRF-007 result object missing")
        result = {}
    if result.get("hardware") != "ci":
        errors.append("PRF-007 result.hardware must be ci")
    if result.get("metric") != "cpuFractionOfOneCore":
        errors.append("PRF-007 result.metric must be cpuFractionOfOneCore")
    if result.get("lowerIsBetter") is not True:
        errors.append("PRF-007 result.lowerIsBetter must be true")
    if result.get("unit") != "fractionOfOneCore":
        errors.append("PRF-007 result.unit must be fractionOfOneCore")
    if result.get("window") != "best-of-7":
        errors.append("PRF-007 result.window must be best-of-7")
    if result.get("subscribers") != 4:
        errors.append("PRF-007 result.subscribers must be 4")
    check_runner_baselines("PRF-007", result, (sc.get("cpuPercentOfOneCoreMax") or 0) / 100.0, text, errors)
    baseline = result.get("baseline")
    if isinstance(baseline, bool) or not isinstance(baseline, (int, float)):
        errors.append("PRF-007 result.baseline must be a number")
    else:
        ceiling = (sc.get("cpuPercentOfOneCoreMax") or 0) / 100.0
        if not (0 < float(baseline) < ceiling):
            errors.append(f"PRF-007 baseline {baseline} must be above 0 and under the {ceiling} ceiling")
        shown = f"{float(baseline):.4f}"
        if shown not in text:
            errors.append(f"PRF-007 test must cite the accepted baseline {shown}")
    if not text:
        errors.append(f"PRF-007 test file missing: {PRF007_TEST}")
        return
    for phrase in ("100_000", "0.25", "processCpuTime", "cpuFractionOfOneCore", "WINDOWS = 7", "SUBSCRIBERS = 4"):
        if phrase not in text:
            errors.append(f"PRF-007 test missing {phrase!r}")


def _near(actual, expected, tol: float = 1e-4) -> bool:
    return isinstance(actual, (int, float)) and not isinstance(actual, bool) and abs(float(actual) - float(expected)) <= tol


# Scenarios whose CI test writes samples straight into the tag engine (no driver, no networked simulator, no
# clients) and scales CPU to a reference size it does not run on. The FEATURES row stays todo until a
# driver-plus-simulator run on the reference sizes exists (review-2 F9; PLAN P2-06).
ENGINE_ONLY = {"PRF-004", "PRF-005", "PRF-006"}

# Scenarios whose test runs on every PR at CI size, but whose ADR 0010 definition (three consecutive passes on the
# baseline's own reference hardware, PRF-013 enforced there) has not been met. The FEATURES row stays todo with the
# test linked until the lead records those runs (review-4 F1, F11; PLAN P4-01).
REFERENCE_PENDING = {"PRF-011"}


def check_tag_capacity(sid: str, sc: dict, test_text: str, errors: list[str]) -> None:
    """PRF-004 / PRF-005 / PRF-006. Baseline is CPU percent of the reference size. Lower is better."""
    if sc.get("implemented") is not True:
        errors.append(f"{sid} must be implemented")
    if sc.get("test") != CAPACITY_TEST:
        errors.append(f"{sid} test path must be {CAPACITY_TEST}")
    result = sc.get("result")
    if not isinstance(result, dict):
        errors.append(f"{sid} result object missing")
        result = {}
    hardware = sc.get("hardware")
    if result.get("hardware") != hardware:
        errors.append(f"{sid} result.hardware must be {hardware}")
    if result.get("metric") != "cpuPercent":
        errors.append(f"{sid} result.metric must be cpuPercent")
    if result.get("lowerIsBetter") is not True:
        errors.append(f"{sid} result.lowerIsBetter must be true")
    if result.get("unit") != "percentOfMachine":
        errors.append(f"{sid} result.unit must be percentOfMachine")
    if result.get("window") != "8x1s":
        errors.append(f"{sid} result.window must be 8x1s")
    if result.get("tags") != sc.get("threshold"):
        errors.append(f"{sid} result.tags must stay the floor {sc.get('threshold')}")
    threshold = sc.get("threshold")
    baseline = result.get("baseline")
    if isinstance(baseline, bool) or not isinstance(baseline, (int, float)):
        errors.append(f"{sid} result.baseline must be a number")
        baseline = None
    elif not 0 < float(baseline) <= 50:
        errors.append(f"{sid} baseline {baseline} must be above 0 and at or under the 50 CPU cutoff")
    else:
        shown = f"{float(baseline):.4f}"
        if shown not in test_text:
            errors.append(f"{sid} test must cite the accepted baseline {shown}")
    memory = result.get("memoryPercent")
    drift = result.get("clockDriftPercent")
    if isinstance(memory, bool) or not isinstance(memory, (int, float)) or not 0 < float(memory) <= 50:
        errors.append(f"{sid} result.memoryPercent must be above 0 and at or under 50")
    if isinstance(drift, bool) or not isinstance(drift, (int, float)) or abs(float(drift)) > 10:
        errors.append(f"{sid} result.clockDriftPercent must stay inside +/-10")
    if sid == "PRF-006":
        if result.get("localHistory") is not True or result.get("changePercent") != 100:
            errors.append("PRF-006 result must keep local history on and every tag changing")
        if result.get("historyRows") != 708000:
            errors.append("PRF-006 historyRows must stay 88500 tags x 8 scans")
    else:
        if result.get("localHistory") is not False:
            errors.append(f"{sid} result.localHistory must stay false")
        if result.get("changePercent") != sc.get("changePercent"):
            errors.append(f"{sid} result.changePercent must stay {sc.get('changePercent')}")
    report_path = ROOT / "docs" / "agent" / "reports" / f"{sid.lower()}.json"
    md_path = report_path.with_suffix(".md")
    if not report_path.is_file():
        errors.append(f"{sid} report missing: {report_path.relative_to(ROOT)}")
        report = {}
    else:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    if not md_path.is_file():
        errors.append(f"{sid} markdown report missing: {md_path.relative_to(ROOT)}")
    else:
        md = md_path.read_text(encoding="utf-8")
        if "inside the cutoffs" not in md or sid not in md:
            errors.append(f"{sid} markdown report must say the run is inside the cutoffs")
        if baseline is not None and f"{float(baseline):.4f}" not in md:
            errors.append(f"{sid} markdown report must show the accepted CPU baseline")
    if report:
        if report.get("scenario") != sid or report.get("hardware") != hardware:
            errors.append(f"{sid} report scenario/hardware drifted")
        if report.get("tags") != threshold or report.get("scanSeconds") != 1:
            errors.append(f"{sid} report must keep the floor tag count at a 1 s scan")
        if report.get("intervals") != 8:
            errors.append(f"{sid} report window must stay 8 intervals")
        if report.get("insideCutoffs") is not True:
            errors.append(f"{sid} report is not inside the cutoffs")
        for key in ("qualityErrors", "mismatches", "errors"):
            if report.get(key) != 0:
                errors.append(f"{sid} report {key} must be 0")
        cpu = report.get("cpuPercent")
        mem = report.get("memoryPercent")
        reported_drift = report.get("clockDriftPercent")
        if baseline is not None and not _near(cpu, baseline):
            errors.append(f"{sid} report cpuPercent {cpu} != baseline {baseline}")
        if isinstance(cpu, bool) or not isinstance(cpu, (int, float)) or float(cpu) > 50:
            errors.append(f"{sid} report CPU {cpu} crosses the 50 cutoff")
        if isinstance(mem, bool) or not isinstance(mem, (int, float)) or float(mem) > 50:
            errors.append(f"{sid} report memory {mem} crosses the 50 cutoff")
        if isinstance(reported_drift, bool) or not isinstance(reported_drift, (int, float)) or abs(float(reported_drift)) > 10:
            errors.append(f"{sid} report clock drift {reported_drift} crosses +/-10")
        if not _near(mem, memory):
            errors.append(f"{sid} report memoryPercent drifted from result.memoryPercent")
        if not _near(reported_drift, drift):
            errors.append(f"{sid} report clockDriftPercent drifted from result.clockDriftPercent")
        head = report.get("headroom") if isinstance(report.get("headroom"), dict) else {}
        if isinstance(cpu, (int, float)) and not isinstance(cpu, bool) and not _near(head.get("cpuPercent"), 50 - float(cpu), 1e-3):
            errors.append(f"{sid} CPU headroom does not match 50 minus the measurement")
        if isinstance(mem, (int, float)) and not isinstance(mem, bool) and not _near(head.get("memoryPercent"), 50 - float(mem), 1e-3):
            errors.append(f"{sid} memory headroom does not match 50 minus the measurement")
        if (
            isinstance(reported_drift, (int, float))
            and not isinstance(reported_drift, bool)
            and not _near(head.get("clockDriftPercent"), 10 - abs(float(reported_drift)), 1e-3)
        ):
            errors.append(f"{sid} drift headroom does not match 10 minus the measurement")
        if sid == "PRF-006":
            if report.get("localHistory") is not True or report.get("changePercent") != 100:
                errors.append("PRF-006 report must keep local history and 100% changing")
            if report.get("historyRows") != 708000:
                errors.append("PRF-006 report must record 708000 history rows")
        else:
            if report.get("localHistory") is not False or report.get("changePercent") != sc.get("changePercent"):
                errors.append(f"{sid} report change fraction or history flag drifted")
            if report.get("historyRows") != 0:
                errors.append(f"{sid} report must not count history rows")
    if not test_text:
        errors.append(f"{sid} test file missing: {CAPACITY_TEST}")
        return
    if sid == "PRF-004":
        for phrase in CAPACITY_PHRASES:
            if phrase not in test_text:
                errors.append(f"{sid} test missing {phrase!r}")


def check_driver_floor(sid: str, sc: dict, text: str, errors: list[str]) -> None:
    """PRF-009 / PRF-010. Baseline is a floor or higher; higher is better. Do not lower the threshold."""
    if sc.get("implemented") is not True:
        errors.append(f"{sid} must be implemented")
    if sc.get("test") != DRIVER_FLOOR_TEST:
        errors.append(f"{sid} test path must be {DRIVER_FLOOR_TEST}")
    if sc.get("networkedSimulator") is not True or sc.get("loopbackAllowed") is not False:
        errors.append(f"{sid} must stay networked and must not allow loopback")
    result = sc.get("result")
    if not isinstance(result, dict):
        errors.append(f"{sid} result object missing")
        result = {}
    if result.get("hardware") != "ci":
        errors.append(f"{sid} result.hardware must be ci")
    if result.get("lowerIsBetter") is not False:
        errors.append(f"{sid} result.lowerIsBetter must be false")
    if result.get("network") != "docker-bridge":
        errors.append(f"{sid} result.network must be docker-bridge")
    threshold = sc.get("threshold")
    baseline = result.get("baseline")
    if isinstance(baseline, bool) or not isinstance(baseline, (int, float)):
        errors.append(f"{sid} result.baseline must be a number")
    elif not isinstance(threshold, (int, float)) or float(baseline) < float(threshold):
        errors.append(f"{sid} baseline {baseline} must be at least the floor {threshold}")
    else:
        shown = str(int(baseline)) if float(baseline).is_integer() else str(baseline)
        if shown not in text:
            errors.append(f"{sid} test must cite the accepted baseline {shown}")
    if not text:
        errors.append(f"{sid} test file missing: {DRIVER_FLOOR_TEST}")
        return
    for phrase in ("isLoopbackAddress", "bridge", "docker-bridge", "PRF-013"):
        if phrase not in text:
            errors.append(f"{sid} test missing {phrase!r}")
    if sid == "PRF-009":
        if result.get("metric") != "valuesPerSecond":
            errors.append("PRF-009 result.metric must be valuesPerSecond")
        if result.get("unit") != "valuesPerSecond":
            errors.append("PRF-009 result.unit must be valuesPerSecond")
        if result.get("points") != 10000 or result.get("scanPeriodMs") != 20:
            errors.append("PRF-009 result points/scanPeriodMs must stay 10000 / 20")
        if result.get("concurrentRequests") != 8:
            errors.append("PRF-009 result.concurrentRequests must stay 8")
        for phrase in ("10_000", "valuesPerSecond", "concurrentRequests", "peakInFlight", "125"):
            if phrase not in text:
                errors.append(f"PRF-009 test missing {phrase!r}")
    if sid == "PRF-010":
        if result.get("metric") != "goodMonitoredItems":
            errors.append("PRF-010 result.metric must be goodMonitoredItems")
        if result.get("unit") != "items":
            errors.append("PRF-010 result.unit must be items")
        for phrase in ("50_000", "monitoredItemCount", "goodMonitoredItems", "OpcQuality.Good"):
            if phrase not in text:
                errors.append(f"PRF-010 test missing {phrase!r}")


def check_prf011(sc: dict, errors: list[str]) -> None:
    """PRF-011. Baseline is the CI-size p95 in ms; lower is better and it must stay under the 250 ms ceiling."""
    if sc.get("implemented") is not True:
        errors.append("PRF-011 must be implemented")
    if sc.get("test") != PRF011_TEST:
        errors.append(f"PRF-011 test path must be {PRF011_TEST}")
    result = sc.get("result")
    if not isinstance(result, dict):
        errors.append("PRF-011 result object missing")
        result = {}
    expect = {
        "hardware": "ci",
        "metric": "p95LatencyMs",
        "lowerIsBetter": True,
        "unit": "ms",
        "window": "20s-steady",
        "sessions": 50,
        "tagsPerSession": 300,
        "changesPerSecondPerSession": 300,
    }
    for key, val in expect.items():
        if result.get(key) != val:
            errors.append(f"PRF-011 result.{key} must be {val!r}")
    rendered = result.get("renderedSessions")
    protocol = result.get("protocolSessions")
    if not isinstance(rendered, int) or isinstance(rendered, bool) or rendered < 1:
        errors.append("PRF-011 result.renderedSessions must be a positive integer")
    elif not isinstance(protocol, int) or isinstance(protocol, bool) or rendered + protocol != 50:
        errors.append("PRF-011 rendered plus protocol sessions must make the 50 sessions")
    baseline = result.get("baseline")
    ceiling = sc.get("thresholdMsP95") or 0
    spec = (ROOT / PRF011_TEST).read_text(encoding="utf-8") if (ROOT / PRF011_TEST).is_file() else ""
    sizes = PRF011_SIZES.read_text(encoding="utf-8") if PRF011_SIZES.is_file() else ""
    if isinstance(baseline, bool) or not isinstance(baseline, (int, float)):
        errors.append("PRF-011 result.baseline must be a number")
    elif not 0 < float(baseline) < float(ceiling):
        errors.append(f"PRF-011 baseline {baseline} must be above 0 and under the {ceiling} ms ceiling")
    else:
        shown = str(int(baseline)) if float(baseline).is_integer() else str(baseline)
        if f"BASELINE_P95_MS = {shown};" not in sizes:
            errors.append(f"web/perf/prf011.ts must cite the accepted baseline {shown}")
    if not spec:
        errors.append(f"PRF-011 test file missing: {PRF011_TEST}")
    for phrase in ("expect(stats.p95).toBeLessThan(250)", "MutationObserver", "/api/v1/ws", "regressionFails", "PRF-013"):
        if phrase not in spec:
            errors.append(f"PRF-011 test missing {phrase!r}")
    for phrase in ("sessions: 50,", "tags: 300,", "tagPeriodMs: 1_000,", f"rendered: {rendered},", "> 15"):
        if phrase not in sizes:
            errors.append(f"web/perf/prf011.ts missing {phrase!r}")
    report = ROOT / "docs" / "agent" / "reports" / "prf-011.md"
    if not report.is_file():
        errors.append("PRF-011 report missing: docs/agent/reports/prf-011.md")
    else:
        md = report.read_text(encoding="utf-8")
        if "PRF-011" not in md or (isinstance(baseline, (int, float)) and f"{baseline} ms" not in md):
            errors.append("PRF-011 report must name the scenario and the accepted baseline")
    playwright = PLAYWRIGHT_CONFIG.read_text(encoding="utf-8") if PLAYWRIGHT_CONFIG.is_file() else ""
    if 'name: "prf011"' not in playwright or 'testMatch: "prf011.spec.ts"' not in playwright:
        errors.append("web/playwright.config.ts must keep the prf011 project the web job runs")


SPEC_PHRASES = [
    "10k tags @1 s stable CPU",
    "withdrawn",
    "the reference platform 8.3.10",
    "CPU above 50%",
    "memory above 50%",
    "+/-10%",
    "2 h",
    "0.70",
    "2 GB RAM per vCPU",
    "50,000 value changes/s",
    "100,000 tags",
    "10% changing",
    "150,000 tags",
    "6% changing",
    "88,500 tags",
    "100,000 tags at 1 Hz",
    "25% of one core",
    "10,000 changes/s",
    "30,000",
    "3 h",
    "10,000 values/s",
    "50,000 OPC UA monitored items",
    "250 ms",
    "50 sessions",
    "300 tags",
    "more than 15%",
    "150,000 points",
    "a manual license reset",
    "PRF-007",
]


def _int(v) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) else None


def _load_sizes(name: str, errors: list[str]) -> tuple[dict, dict] | None:
    path = ROOT / "test-infra" / "perf" / f"{name}.json"
    if not path.is_file():
        errors.append(f"test-infra/perf/{name}.json missing")
        return None
    sizes = json.loads(path.read_text(encoding="utf-8")).get("sizes") or {}
    full, ci = sizes.get("full"), sizes.get("ci")
    if not isinstance(full, dict) or not isinstance(ci, dict):
        errors.append(f"{name} must have full and ci sizes")
        return None
    for size, body in (("full", full), ("ci", ci)):
        for key, val in body.items():
            if _int(val) is None or val < 0:
                errors.append(f"{name} {size}.{key} must be a non-negative integer")
                return None
    return full, ci


def check_prf003_sizes(sc: dict, errors: list[str]) -> dict | None:
    """Full size keeps the ADR 0010 floor; CI size stays below it in points and time."""
    loaded = _load_sizes("prf-003", errors)
    if loaded is None:
        return None
    full, ci = loaded
    floor, minutes, clients = sc.get("threshold"), sc.get("durationMinutes"), sc.get("clients")
    keys = {"points", "modbusPoints", "seconds", "warmupSeconds", "sessions", "tagsPerSession", "alarmBit", "scanMillis", "commsTimeoutMillis"}
    for size, body in (("full", full), ("ci", ci)):
        if set(body) != keys:
            errors.append(f"prf-003 {size} keys {sorted(body)} != {sorted(keys)}")
            return None
        if not 0 < body["modbusPoints"] < body["points"]:
            errors.append(f"prf-003 {size} must split points between Modbus and OPC UA")
        if not 0 < body["scanMillis"] < 1000:
            errors.append(f"prf-003 {size} scanMillis must stay under the 1000 ms simulator period")
        if body["sessions"] != clients:
            errors.append(f"prf-003 {size} sessions must stay {clients} (ADR 0010 clients)")
        if not 0 < body["tagsPerSession"] <= body["points"]:
            errors.append(f"prf-003 {size} tagsPerSession must be 1 to points")
        if body["warmupSeconds"] >= body["seconds"]:
            errors.append(f"prf-003 {size} warmupSeconds must be shorter than seconds")
    # Every point changes once per 1000 ms, so points is the offered changes/s.
    if full["points"] < floor:
        errors.append(f"prf-003 full points {full['points']} below the {floor} changes/s floor")
    if full["seconds"] < minutes * 60:
        errors.append(f"prf-003 full seconds {full['seconds']} shorter than {minutes} min")
    if full["points"] <= ci["points"] or ci["points"] >= floor:
        errors.append("prf-003 ci points must stay below full and below the floor")
    if ci["seconds"] >= minutes * 60:
        errors.append("prf-003 ci seconds must stay below the full window")
    return ci


def check_prf008_sizes(sc: dict, errors: list[str]) -> dict | None:
    """Full size keeps the floor, the stretch, and 3 h; CI size stays below in tags and time."""
    loaded = _load_sizes("prf-008", errors)
    if loaded is None:
        return None
    full, ci = loaded
    floor, stretch, minutes = sc.get("threshold"), sc.get("stretch"), sc.get("durationMinutes")
    keys = {"tags", "percent", "steadySeconds", "cutAtSeconds", "cutSeconds", "stretchPercent", "stretchSeconds", "drainBudgetSeconds"}
    for size, body in (("full", full), ("ci", ci)):
        if set(body) != keys:
            errors.append(f"prf-008 {size} keys {sorted(body)} != {sorted(keys)}")
            return None
        if not 0 < body["percent"] <= 100 or not 0 < body["stretchPercent"] <= 100:
            errors.append(f"prf-008 {size} percents must be 1-100")
        if body["cutSeconds"] < 1 or not 0 < body["cutAtSeconds"] < body["cutAtSeconds"] + body["cutSeconds"] < body["steadySeconds"]:
            errors.append(f"prf-008 {size} must cut the database inside the steady phase")
        if body["stretchSeconds"] < 1 or body["drainBudgetSeconds"] < 1:
            errors.append(f"prf-008 {size} needs a stretch phase and a drain budget")
        # The steady rate must reach the floor at both sizes: the CI size is shorter, not slower.
        if body["tags"] * body["percent"] < floor * 100:
            errors.append(f"prf-008 {size} steady rate below the {floor} changes/s floor")
    if full["tags"] * full["stretchPercent"] < stretch * 100:
        errors.append(f"prf-008 full stretch rate below {stretch} changes/s")
    if full["steadySeconds"] < minutes * 60:
        errors.append(f"prf-008 full steadySeconds {full['steadySeconds']} shorter than {minutes} min")
    if ci["tags"] >= full["tags"] or ci["steadySeconds"] >= minutes * 60 or ci["stretchSeconds"] >= full["stretchSeconds"]:
        errors.append("prf-008 ci tags and times must stay below full")
    return ci


def check_ci_report(sid: str, ci: dict, errors: list[str]) -> None:
    """The CI-size report must exist, say CI-size, match the ci size, and show zero loss inside the cutoffs."""
    name = sid.lower()
    json_path, md_path = REPORTS / f"{name}.json", REPORTS / f"{name}.md"
    if not json_path.is_file() or not md_path.is_file():
        errors.append(f"docs/agent/reports/{name}.json and .md must exist")
        return
    rep = json.loads(json_path.read_text(encoding="utf-8"))
    md = md_path.read_text(encoding="utf-8")
    if rep.get("scenario") != sid or rep.get("size") != "ci" or rep.get("label") != "CI-size":
        errors.append(f"{name}.json must be scenario {sid}, size ci, label CI-size")
    if not md.startswith(f"# {sid} ") or "(CI-size)" not in md.splitlines()[0] or "implemented: false" not in md:
        errors.append(f"{name}.md must be headed '{sid} ... (CI-size)' and say implemented: false")
    if rep.get("zeroLoss") is not True or rep.get("insideCutoffs") is not True:
        errors.append(f"{name}.json must show zeroLoss and insideCutoffs true")
    if rep.get("network") != "docker-bridge":
        errors.append(f"{name}.json must be measured on the docker bridge")
    if sid == "PRF-003":
        same = {k: ci[k] for k in ("points", "modbusPoints", "sessions", "tagsPerSession", "alarmBit", "scanMillis", "commsTimeoutMillis")}
        rate = rep.get("changesPerSecond")
        if not isinstance(rate, (int, float)) or rate < 0.98 * ci["points"]:
            errors.append("prf-003.json changes/s must reach 98% of the offered rate")
        for key in ("historyCountMismatches", "historySumMismatches", "notGoodChanges", "journalLostRows", "collectorDropped", "quarantined", "staleSessionValues"):
            if rep.get(key) != 0:
                errors.append(f"prf-003.json {key} must be 0")
        if rep.get("historyRows") != rep.get("changes"):
            errors.append("prf-003.json historyRows must equal changes")
    else:
        same = {k: ci[k] for k in ("tags", "percent", "steadySeconds", "cutSeconds", "stretchPercent", "stretchSeconds")}
        steady = rep.get("steady") or {}
        if not isinstance(steady.get("changesPerSecond"), (int, float)) or steady["changesPerSecond"] < rep.get("floorChangesPerSecond", float("inf")):
            errors.append("prf-008.json steady changes/s must reach the floor")
        if rep.get("rows") != rep.get("expectedRows") or rep.get("distinctTagValues") != rep.get("rows"):
            errors.append("prf-008.json rows must equal expectedRows with no duplicates")
        for key in ("ruleViolations", "stampMismatches", "notGood", "dropped", "diverted", "quarantined", "writeErrors"):
            if rep.get(key) != 0:
                errors.append(f"prf-008.json {key} must be 0")
    for key, val in same.items():
        if rep.get(key) != val:
            errors.append(f"{name}.json {key} {rep.get(key)!r} != ci size {val!r}")


def feature_rows(text: str) -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    for n, line in enumerate(text.splitlines(), 1):
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells or cells[0] in {"ID", ""} or set(cells[0]) <= set("-: "):
            continue
        if len(cells) != 8:
            continue
        fid, area, feat, src, prio, phase, tests, status = cells
        rows[fid] = {
            "line": str(n),
            "area": area,
            "feature": feat,
            "src": src,
            "prio": prio,
            "phase": phase,
            "tests": tests,
            "status": status,
        }
    return rows


def section_11(spec: str) -> str:
    marker = "## 11. Quality attributes"
    idx = spec.find(marker)
    if idx < 0:
        return ""
    return spec[idx:]


def main() -> int:
    if not SPEC.is_file():
        print("performance checklist skipped: internal notes are not in this tree")
        return 0
    errors: list[str] = []
    if not REQ_PATH.is_file():
        print("ERROR: missing", REQ_PATH)
        return 1
    req = json.loads(REQ_PATH.read_text(encoding="utf-8"))
    spec = SPEC.read_text(encoding="utf-8")
    plan = PLAN.read_text(encoding="utf-8")
    playbook = PLAYBOOK.read_text(encoding="utf-8")
    features = FEATURES.read_text(encoding="utf-8")
    adr = ADR.read_text(encoding="utf-8") if ADR.is_file() else ""
    sec = section_11(spec)
    rows = feature_rows(features)
    errors.extend(_self_check_regression())
    prf007_text = (ROOT / PRF007_TEST).read_text(encoding="utf-8") if (ROOT / PRF007_TEST).is_file() else ""
    driver_floor_text = (ROOT / DRIVER_FLOOR_TEST).read_text(encoding="utf-8") if (ROOT / DRIVER_FLOOR_TEST).is_file() else ""
    capacity_text = (ROOT / CAPACITY_TEST).read_text(encoding="utf-8") if (ROOT / CAPACITY_TEST).is_file() else ""

    if req.get("adr") != "0010" or req.get("date") != "2026-10-27":
        errors.append("perf-requirements.json adr/date must stay 0010 / 2026-10-27")
    if req.get("decidedBy") != "Nick Hepburn":
        errors.append("perf-requirements.json decidedBy must be Nick Hepburn")
    retired = req.get("retired") or {}
    if retired.get("statement") != "10k tags @1 s stable CPU":
        errors.append("retired statement missing")

    ign = req.get("referencePlatform") or {}
    if ign.get("version") != "8.3.10" or ign.get("licenseDecision") != "0001":
        errors.append("the reference platform pin must stay 8.3.10 under ADR 0001")
    if ign.get("trialMinutes") != 120 or ign.get("procedureCapMinutes") != 110:
        errors.append("the reference platform trial must stay 120 min with a 110 min procedure cap")
    if ign.get("resetTrial") != "manual":
        errors.append("a manual license reset stays manual")

    cut = req.get("cutoffs") or {}
    if (cut.get("cpuPercentGreaterThan"), cut.get("memoryPercentGreaterThan"), cut.get("clockDriftPercentAbsGreaterThan")) != (50, 50, 10):
        errors.append("cutoffs must stay CPU > 50, memory > 50, |clock drift| > 10")
    if req.get("regressionFailPercentGreaterThan") != 15:
        errors.append("regression bar must stay > 15%")

    scaling = req.get("scaling") or {}
    if scaling.get("vcpu") != [2, 4, 8, 16] or scaling.get("ramGbPerVcpu") != 2:
        errors.append("scaling series must stay 2/4/8/16 vCPU at 2 GB RAM per vCPU")
    if scaling.get("baselineVcpu") != 2 or scaling.get("efficiencyMin") != 0.7:
        errors.append("scaling efficiency must stay 0.70 against the 2 vCPU baseline")

    hardware = {h["id"]: h for h in req.get("hardware") or []}
    expect_hw = {"S2": (2, 4), "S4": (4, 8), "S8": (8, 16), "S16": (16, 32), "EDGE": (4, 4)}
    if set(hardware) != set(expect_hw):
        errors.append(f"hardware ids {sorted(hardware)} != {sorted(expect_hw)}")
    for hid, (vcpu, ram) in expect_hw.items():
        got = hardware.get(hid) or {}
        if got.get("vcpu") != vcpu or got.get("ramGb") != ram:
            errors.append(f"{hid} must be {vcpu} vCPU / {ram} GB")

    scenarios = {s["id"]: s for s in req.get("scenarios") or []}
    if list(scenarios) != REQUIRED_IDS:
        errors.append(f"scenario ids must be {REQUIRED_IDS}")
    for sid, exp in EXPECT.items():
        got = scenarios.get(sid) or {}
        for key, val in exp.items():
            if got.get(key) != val:
                errors.append(f"{sid}.{key}: json has {got.get(key)!r}, expected {val!r}")

    for sid in REQUIRED_IDS:
        sc = scenarios.get(sid) or {}
        if sid == "PRF-007":
            check_prf007(sc, prf007_text, errors)
        elif sid in ("PRF-009", "PRF-010"):
            check_driver_floor(sid, sc, driver_floor_text, errors)
        elif sid in ("PRF-004", "PRF-005", "PRF-006"):
            check_tag_capacity(sid, sc, capacity_text, errors)
        elif sid == "PRF-011":
            check_prf011(sc, errors)
        elif sid == "PRF-013":
            if sc.get("implemented") is not True or not (ROOT / str(sc.get("test"))).is_file():
                errors.append("PRF-013 must be implemented=true with an existing test file")
            ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
            if "scripts/perf_regression.py" not in ci:
                errors.append("PRF-013 comparator must run in the Load bench job of ci.yml")
        elif sc.get("implemented") is not False or sc.get("test") != "-":
            errors.append(f"{sid} must stay unimplemented (implemented=false, test='-') until a test file exists")
        src = sc.get("docSource") or ""
        if src != "-":
            errors.append(f"{sid} docSource must be '-', not an external manual: {src}")
        for label, text in (("SPEC §11", sec), ("PLAN", plan), ("PLAYBOOK", playbook), ("ADR 0010", adr), ("FEATURES", features)):
            if sid not in text:
                errors.append(f"{sid} missing from {label}")
        row = rows.get(sid)
        if row is None:
            errors.append(f"FEATURES row {sid} missing")
            continue
        if row["area"] != "Performance" or row["prio"] != "P0":
            errors.append(f"{sid} FEATURES area/priority must be Performance / P0")
        if row["phase"] != str(sc.get("phase")):
            errors.append(f"{sid} FEATURES phase {row['phase']} != json {sc.get('phase')}")
        if row["src"] != src:
            errors.append(f"{sid} FEATURES doc source != json docSource")
        if sid in REFERENCE_PENDING:
            if row["status"] != "todo" or row["tests"] in {"", "-"}:
                errors.append(f"{sid} awaits its reference-hardware runs; FEATURES must stay todo with the test linked")
            elif not (ROOT / sc.get("test", "")).is_file():
                errors.append(f"{sid} test file missing: {sc.get('test')}")
        elif sid in ENGINE_ONLY:
            # Review-2 F9: the engine-only check runs in CI, but no driver-plus-simulator run on the reference
            # sizes exists, so the row stays todo (partial) with the engine check linked.
            if row["status"] != "todo" or row["tests"] in {"", "-"}:
                errors.append(f"{sid} is an engine-only check; FEATURES must stay todo with the test linked")
            elif not (ROOT / sc.get("test", "")).is_file():
                errors.append(f"{sid} test file missing: {sc.get('test')}")
        elif sc.get("implemented"):
            test = sc.get("test")
            if row["status"] != "done" or row["tests"] in {"", "-"}:
                errors.append(f"{sid} implemented requires FEATURES status done and a test link")
            elif not (ROOT / test).is_file():
                errors.append(f"{sid} test file missing: {test}")
        else:
            if row["status"] == "done" or row["tests"] != "-":
                errors.append(f"{sid} is not implemented; FEATURES must stay todo with Test(s) '-'")

    if not sec:
        errors.append("SPEC §11 heading missing")
    for phrase in SPEC_PHRASES:
        if phrase not in sec:
            errors.append(f"SPEC §11 missing {phrase!r}")
    for phrase in ("Nick Hepburn", "0.70", "88,500", "a manual license reset", "withdrawn"):
        if phrase not in adr:
            errors.append(f"ADR 0010 missing {phrase!r}")
    if "10k tags" in plan:
        errors.append("PLAN still uses the withdrawn '10k tags' slogan")
    if "10k tags" in playbook:
        errors.append("PLAYBOOK still uses the withdrawn '10k tags' slogan")
    if not re.search(r"\| P2-01 \|.*\| done \|", plan):
        errors.append("P2-01 must be done once the requirements are recorded")
    for i in range(2, 10):
        if i == 3:
            if not re.search(r"\| P2-03 \|.*\| done\b", plan):
                errors.append("P2-03 must be done once the 100_000-tag scan exists")
            continue
        if i == 7:
            if not re.search(r"\| P2-07 \|.*\| done\b", plan):
                errors.append("P2-07 must be done once the driver floors exist")
            continue
        if i == 2:
            # The lead marks the row in-progress while a worker has it. It is not done
            # until the lead ticks FEATURES. `done` stays rejected.
            if re.search(r"\| P2-02 \|.*\| done\b", plan):
                errors.append("P2-02 must not be marked done here; the lead ticks it with the checker")
            elif not re.search(r"\| P2-02 \|.*\| (?:todo|in-progress)\b", plan):
                errors.append("P2-02 must stay todo or in-progress (benchmark not implemented)")
            continue
        if not re.search(rf"\| P2-0{i} \|.*\| (todo|in-progress[^|]*) \|", plan):
            errors.append(f"P2-0{i} must stay todo (benchmark not implemented)")
    for tid in ("P3-01", "P3-02", "P3-03", "P4-01", "P4-02"):
        if not re.search(rf"\| {tid} \|.*\| todo \|", plan):
            errors.append(f"{tid} must stay todo")

    legacy = LEGACY.read_text(encoding="utf-8") if LEGACY.is_file() else ""
    for phrase in ("ADR 0010", "10_000", "not PRF-007"):
        if phrase not in legacy:
            errors.append(f"TagBenchmarkTest must keep the legacy 10_000 scan and point at ADR 0010 ({phrase})")
    readme = SIM_README.read_text(encoding="utf-8") if SIM_README.is_file() else ""
    for phrase in ("Not implemented", "PRF-012", "150,000", "prf-012.json", "python3 -m bench prf012"):
        if phrase not in readme:
            errors.append(f"test-infra/perf/README.md missing {phrase!r}")
    prf012_path = ROOT / "test-infra" / "perf" / "prf-012.json"
    if not prf012_path.is_file():
        errors.append("test-infra/perf/prf-012.json missing")
    else:
        prf012 = json.loads(prf012_path.read_text(encoding="utf-8"))
        floor = int((scenarios.get("PRF-012") or {}).get("minPoints") or 0)
        full_points = prf012.get("fullPoints")
        ci_points = prf012.get("ciPoints")
        if isinstance(full_points, bool) or not isinstance(full_points, int) or full_points < floor:
            errors.append(f"prf-012 fullPoints must be an integer >= {floor}")
        if isinstance(ci_points, bool) or not isinstance(ci_points, int) or not 1 <= ci_points <= 1024:
            errors.append("prf-012 ciPoints must stay a CI-safe count from 1 to 1024")
        elif isinstance(full_points, int) and not isinstance(full_points, bool) and ci_points >= full_points:
            errors.append("prf-012 ciPoints must stay below fullPoints")
        if prf012.get("protocols") != ["modbus", "opcua", "mqtt"]:
            errors.append("prf-012 protocols must stay modbus, opcua, mqtt")
        if prf012.get("loopbackAllowed") is not False:
            errors.append("prf-012 loopbackAllowed must stay false")
        if prf012.get("periodMs") != 1000:
            errors.append("prf-012 periodMs must stay 1000")
        if sorted(prf012.get("changePercents") or []) != [6, 10]:
            errors.append("prf-012 change fractions must stay 6 and 10")
        per_unit = prf012.get("registersPerUnit")
        if isinstance(full_points, int) and not isinstance(full_points, bool):
            if isinstance(per_unit, bool) or not isinstance(per_unit, int) or per_unit < 1 or per_unit * 247 < full_points:
                errors.append("prf-012 Modbus map must be able to hold fullPoints")
    ci = CI.read_text(encoding="utf-8") if CI.is_file() else ""
    if "scripts/check_perf_requirements.py" not in ci:
        errors.append("CI must run scripts/check_perf_requirements.py")
    if "driverFloorTest" not in ci:
        errors.append("CI Load bench job must run :test-infra:driver-floors:driverFloorTest")
    for sid, check in (("PRF-003", check_prf003_sizes), ("PRF-008", check_prf008_sizes)):
        test = LOAD_SOAK_TESTS[sid]
        if not (ROOT / LOAD_SOAK_DIR / test).is_file():
            errors.append(f"{LOAD_SOAK_DIR}/{test} missing")
        task = ":test-infra:load-soak:" + ("fullLoadTest" if sid == "PRF-003" else "historianSoakTest")
        if task not in ci:
            errors.append(f"CI Load bench job must run {task}")
        ci_size = check(scenarios.get(sid) or {}, errors)
        if ci_size is not None:
            check_ci_report(sid, ci_size, errors)
    gradle = CAPACITY_GRADLE.read_text(encoding="utf-8") if CAPACITY_GRADLE.is_file() else ""
    if 'dependsOn("tagCapacityFloorTest")' not in gradle:
        errors.append("gateway server check must run tagCapacityFloorTest")
    if "excludeTestsMatching(\"org.oip.gateway.TagCapacityFloorTest\")" not in gradle:
        errors.append("TagCapacityFloorTest must stay out of the shared server test JVM")

    # The scaling harness has to use ADR 0010's formula. A fixed offered load
    # divided by vCPU is not a result, so the packaged scale profiles ramp.
    sys.path.insert(0, str(ROOT / "bench"))
    from bench.scaling import EFFICIENCY_MIN, FORMULA, is_throughput_ramp
    from bench.profile import load_profile as load_bench_profile

    if FORMULA != (req.get("scaling") or {}).get("formula"):
        errors.append("bench scaling formula drifted from perf-requirements.json")
    if EFFICIENCY_MIN != (req.get("scaling") or {}).get("efficiencyMin"):
        errors.append("bench efficiency minimum drifted from perf-requirements.json")
    for name in ("scale-dev", "scale-full"):
        path = ROOT / "bench" / "profiles" / f"{name}.json"
        if not path.is_file():
            errors.append(f"missing {path}")
            continue
        loaded = load_bench_profile(path)
        if not is_throughput_ramp(loaded):
            errors.append(
                f"{name} must ramp points at 100% changing and 1 s until a cutoff, "
                "not hold one offered load"
            )
        raw = json.loads(path.read_text(encoding="utf-8"))
        if "E(n)" not in str(raw.get("description") or ""):
            errors.append(f"{name} description must state the E(n) formula")

    for e in errors:
        print("ERROR:", e)
    print(f"perf requirements: {'FAIL' if errors else 'PASS'} ({len(REQUIRED_IDS)} scenarios)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
