"""Throughput scaling across CPU and memory tiers.

Each tier ramps point count at 100% changing and a 1 s period until a cutoff.
Efficiency uses the last load that stayed inside the cutoffs:

    E(n) = (T(n) / T(baseline)) / (n / baseline)

ADR 0010 requires E(n) >= 0.70. Dividing a fixed offered load by vCPU is not
this metric: that ratio falls as 1/n while the gateway is idle.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from bench.dockerbox import heap_mb
from bench.profile import PlanError, Profile


# ADR 0010. The check script compares this text to perf-requirements.json.
FORMULA = "E(n) = (T(n) / T(baseline)) / (n / baseline)"
EFFICIENCY_MIN = 0.70
REFERENCE_BASELINE_VCPU = 2.0
# One sim core passed 80% near 120k points at 100% changing / 1 s. Stay under that.
POINTS_PER_SIM_VCPU = 80_000
CUTOFFS = ("cpu", "memory", "drift", "quality")


@dataclass(frozen=True)
class Size:
    cpus: float
    memory: str


@dataclass(frozen=True)
class SimFleet:
    """How many simulator processes a tier needs so the sim is not the cutoff."""

    instances: int
    cpus_each: float
    memory: str
    points_per_vcpu: int


def resolve_sizes(override: str | None, raw_sizes) -> list[Size]:
    """CLI `--sizes` text, or the profile's `{cpus, memory}` objects."""
    if override:
        return parse_sizes(override)
    raw = raw_sizes or [{"cpus": 2, "memory": "4g"}]
    return [Size(float(item["cpus"]), str(item["memory"])) for item in raw]


def parse_sizes(text: str) -> list[Size]:
    """`2:4g,4:8g` or `2,4` (memory stays empty until the caller fills it)."""
    sizes = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" in part:
            cpu, memory = part.split(":", 1)
            sizes.append(Size(float(cpu), memory))
        else:
            sizes.append(Size(float(part), ""))
    if not sizes:
        raise ValueError("no sizes")
    return sizes


def select_sizes(sizes: list[Size], cpus: float | None, memory: str | None) -> list[Size]:
    """Profile tiers, or one tier when `--cpus` and `--memory` are both set."""
    if (cpus is None) ^ (memory is None):
        raise ValueError("--cpus and --memory are set together to run one tier")
    if cpus is None:
        return list(sizes)
    if cpus <= 0:
        raise ValueError("cpus must be > 0")
    if not str(memory).strip():
        raise ValueError("memory is empty")
    return [Size(float(cpus), str(memory))]


