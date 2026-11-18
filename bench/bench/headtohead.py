"""PRF-001 head-to-head against the reference platform 8.3.10.

One size uses one 2 h trial. a manual license reset is a manual pause: this module never
POSTs the trial route. The report for a size records the reference platform's last good load
and our gateway's result at that same load.

The procedure that fits in the trial is 20 min setup, 25 min ramp, 45 min
steady, 20 min collect (110 min). A 60 min soak after the ramp does not fit
in the 2 h trial, so those stay two trials (ADR 0010).
"""

from __future__ import annotations

from dataclasses import dataclass

from bench.formula import raw_at
from bench.metrics import (
    CPU_CUTOFF_PERCENT,
    DRIFT_CUTOFF_PERCENT,
    MEMORY_CUTOFF_PERCENT,
    prf001_cutoff,
)
from bench.profile import Limits, PlanError, Profile, profile_from_dict
from bench.ramp import ManualClock, run_ramp
from bench.scaling import sustained_throughput
from bench.targets import HostSample, TagReading


REFERENCE_VERSION = "8.3.10"
TRIAL_MINUTES = 120
PROCEDURE_CAP_MINUTES = 110
SETUP_MINUTES = 20
RAMP_MINUTES = 25
STEADY_MINUTES = 45
COLLECT_MINUTES = 20
SOAK_MINUTES = 60

# Reference sizes. EDGE is a head-to-head size and is not a scaling-series point.
REFERENCE_SIZES = (
    {"id": "S2", "vcpu": 2, "ramGb": 4, "memory": "4g", "role": "scaling-baseline"},
    {"id": "S4", "vcpu": 4, "ramGb": 8, "memory": "8g", "role": "scaling"},
    {"id": "S8", "vcpu": 8, "ramGb": 16, "memory": "16g", "role": "primary-absolute"},
    {"id": "S16", "vcpu": 16, "ramGb": 32, "memory": "32g", "role": "large-absolute"},
    {"id": "EDGE", "vcpu": 4, "ramGb": 4, "memory": "4g", "role": "edge-absolute"},
)
SCALE_SIZE_IDS = ("S2", "S4", "S8", "S16")

RESET_TRIAL_NOTE = (
    "a manual license reset is a manual step. Reset it in the reference platform gateway UI "
    "(the trial banner), then confirm. This harness does not POST /data/api/v1/trial "
    "and does not automate a manual license reset (ADR 0001, ADR 0010)."
)


def procedure_minutes() -> int:
    return SETUP_MINUTES + RAMP_MINUTES + STEADY_MINUTES + COLLECT_MINUTES


def soak_with_ramp_minutes() -> int:
    """The combination ADR 0010 keeps out of a single trial."""
    return SETUP_MINUTES + RAMP_MINUTES + SOAK_MINUTES + COLLECT_MINUTES


def require_adr_ceilings(profile: Profile) -> None:
    """Refuse a profile that raises the CPU, memory, or drift ceilings."""
    limits = profile.limits
    if limits.cpu_percent > CPU_CUTOFF_PERCENT:
        raise PlanError(
            f"profile {profile.name} cpuPercent {limits.cpu_percent} is above the 50% ceiling"
        )
    if limits.memory_percent > MEMORY_CUTOFF_PERCENT:
        raise PlanError(
            f"profile {profile.name} memoryPercent {limits.memory_percent} is above the 50% ceiling"
        )
    if limits.drift_percent > DRIFT_CUTOFF_PERCENT:
        raise PlanError(
            f"profile {profile.name} driftPercent {limits.drift_percent} is outside the +/-10% ceiling"
        )


def reference_size(size_id: str) -> dict:
    for size in REFERENCE_SIZES:
        if size["id"] == size_id:
            return dict(size)
    raise PlanError(f"unknown size {size_id}. Expected one of {', '.join(s['id'] for s in REFERENCE_SIZES)}")


