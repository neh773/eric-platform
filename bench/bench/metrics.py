"""Pure helpers for cutoff decisions, drift and docker stat lines."""

from __future__ import annotations

from bench.formula import VALUE_MOD, changes, tick_at
from bench.profile import Limits


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * (p / 100.0)
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    frac = rank - low
    return ordered[low] * (1 - frac) + ordered[high] * frac


def parse_bytes(text: str) -> int:
    """Parse a docker memory token such as `123.4MiB` or `2GiB`."""
    raw = text.strip()
    number = []
    unit = []
    for ch in raw:
        if ch.isdigit() or ch == ".":
            number.append(ch)
        else:
            unit.append(ch)
    if not number:
        raise ValueError(raw)
    value = float("".join(number))
    suffix = "".join(unit).strip().lower()
    scale = {
        "b": 1,
        "kib": 1024,
        "kb": 1000,
        "mib": 1024 ** 2,
        "mb": 1000 ** 2,
        "gib": 1024 ** 3,
        "gb": 1000 ** 3,
        "tib": 1024 ** 4,
    }
    if suffix not in scale:
        raise ValueError(raw)
    return int(value * scale[suffix])


def parse_percent(text: str) -> float:
    return float(text.strip().rstrip("%"))


def cpu_percent_of_quota(docker_cpu_percent: float, allocated_cpus: float) -> float:
    """Docker reports 100% for one busy core. Quota percent is that divided by the cgroup CPUs."""
    if allocated_cpus <= 0:
        raise ValueError("allocated cpus must be > 0")
    return docker_cpu_percent / allocated_cpus


def implied_tick(index: int, value: int, percent: float) -> int | None:
    """Recover the formula tick from a delivered integer, or None if it cannot be a formula value."""
    if value < 0 or value >= VALUE_MOD:
        return None
    base = index % 1000
    if not changes(index, percent):
        return 0 if value == base else None
    if (base + ((value - base) % VALUE_MOD)) % VALUE_MOD != value % VALUE_MOD:
        return None
    # raw = (base + tick) % MOD, so tick ≡ value - base (mod MOD). The bench runs
    # are far shorter than MOD ticks, so the representative in 0..MOD-1 is the tick.
    return (value - base) % VALUE_MOD


def ticks_advanced(index: int, previous: int, value: int, percent: float) -> int:
    """Source ticks between two good values of one changing point.

    The host sample (docker stats) plus the sleep is slower than the source period,
    so one read often covers more than one tick. Counting reads that saw a new value
    under-reports values/s. A trial-length run stays inside one modulo cycle of the formula.
    """
    previous_tick = implied_tick(index, previous, percent)
    current_tick = implied_tick(index, value, percent)
    if previous_tick is not None and current_tick is not None and current_tick > previous_tick:
        return current_tick - previous_tick
    return 1


def latency_ms(index: int, value: int, now_ms: int, epoch_ms: int, period_ms: int, percent: float) -> float | None:
    tick = implied_tick(index, value, percent)
    if tick is None or not changes(index, percent):
        return None
    source_ms = epoch_ms + tick * period_ms
    return float(now_ms - source_ms)


def drift_percent(samples: list[tuple[int, int]], period_ms: int) -> float | None:
    """samples are (now_ms, tick) for one changing point. Negative means the gateway is behind."""
    if len(samples) < 2 or period_ms <= 0:
        return None
    dt = samples[-1][0] - samples[0][0]
    if dt <= 0:
        return None
    expected = dt / float(period_ms)
    if expected <= 0:
        return None
    actual = samples[-1][1] - samples[0][1]
    return (actual - expected) / expected * 100.0


def gauge_drift_percent(drift_ms: float | None, period_ms: int) -> float | None:
    """the reference platform's ClockDriftDetector gauge is milliseconds. Express it as a percent of the step period."""
    if drift_ms is None or period_ms <= 0:
        return None
    if abs(drift_ms) > 600_000:
        return None
    return drift_ms / float(period_ms) * 100.0


def choose_cutoff(step: dict, limits: Limits) -> str | None:
    """First cutoff that the step crossed, or None. `error` is decided by the runner before this.

    The comparisons are strict. CPU at 50%, memory at 50%, and clock drift of exactly
    +/-10% stay inside the ADR 0010 ceilings.
    """
    reads = int(step.get("reads") or 0)
    errors = int(step.get("qualityErrors") or 0) + int(step.get("mismatches") or 0)
    if reads >= limits.min_reads and reads > 0 and (errors / reads) > limits.quality_error_fraction:
        return "quality"
    cpu = (step.get("cpuPercentOfQuota") or {}).get("max")
    if cpu is not None and cpu > limits.cpu_percent:
        return "cpu"
    memory = (step.get("memoryPercent") or {}).get("max")
    if memory is not None and memory > limits.memory_percent:
        return "memory"
    drift = step.get("driftPercent")
    if drift is not None and abs(drift) > limits.drift_percent:
        return "drift"
    gauge = step.get("referenceDriftPercent")
    if gauge is not None and abs(gauge) > limits.drift_percent:
        return "drift"
    clock = step.get("clockDriftPercent")
    if isinstance(clock, (int, float)) and not isinstance(clock, bool) and abs(float(clock)) > limits.drift_percent:
        return "drift"
    return None