def heap_for_memory(memory: str, fraction: float = 0.5) -> str:
    """JVM max heap for a cgroup limit. Half the tier, so heap grows with RAM.

    The cutoff is 50% of OS memory. A fixed ``-Xmx`` on a larger tier makes the
    process look idle on CPU and short on heap. The returned text is an ``-Xmx``
    suffix (``2g`` or ``384m``).
    """
    if fraction <= 0 or fraction >= 1:
        raise ValueError("heap fraction must be between 0 and 1")
    total = heap_mb(memory)
    heap = int(total * fraction)
    if heap < 1 or heap >= total:
        heap = max(1, total // 2)
    if heap % 1024 == 0:
        return f"{heap // 1024}g"
    return f"{heap}m"


def ramp_points(limit: int, start: int = 5_000) -> list[int]:
    """Doubling point counts from ``start`` through ``limit``."""
    if limit < 1 or start < 1:
        raise ValueError("point counts must be positive")
    if start > limit:
        return [int(limit)]
    points = []
    value = start
    while value < limit:
        points.append(int(value))
        value *= 2
    points.append(int(limit))
    return points


def is_capacity_ramp(profile: Profile) -> bool:
    """1 s scan, 10% changing, rising point counts. ADR 0010 tag-capacity series."""
    points = profile.points
    if profile.percent_changing != 10.0 or profile.period_ms != [1000]:
        return False
    if len(points) < 3:
        return False
    if any(later <= earlier for earlier, later in zip(points, points[1:])):
        return False
    return points[-1] >= points[0] * 4


def prepare_capacity_profile(profile: Profile) -> Profile:
    """The tag-capacity workload. A profile that fails ``is_capacity_ramp`` is refused."""
    if is_capacity_ramp(profile):
        return profile
    raise PlanError(
        f"profile {profile.name!r} is not a tag-capacity ramp. "
        "is_capacity_ramp requires percentChanging 10, periodMs 1000, at least 3 strictly "
        "increasing point counts, and the last point at least 4x the first. "
        f"This profile has points={list(profile.points)}, percentChanging={profile.percent_changing}, "
        f"periodMs={list(profile.period_ms)}."
    )


def is_throughput_ramp(profile: Profile) -> bool:
    """True when the profile already ramps a 100% / 1 s load instead of holding one size."""
    points = profile.points
    if profile.percent_changing != 100.0 or profile.period_ms != [1000]:
        return False
    if len(points) < 3:
        return False
    if any(later <= earlier for earlier, later in zip(points, points[1:])):
        return False
    return points[-1] >= points[0] * 4


def prepare_scale_profile(profile: Profile) -> Profile:
    """The workload ``bench scale`` runs at every tier: 100% changing, 1 s, rising points.

    The profile must already pass ``is_throughput_ramp``. A profile that does not
    is refused. It is not rewritten to the default 5,000-to-160,000 series.
    """
    if is_throughput_ramp(profile):
        return profile
    raise PlanError(
        f"profile {profile.name!r} is not a throughput ramp, so bench scale will not run it. "
        "is_throughput_ramp requires percentChanging 100, periodMs 1000, at least 3 strictly "
        "increasing point counts, and the last point at least 4x the first. "
        f"This profile has points={list(profile.points)}, percentChanging={profile.percent_changing}, "
        f"periodMs={list(profile.period_ms)}. "
        f"Refusing to substitute the default ramp {ramp_points(160_000)}."
    )


def plan_sim_fleet(
    gateway_cpus: float,
    max_points: int,
    *,
    headroom: float = 2.0,
    points_per_vcpu: int = POINTS_PER_SIM_VCPU,
) -> SimFleet:
    """One process per core. Python holds the GIL, so extra CPUs on one process do not count.

    Instances cover the gateway's cutoff burn (50% of its quota, with headroom under
    the 80% sim flag) and the point count (``points_per_vcpu`` each). ``headroom``
    defaults to 2 because a fleet sized at the 80,000-point budget was the cutoff
    on earlier runs: the simulator reached 80% of a core before the gateway did.
    ``points_per_vcpu`` above that budget is refused. It would shrink the fleet.
    """
    if gateway_cpus <= 0:
        raise ValueError("gateway cpus must be > 0")
    if max_points < 1:
        raise ValueError("max points must be positive")
    if headroom < 1:
        raise ValueError("headroom must be >= 1")
    if points_per_vcpu < 1:
        raise ValueError("points_per_vcpu must be positive")
    if points_per_vcpu > POINTS_PER_SIM_VCPU:
        raise ValueError(
            f"points_per_vcpu {points_per_vcpu} is above {POINTS_PER_SIM_VCPU}, "
            "the budget that stayed under the 80% simulator flag"
        )
    # The gateway is allowed 50% of its quota before the cutoff. The sim flags at 80%
    # of its own cores, so it needs more cores than the gateway will be allowed to burn.
    from_gateway = max(1, math.ceil((gateway_cpus * 0.5) / 0.8))
    from_points = max(1, math.ceil(max_points / points_per_vcpu))
    needed = max(from_gateway, from_points, math.ceil(gateway_cpus))
    instances = max(1, math.ceil(needed * headroom))
    # The address space is computed on read. 512 MB is enough for the retained births
    # we turn off, and for the Modbus buffers, at the sizes this ramp reaches.
    return SimFleet(
        instances=instances,
        cpus_each=1.0,
        memory="512m",
        points_per_vcpu=points_per_vcpu,
    )


def sustained_throughput(report: dict) -> dict:
    """Last step that stayed inside the cutoffs and did not saturate the simulator.

    The step that crosses a cutoff is not the sustained rate. A run that finishes
    the series without a cutoff only has a lower bound, so ``cutoffReached`` is false.
    """
    good = None
    cutoff = None
    simulator_limited = False
    for step in report.get("steps") or []:
        if step.get("error"):
            break
        if step.get("simSaturated"):
            simulator_limited = True
            break
        reason = step.get("cutoff")
        if reason in CUTOFFS:
            cutoff = reason
            break
        good = step
    stopped = report.get("stoppedBecause")
    values = None if good is None else good.get("valuesPerSec")
    points = None if good is None else good.get("points")
    return {
        "valuesPerSec": values,
        "points": points,
        "cutoffReached": cutoff is not None and good is not None and not simulator_limited,
        "cutoff": cutoff,
        "simulatorLimited": simulator_limited,
        "stoppedBecause": stopped,
    }


def efficiency_ratio(value: float, baseline_value: float, n: float, baseline_n: float) -> float:
    """``E(n) = (T(n) / T(baseline)) / (n / baseline)``. The same formula for both metrics."""
    if baseline_value == 0 or baseline_n == 0 or n == 0:
        raise ValueError("efficiency requires a non-zero baseline, value, and size")
    return (value / baseline_value) / (n / baseline_n)


def meets_efficiency(ratio: float | None, *, is_baseline: bool, minimum: float = EFFICIENCY_MIN) -> bool | None:
    """The baseline has no bar. A computed ratio below ``minimum`` fails. Exactly ``minimum`` passes."""
    if ratio is None or is_baseline:
        return None
    return ratio + 1e-12 >= minimum


def efficiency(
    reports: list[dict],
    cpus: list[float],
    *,
    minimum: float = EFFICIENCY_MIN,
    metric: str = "valuesPerSec",
) -> list[dict]:
    """``reports[i]`` is the ramp for ``cpus[i]``.

    T is the last good step's value changes per second, or its tag count when
    ``metric`` is ``points``. E is null when that tier, or the baseline, did not
    actually reach a cutoff, or the simulator crossed 80% first.
    """
    if metric not in ("valuesPerSec", "points"):
        raise ValueError("metric must be valuesPerSec or points")
    if len(reports) != len(cpus):
        raise ValueError("one report per cpu count")
    measured = [sustained_throughput(report) for report in reports]
    baseline_index = _baseline_index(cpus)
    baseline_cpus = cpus[baseline_index] if cpus else None
    baseline_t = None
    if measured and measured[baseline_index]["cutoffReached"]:
        baseline_t = measured[baseline_index][metric]
    rows = []
    for _report, cpu, reading in zip(reports, cpus, measured):
        values = reading["valuesPerSec"]
        observed = reading[metric]
        comparable = (
            reading["cutoffReached"]
            and not reading["simulatorLimited"]
            and baseline_t not in (None, 0)
            and baseline_cpus not in (None, 0)
            and cpu > 0
            and observed is not None
        )
        is_baseline = cpu == baseline_cpus and reading is measured[baseline_index]
        if comparable and is_baseline:
            ratio = 1.0
        elif comparable:
            ratio = efficiency_ratio(observed, baseline_t, cpu, baseline_cpus)
        else:
            ratio = None
        rows.append({
            "cpus": cpu,
            "metric": "tag-capacity" if metric == "points" else "value-changes-per-second",
            "stoppedBecause": reading["stoppedBecause"],
            "cutoff": reading["cutoff"],
            "cutoffReached": reading["cutoffReached"],
            "simulatorLimited": reading["simulatorLimited"],
            "points": reading["points"],
            "valuesPerSec": values,
            "efficiency": ratio,
            "efficiencyMin": minimum,
            "meetsMinimum": meets_efficiency(ratio, is_baseline=is_baseline, minimum=minimum),
            "baselineCpus": baseline_cpus,
        })
    return rows


def tag_capacity_efficiency(reports: list[dict], cpus: list[float], *, minimum: float = EFFICIENCY_MIN) -> list[dict]:
    """E(n) for tag capacity. T is the last good point count on a 1 s, 10% changing ramp."""
    return efficiency(reports, cpus, minimum=minimum, metric="points")


def combined_efficiency(throughput_rows: list[dict], capacity_rows: list[dict]) -> list[dict]:
    """One row per tier. The tier fails when either metric is below 0.70."""
    capacity_by_cpu = {row["cpus"]: row for row in capacity_rows}
    combined = []
    for row in throughput_rows:
        capacity = capacity_by_cpu.get(row["cpus"])
        combined.append({
            "cpus": row["cpus"],
            "memory": row.get("memory"),
            "baselineCpus": row.get("baselineCpus"),
            "throughput": row,
            "tagCapacity": capacity,
            "meetsMinimum": _merge_meets(
                row.get("meetsMinimum"),
                None if capacity is None else capacity.get("meetsMinimum"),
            ),
        })
    return combined


def series_exit_code(rows: list[dict]) -> int:
    """0 when every tier above the baseline is at least 0.70.

    1 when a computed E(n) is below 0.70. 2 when a tier above the baseline has
    no efficiency (the run never reached a cutoff, or the simulator saturated).
    The baseline itself is 1 by definition and is not a failure.
    """
    if not rows:
        return 2
    for row in rows:
        if row.get("cpus") == row.get("baselineCpus"):
            continue
        meets = row.get("meetsMinimum")
        if meets is False:
            return 1
        if meets is None:
            return 2
    return 0


def _merge_meets(left, right):
    if left is False or right is False:
        return False
    if left is None or right is None:
        return None
    return True


def synthetic_saturated_report(
    cpus: float,
    *,
    kind: str,
    efficiency_target: float = 1.0,
    baseline_cpus: float = REFERENCE_BASELINE_VCPU,
) -> dict:
    """A ramp that cuts off with T set so E(n) equals ``efficiency_target``.

    The baseline tier stays at efficiency 1. The numbers are not a measurement.
    """
    if kind not in ("throughput", "capacity"):
        raise ValueError("kind must be throughput or capacity")
    if efficiency_target <= 0:
        raise ValueError("efficiency_target must be > 0")
    if baseline_cpus <= 0 or cpus <= 0:
        raise ValueError("cpu counts must be > 0")
    # Baseline T is fixed. Other tiers are T(2) * (n/2) * E, which is the formula inverted.
    factor = 1.0 if cpus == baseline_cpus else efficiency_target
    growth = (cpus / baseline_cpus) * factor
    if kind == "throughput":
        good_values = 1000.0 * growth
        good_points = max(1, int(good_values))
        percent = 100
    else:
        good_points = max(1, int(10_000 * growth))
        good_values = good_points * 0.10
        percent = 10
    return {
        "kind": "ramp",
        "dryRun": True,
        "stoppedBecause": "cpu",
        "steps": [
            {
                "points": good_points,
                "valuesPerSec": good_values,
                "percentChanging": percent,
                "periodMs": 1000,
                "cutoff": None,
                "simSaturated": False,
            },
            {
                "points": good_points * 2,
                "valuesPerSec": good_values * 2,
                "percentChanging": percent,
                "periodMs": 1000,
                "cutoff": "cpu",
                "simSaturated": False,
            },
        ],
    }


def _baseline_index(cpus: list[float]) -> int:
    """ADR 0010 uses the 2 vCPU tier when that size is in the run, otherwise the smallest."""
    if not cpus:
        return 0
    for index, cpu in enumerate(cpus):
        if cpu == REFERENCE_BASELINE_VCPU:
            return index
    return min(range(len(cpus)), key=lambda index: cpus[index])