def select_sizes(text: str | None) -> list[dict]:
    """``S2,S4`` or every reference size. Order follows the reference table."""
    if text is None or not str(text).strip() or str(text).strip().lower() == "all":
        return [dict(size) for size in REFERENCE_SIZES]
    wanted = []
    for part in str(text).split(","):
        part = part.strip()
        if not part:
            continue
        wanted.append(part.upper())
    if not wanted:
        raise PlanError("no sizes")
    unknown = [size_id for size_id in wanted if size_id not in {s["id"] for s in REFERENCE_SIZES}]
    if unknown:
        raise PlanError(f"unknown size {unknown[0]}")
    return [dict(size) for size in REFERENCE_SIZES if size["id"] in wanted]


def plan_trials(sizes: list[dict]) -> list[dict]:
    """Each the reference platform size is its own trial. Sizes are not stacked into one 2 h window."""
    plans = []
    for index, size in enumerate(sizes):
        plans.append({
            "size": size["id"],
            "trial": index + 1,
            "stacked": False,
            "trialMinutes": TRIAL_MINUTES,
            "procedureCapMinutes": PROCEDURE_CAP_MINUTES,
        })
    return plans


def confirm_reset_trial(size_id: str, *, stdin, stdout, dry_run: bool = False, preconfirmed: bool = False) -> dict:
    """Pause for a manual a manual license reset, or record that the operator already confirmed it.

    Dry-run records the pause and does not read stdin. A non-interactive run
    without ``--reset-confirmed`` stops, so CI cannot hang and cannot skip the step.
    """
    record = {
        "size": size_id,
        "automated": False,
        "postedTrialRoute": False,
        "method": "manual",
        "note": RESET_TRIAL_NOTE,
    }
    if dry_run:
        record["paused"] = True
        record["confirmed"] = True
        record["mode"] = "dry-run"
        stdout.write(
            f"dry-run: pause for a manual license reset before the reference platform size {size_id}. {RESET_TRIAL_NOTE}\n"
        )
        return record
    if preconfirmed:
        record["paused"] = False
        record["confirmed"] = True
        record["mode"] = "flag"
        stdout.write(
            f"a manual license reset for {size_id} was confirmed before this run. {RESET_TRIAL_NOTE}\n"
        )
        return record
    if stdin is None or not stdin.isatty():
        raise PlanError(
            f"the reference platform size {size_id} needs its own {TRIAL_MINUTES} min trial. {RESET_TRIAL_NOTE} "
            "Re-run on a terminal so the harness can pause, or pass --reset-confirmed after the manual reset."
        )
    stdout.write(
        f"\n{RESET_TRIAL_NOTE}\n"
        f"Size {size_id} uses one trial window and is not stacked with another size.\n"
        "Press Enter after a manual license reset is done. "
    )
    stdout.flush()
    stdin.readline()
    record["paused"] = True
    record["confirmed"] = True
    record["mode"] = "pause"
    return record


def last_good_load(report: dict) -> dict | None:
    """The last ramp step that stayed inside the cutoffs. The crossing step is not the load."""
    reading = sustained_throughput(report)
    if reading["points"] is None:
        return None
    good = None
    for step in report.get("steps") or []:
        if step.get("error") or step.get("simSaturated"):
            break
        if step.get("cutoff"):
            break
        good = step
    if good is None:
        return None
    loss = _step_loss(good)
    return {
        "points": good.get("points"),
        "periodMs": good.get("periodMs"),
        "percentChanging": good.get("percentChanging"),
        "protocol": good.get("protocol"),
        "valuesPerSec": reading["valuesPerSec"],
        "goodQualityTags": None if loss else good.get("points"),
        "cutoffReached": reading["cutoffReached"],
        "cutoff": reading["cutoff"],
    }