def history_cutoff(step: dict) -> str | None:
    """``data-loss`` when a step with local history on dropped any history row, else None.

    A dropped row is a sample the gateway's history collector discarded because its queue was full,
    so it is lost, like a missed or duplicate real-time value. Steps without a ``history`` record
    (history off) are not judged here. A history-on step whose counters could not be read at all is
    also ``data-loss``: the run cannot show that nothing was dropped.
    """
    if "history" not in step:
        return None
    history = step.get("history")
    if not isinstance(history, dict):
        return "data-loss"
    for key in ("dropped", "droppedTotal"):
        value = history.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            return "data-loss"
        if value > 0:
            return "data-loss"
    return None


# ADR 0010 steady-window rule. Sample every 10 s. Fail when the mean of the
# window crosses a ceiling, or when any 60 s mean crosses it. Exactly 50% CPU,
# exactly 50% memory, and exactly +/-10% drift do not cross.
STEADY_SAMPLE_SECONDS = 10
SPIKE_WINDOW_SECONDS = 60
CPU_CUTOFF_PERCENT = 50.0
MEMORY_CUTOFF_PERCENT = 50.0
DRIFT_CUTOFF_PERCENT = 10.0


def prf001_cutoff(
    samples: list[dict],
    *,
    sample_seconds: float = STEADY_SAMPLE_SECONDS,
    spike_window_seconds: float = SPIKE_WINDOW_SECONDS,
    cpu_limit: float = CPU_CUTOFF_PERCENT,
    memory_limit: float = MEMORY_CUTOFF_PERCENT,
    drift_limit: float = DRIFT_CUTOFF_PERCENT,
) -> str | None:
    """Cutoff for a steady window, or None when the window stays inside the ceilings.

    ``cpu_limit`` / ``memory_limit`` / ``drift_limit`` may be tighter than the ADR
    ceilings. A looser limit is refused so a profile cannot raise 50% or 10%.
    An error, a missed sample, a duplicated sample, or a quality other than good
    fails the window on its own.
    """
    if sample_seconds <= 0:
        raise ValueError("sample_seconds must be > 0")
    if spike_window_seconds <= 0:
        raise ValueError("spike_window_seconds must be > 0")
    if cpu_limit > CPU_CUTOFF_PERCENT or memory_limit > MEMORY_CUTOFF_PERCENT or drift_limit > DRIFT_CUTOFF_PERCENT:
        raise ValueError(
            "cutoffs looser than CPU 50%, memory 50%, and drift +/-10% are refused"
        )
    for sample in samples:
        fault = _sample_fault(sample)
        if fault:
            return fault
    width = int(round(spike_window_seconds / sample_seconds))
    if width < 1:
        raise ValueError("spike window is shorter than one sample")
    if _crosses_mean(samples, "cpuPercent", cpu_limit, width, absolute=False):
        return "cpu"
    if _crosses_mean(samples, "memoryPercent", memory_limit, width, absolute=False):
        return "memory"
    if _crosses_mean(samples, "driftPercent", drift_limit, width, absolute=True):
        return "drift"
    return None


def _sample_fault(sample: dict) -> str | None:
    """``error`` before data loss. Data loss is a miss, a duplicate, or bad quality."""
    if sample.get("error"):
        return "error"
    if sample.get("dataLoss") or sample.get("missed") or sample.get("duplicate"):
        return "data-loss"
    if sample.get("qualityErrors"):
        return "data-loss"
    quality = sample.get("quality")
    if isinstance(quality, str) and not quality.lower().startswith("good"):
        return "data-loss"
    return None


def _crosses_mean(samples: list[dict], key: str, limit: float, width: int, *, absolute: bool) -> bool:
    values = []
    for sample in samples:
        raw = sample.get(key)
        if isinstance(raw, bool) or not isinstance(raw, (int, float)):
            continue
        values.append(float(raw))
    if not values:
        return False
    mean = sum(values) / len(values)
    if _over(mean, limit, absolute):
        return True
    # A window shorter than 60 s has no 60 s mean. Missing samples are not a pass
    # of a spike that was never observed; the overall mean above still applies.
    if len(values) < width:
        return False
    return any(_over(window, limit, absolute) for window in _window_means(values, width))


def _over(value: float, limit: float, absolute: bool) -> bool:
    observed = abs(value) if absolute else value
    return observed > limit


def _window_means(values: list[float], width: int) -> list[float]:
    if width < 1:
        raise ValueError("width must be >= 1")
    if len(values) < width:
        return []
    total = sum(values[:width])
    means = [total / width]
    for index in range(width, len(values)):
        total += values[index] - values[index - width]
        means.append(total / width)
    return means


def tick_series_ok(now_ms: int, epoch_ms: int, period_ms: int) -> int:
    return tick_at(now_ms, epoch_ms, period_ms)