def holds_load(last_good: dict | None, ours: dict | None, *, steady_cutoff: str | None) -> dict:
    """Our gateway holds the load when it stays inside the ceilings at equal or higher throughput and tag count."""
    if not last_good or not ours:
        return {
            "withinCutoffs": False,
            "throughputAtLeast": False,
            "tagCountAtLeast": False,
            "held": False,
        }
    stopped = ours.get("stoppedBecause")
    within = steady_cutoff is None and not ours.get("error") and stopped not in {
        "cpu", "memory", "drift", "quality", "error", "data-loss", "simulator",
    }
    reference_rate = last_good.get("valuesPerSec")
    our_rate = ours.get("valuesPerSec")
    throughput_ok = (
        isinstance(reference_rate, (int, float))
        and isinstance(our_rate, (int, float))
        and our_rate + 1e-9 >= reference_rate
    )
    reference_points = last_good.get("points")
    our_points = ours.get("points")
    tags_ok = (
        isinstance(reference_points, int)
        and isinstance(our_points, int)
        and our_points >= reference_points
    )
    return {
        "withinCutoffs": bool(within),
        "throughputAtLeast": bool(throughput_ok),
        "tagCountAtLeast": bool(tags_ok),
        "held": bool(within and throughput_ok and tags_ok),
    }


def assemble_size_report(
    size: dict,
    reference_report: dict,
    oip_report: dict | None,
    pause: dict,
    *,
    dry_run: bool,
) -> dict:
    """One size: the reference platform's last good load, and our result at that load."""
    load = last_good_load(reference_report)
    ours = _ours_summary(oip_report)
    steady = None if ours is None else ours.get("steadyCutoff")
    comparison = holds_load(load, ours, steady_cutoff=steady)
    return {
        "kind": "head-to-head",
        "scenario": "PRF-001",
        "referenceVersion": REFERENCE_VERSION,
        "dryRun": dry_run,
        "measured": not dry_run,
        "size": {
            "id": size["id"],
            "vcpu": size["vcpu"],
            "ramGb": size["ramGb"],
            "memory": size["memory"],
            "role": size["role"],
        },
        "cutoffs": {
            "cpuPercentGreaterThan": CPU_CUTOFF_PERCENT,
            "memoryPercentGreaterThan": MEMORY_CUTOFF_PERCENT,
            "clockDriftPercentAbsGreaterThan": DRIFT_CUTOFF_PERCENT,
            "errorsOrDataLossFail": True,
            "sampleSeconds": 10,
            "spikeWindowSeconds": 60,
        },
        "procedure": {
            "trialMinutes": TRIAL_MINUTES,
            "procedureCapMinutes": PROCEDURE_CAP_MINUTES,
            "setupMinutes": SETUP_MINUTES,
            "rampMinutes": RAMP_MINUTES,
            "steadyMinutes": STEADY_MINUTES,
            "collectMinutes": COLLECT_MINUTES,
            "oneRunPerTrial": True,
        },
        "resetTrial": pause,
        "lastGoodLoad": load,
        "reference": {
            "stoppedBecause": reference_report.get("stoppedBecause"),
            "error": reference_report.get("error"),
            "steps": reference_report.get("steps") or [],
        },
        "ours": ours,
        "comparison": comparison,
        "notes": _notes(dry_run, load, comparison),
    }


def series_report(size_reports: list[dict], *, dry_run: bool) -> dict:
    return {
        "kind": "head-to-head-series",
        "scenario": "PRF-001",
        "referenceVersion": REFERENCE_VERSION,
        "dryRun": dry_run,
        "measured": not dry_run,
        "oneRunPerTrial": True,
        "resetTrial": "manual",
        "trials": plan_trials([{"id": report["size"]["id"]} for report in size_reports]),
        "sizes": [
            {
                "id": report["size"]["id"],
                "vcpu": report["size"]["vcpu"],
                "ramGb": report["size"]["ramGb"],
                "lastGoodLoad": report.get("lastGoodLoad"),
                "ours": _public_ours(report.get("ours")),
                "held": (report.get("comparison") or {}).get("held"),
                "resetTrial": report.get("resetTrial"),
            }
            for report in size_reports
        ],
        "notes": [
            RESET_TRIAL_NOTE,
            "Each size is one the reference platform trial. The harness pauses for a manual license reset and does not call it.",
        ],
    }


def head_to_head_exit(size_reports: list[dict], *, dry_run: bool) -> int:
    """Dry-run is the CI path and exits 0 after the reports are written.

    A measured run exits 1 when our gateway does not hold the last good load.
    """
    if dry_run:
        return 0
    for report in size_reports:
        if not (report.get("comparison") or {}).get("held"):
            return 1
    return 0


def build_dry_run(sizes: list[dict], *, stdin=None, stdout=None) -> list[dict]:
    """Synthetic ramps through the real cutoff and report code. No docker and no network.

    The loads are marked ``dryRun`` and are not a PRF-001 result.
    """
    import io

    sink = stdout if stdout is not None else io.StringIO()
    source = stdin if stdin is not None else io.StringIO()
    reports = []
    for size in sizes:
        pause = confirm_reset_trial(size["id"], stdin=source, stdout=sink, dry_run=True)
        clock = ManualClock()
        profile = _dry_profile()
        reference = run_ramp(
            profile,
            _ScriptedTarget(cpu_for_points={profile.points[0]: 10.0}, cpu_after=80.0),
            clock=clock,
            epoch_ms=int(clock.time() * 1000),
        )
        load = last_good_load(reference)
        oip = None
        if load and load.get("points"):
            hold_clock = ManualClock()
            oip = run_ramp(
                _hold_profile(profile, int(load["points"])),
                _ScriptedTarget(cpu_for_points={}, cpu_after=20.0),
                clock=hold_clock,
                epoch_ms=int(hold_clock.time() * 1000),
                collect_samples=True,
            )
        reports.append(assemble_size_report(size, reference, oip, pause, dry_run=True))
    return reports


def hold_profile(base: Profile, points: int, *, sample_seconds: float, sample_interval: float) -> Profile:
    """One step at the last good load. The steady window samples every 10 s on a real run."""
    data = dict(base.raw)
    data["name"] = f"{base.name}-hold"
    data["points"] = [int(points)]
    data["sampleSeconds"] = sample_seconds
    data["sampleIntervalSeconds"] = sample_interval
    data["warmupSeconds"] = 0
    data["maxWallSeconds"] = max(sample_seconds + 1.0, 60.0)
    return profile_from_dict(data)


@dataclass
class _ScriptedTarget:
    """In-process gateway. CPU jumps once the point count passes the first scripted step."""

    cpu_for_points: dict
    cpu_after: float
    bad: bool = False
    name: str = "dry-run"
    notes: list | None = None
    cfg: object | None = None
    cpu: float = 10.0

    def __post_init__(self) -> None:
        if self.notes is None:
            self.notes = []

    def setup(self, cfg) -> None:
        self.cfg = cfg

    def apply_step(self, points, period_ms, protocol, cfg) -> None:
        del period_ms, protocol
        self.cfg = cfg
        self.cpu = float(self.cpu_for_points.get(points, self.cpu_after))

    def read_values(self, indexes, now_ms):
        rows = []
        for index in indexes:
            if self.bad:
                rows.append(TagReading(index, None, False, "Bad"))
            else:
                rows.append(TagReading(index, raw_at(index, now_ms, self.cfg), True, "Good"))
        return rows

    def sample_host(self):
        return HostSample(
            cpu_percent_of_quota=self.cpu,
            memory_percent=10.0,
            clock_drift_percent=0.0,
            rss_bytes=1024,
            heap_bytes=512,
        )

    def cleanup(self) -> None:
        return None


def _dry_profile() -> Profile:
    return profile_from_dict({
        "name": "prf001-dry",
        "description": "CI dry-run. Not a measurement.",
        "points": [20, 40],
        "periodMs": 1000,
        "percentChanging": 100,
        "protocols": ["modbus"],
        "warmupSeconds": 0,
        "sampleSeconds": 2,
        "sampleIntervalSeconds": 1,
        "sampleTags": 4,
        "registersPerUnit": 10,
        "maxWallSeconds": 60,
        "cutoffs": {
            "cpuPercent": CPU_CUTOFF_PERCENT,
            "memoryPercent": MEMORY_CUTOFF_PERCENT,
            "driftPercent": DRIFT_CUTOFF_PERCENT,
            "qualityErrorFraction": 0.01,
            "minReads": 1,
        },
    })


def _hold_profile(base: Profile, points: int) -> Profile:
    return hold_profile(base, points, sample_seconds=2, sample_interval=1)


def _step_loss(step: dict) -> bool:
    if step.get("error"):
        return True
    if int(step.get("qualityErrors") or 0) > 0:
        return True
    if int(step.get("mismatches") or 0) > 0:
        return True
    return False


def _ours_summary(report: dict | None) -> dict | None:
    if report is None:
        return None
    step = None
    for candidate in report.get("steps") or []:
        if candidate.get("cutoff") or candidate.get("error"):
            step = candidate
            break
        step = candidate
    if step is None:
        return {
            "stoppedBecause": report.get("stoppedBecause"),
            "error": report.get("error"),
            "points": None,
            "valuesPerSec": None,
            "steadyCutoff": None,
        }
    samples = step.get("samples") or []
    steady = prf001_cutoff(samples) if samples else step.get("steadyCutoff")
    if steady is None and step.get("cutoff"):
        steady = step.get("cutoff")
    return {
        "stoppedBecause": report.get("stoppedBecause"),
        "error": report.get("error"),
        "points": step.get("points"),
        "periodMs": step.get("periodMs"),
        "percentChanging": step.get("percentChanging"),
        "protocol": step.get("protocol"),
        "valuesPerSec": step.get("valuesPerSec"),
        "goodQualityTags": None if _step_loss(step) else step.get("points"),
        "steadyCutoff": steady,
        "cpuPercentOfQuota": step.get("cpuPercentOfQuota"),
        "memoryPercent": step.get("memoryPercent"),
        "clockDriftPercent": step.get("clockDriftPercent"),
    }


def _public_ours(ours: dict | None) -> dict | None:
    if ours is None:
        return None
    return {
        "points": ours.get("points"),
        "valuesPerSec": ours.get("valuesPerSec"),
        "goodQualityTags": ours.get("goodQualityTags"),
        "steadyCutoff": ours.get("steadyCutoff"),
        "stoppedBecause": ours.get("stoppedBecause"),
    }


def _notes(dry_run: bool, load: dict | None, comparison: dict) -> list[str]:
    notes = [RESET_TRIAL_NOTE]
    if dry_run:
        notes.append("Dry-run uses a scripted gateway. The numbers are not a PRF-001 measurement.")
    if load is None:
        notes.append("the reference platform produced no load that stayed inside the cutoffs.")
    elif comparison.get("held"):
        notes.append("Our gateway held the reference platform's last good load inside the same cutoffs.")
    else:
        notes.append("Our gateway did not hold the reference platform's last good load.")
    return notes


def ceilings_from(profile: Profile) -> Limits:
    """Tighter of the profile and the ADR ceilings. Never looser than the ADR."""
    return Limits(
        cpu_percent=min(profile.limits.cpu_percent, CPU_CUTOFF_PERCENT),
        memory_percent=min(profile.limits.memory_percent, MEMORY_CUTOFF_PERCENT),
        drift_percent=min(profile.limits.drift_percent, DRIFT_CUTOFF_PERCENT),
        quality_error_fraction=profile.limits.quality_error_fraction,
        min_reads=profile.limits.min_reads,
    )
