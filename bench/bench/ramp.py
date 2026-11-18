"""Step a gateway through point counts until a cutoff, an error, or the wall-clock cap."""

from __future__ import annotations

import time
from dataclasses import dataclass

from bench.formula import BenchConfig, address, changes, expected_values_per_sec
from bench.metrics import (
    choose_cutoff,
    drift_percent,
    gauge_drift_percent,
    history_cutoff,
    implied_tick,
    latency_ms,
    percentile,
    prf001_cutoff,
    ticks_advanced,
)
from bench.profile import Profile, StepPlan
from bench.targets import HostSample, TagReading


class Clock:
    def monotonic(self) -> float:
        return time.monotonic()

    def time(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        if seconds > 0:
            time.sleep(seconds)


@dataclass
class ManualClock:
    """Test clock. sleep() advances both clocks and does not block."""

    t: float = 0.0
    wall0: float = 1_700_000_000.0

    def monotonic(self) -> float:
        return self.t

    def time(self) -> float:
        return self.wall0 + self.t

    def sleep(self, seconds: float) -> None:
        self.t += max(0.0, seconds)


def sample_indexes(points: int, percent: float, count: int) -> list[int]:
    changing = [i for i in range(points) if changes(i, percent)]
    constant = [i for i in range(points) if not changes(i, percent)]

    def take(xs: list[int], n: int) -> list[int]:
        if n <= 0 or not xs:
            return []
        if len(xs) <= n:
            return list(xs)
        return [xs[int(i * len(xs) / n)] for i in range(n)]

    half = max(1, count // 2)
    picked = take(changing, half) + take(constant, max(0, count - half))
    return picked[:count] or take(list(range(points)), min(count, points))


def effective_wall_seconds(profile_cap: float | None, trial_seconds_left: float | None) -> float | None:
    """The run stops before a step that would cross the tighter of the profile cap and the trial."""
    caps = []
    if profile_cap is not None:
        caps.append(float(profile_cap))
    if trial_seconds_left is not None:
        caps.append(max(0.0, float(trial_seconds_left)))
    if not caps:
        return None
    return min(caps)


def run_ramp(
    profile: Profile,
    target,
    clock=None,
    sampler=None,
    sim=None,
    epoch_ms: int | None = None,
    wall_seconds: float | None = None,
    stop_when_sim_saturated: bool = False,
    collect_samples: bool = False,
) -> dict:
    clock = clock or Clock()
    started = clock.monotonic()
    cap = effective_wall_seconds(profile.max_wall_seconds, wall_seconds)
    if epoch_ms is None:
        # Anchor the formula at the run start so delivered ticks stay inside one modulo
        # cycle for a trial-length run (30_000 ticks). Latency is then milliseconds, not years.
        epoch_ms = int(clock.time() * 1000)
    notes: list[str] = []
    if (
        wall_seconds is not None
        and profile.max_wall_seconds is not None
        and wall_seconds < profile.max_wall_seconds
    ):
        notes.append(
            f"Wall-clock cap is the remaining trial ({wall_seconds:.0f}s), "
            f"tighter than the profile cap of {profile.max_wall_seconds:.0f}s."
        )
    steps_out: list[dict] = []
    stopped = "completed"
    error = None
    previous = 0
    try:
        base_cfg = BenchConfig(
            points=max(profile.points),
            percent_changing=profile.percent_changing,
            period_ms=profile.period_ms[0],
            epoch_ms=epoch_ms,
            registers_per_unit=profile.registers_per_unit,
            metrics_per_device=profile.metrics_per_device,
        )
        target.setup(base_cfg)
        for step in profile.steps():
            remaining_budget = None
            if cap is not None:
                elapsed = clock.monotonic() - started
                need = step.warmup_seconds + step.sample_seconds
                if elapsed + need > cap:
                    stopped = "budget"
                    notes.append(
                        f"Stopped before {step.points} points ({step.protocol}) so the run stays inside "
                        f"{cap:.0f}s. Elapsed {elapsed:.0f}s."
                    )
                    break
                remaining_budget = cap - elapsed
            cfg = BenchConfig(
                points=step.points,
                percent_changing=step.percent_changing,
                period_ms=step.period_ms,
                epoch_ms=epoch_ms,
                registers_per_unit=profile.registers_per_unit,
                metrics_per_device=profile.metrics_per_device,
            )
            if sim is not None:
                sim.update(cfg)
            target.apply_step(step.points, step.period_ms, step.protocol, cfg)
            record = _measure(
                step, cfg, target, clock, sampler, profile, previous, collect_samples=collect_samples,
            )
            record["budgetRemainingSeconds"] = remaining_budget
            step_metrics = getattr(target, "step_metrics", None)
            if step_metrics is not None:
                record.update(step_metrics() or {})
            steps_out.append(record)
            previous = step.points
            if record.get("error"):
                stopped = "error"
                error = record["error"]
                break
            if record.get("simSaturated"):
                notes.append(
                    f"At {step.points} points the simulator CPU exceeded 80% of one core. "
                    "A gateway cutoff in this step may be the simulator falling behind."
                )
                if stop_when_sim_saturated:
                    # Do not stamp a gateway cutoff on a step the simulator already failed.
                    stopped = "simulator"
                    break
            if collect_samples:
                # Steady window: mean, any 60 s mean, and any error or data loss.
                # A single 10 s sample above 50% does not fail the window.
                cutoff = prf001_cutoff(
                    record.get("samples") or [],
                    sample_seconds=step.sample_interval_seconds or 10,
                    cpu_limit=min(profile.limits.cpu_percent, 50.0),
                    memory_limit=min(profile.limits.memory_percent, 50.0),
                    drift_limit=min(profile.limits.drift_percent, 10.0),
                )
                record["steadyCutoff"] = cutoff
            else:
                cutoff = choose_cutoff(record, profile.limits)
            # Local history on: any dropped history row is data loss, whatever the other cutoffs say.
            cutoff = history_cutoff(record) or cutoff
            if cutoff:
                stopped = cutoff
                record["cutoff"] = cutoff
                break
    except Exception as exc:
        stopped = "error"
        error = str(exc)
        notes.append(str(exc))
    finally:
        cleanup = getattr(target, "cleanup", None)
        if cleanup:
            try:
                cleanup()
            except Exception as exc:
                notes.append(f"cleanup: {exc}")
    target_notes = list(getattr(target, "notes", []) or [])
    return {
        "kind": "ramp",
        "bench": "oip-load-ramp",
        "version": 2,
        "target": getattr(target, "name", target.__class__.__name__),
        "profile": profile.name,
        "description": profile.description,
        "stoppedBecause": stopped,
        "error": error,
        "elapsedSeconds": round(clock.monotonic() - started, 3),
        "maxWallSeconds": cap,
        "profileMaxWallSeconds": profile.max_wall_seconds,
        "trialSecondsLeft": wall_seconds,
        "plannedSeconds": profile.planned_seconds(),
        "limits": {
            "cpuPercent": profile.limits.cpu_percent,
            "memoryPercent": profile.limits.memory_percent,
            "driftPercent": profile.limits.drift_percent,
            "qualityErrorFraction": profile.limits.quality_error_fraction,
        },
        "formula": {
            "percentChanging": profile.percent_changing,
            "registersPerUnit": profile.registers_per_unit,
            "epochMs": epoch_ms,
            "value": "(index % 1000 + tick) % 30000 when (index % 100) < percent, else index % 1000",
        },
        "steps": steps_out,
        "notes": notes + target_notes,
    }


def _measure(
    step: StepPlan,
    cfg: BenchConfig,
    target,
    clock,
    sampler,
    profile: Profile,
    previous_points: int,
    collect_samples: bool = False,
) -> dict:
    clock.sleep(step.warmup_seconds)
    indexes = sample_indexes(step.points, step.percent_changing, step.sample_tags)
    hosts: list[HostSample] = []
    latencies: list[float] = []
    series: dict[int, list[tuple[int, int]]] = {i: [] for i in indexes}
    reads = 0
    quality_errors = 0
    mismatches = 0
    good_changes = 0
    last_good: dict[int, int] = {}
    window_start = clock.monotonic()
    deadline = window_start + step.sample_seconds
    error = None
    observe_values = step.protocol != "sparkplug"
    samples: list[dict] = []
    while True:
        host = None
        try:
            host = sampler() if sampler else target.sample_host()
            if host is not None:
                hosts.append(host)
            # docker stats blocks for about a second. Stamp the read after that
            # sample, or latency is the stats call and can come out negative.
            now_ms = int(clock.time() * 1000)
            readings = target.read_values(indexes, now_ms) if observe_values else []
        except Exception as exc:
            error = str(exc)
            if collect_samples:
                samples.append({
                    "cpuPercent": None if host is None else host.cpu_percent_of_quota,
                    "memoryPercent": None if host is None else host.memory_percent,
                    "driftPercent": _host_drift(host, step.period_ms),
                    "dataLoss": False,
                    "error": True,
                })
            break
        loss = False
        for reading in readings:
            reads += 1
            _accumulate(reading, cfg, now_ms, latencies, series, last_good)
            if not reading.good:
                quality_errors += 1
                loss = True
            elif reading.value is not None and implied_tick(reading.index, reading.value, cfg.percent_changing) is None:
                mismatches += 1
                loss = True
            elif reading.good and reading.value is not None:
                prev = last_good.get(reading.index)
                if prev is not None and prev != reading.value and changes(reading.index, cfg.percent_changing):
                    good_changes += ticks_advanced(
                        reading.index, prev, reading.value, cfg.percent_changing,
                    )
                last_good[reading.index] = reading.value
        if collect_samples:
            samples.append({
                "cpuPercent": None if host is None else host.cpu_percent_of_quota,
                "memoryPercent": None if host is None else host.memory_percent,
                "driftPercent": _host_drift(host, step.period_ms),
                "dataLoss": loss,
                "error": False,
            })
        now = clock.monotonic()
        if now >= deadline or step.sample_interval_seconds <= 0:
            break
        clock.sleep(min(step.sample_interval_seconds, deadline - now))
    elapsed = max(1e-6, clock.monotonic() - window_start)
    # Scale observed changes on the changing subset up to the whole point count.
    changing_samples = [i for i in indexes if changes(i, cfg.percent_changing)]
    changing_total = sum(1 for i in range(step.points) if changes(i, cfg.percent_changing))
    if changing_samples:
        values_per_sec = (good_changes / len(changing_samples)) * changing_total / elapsed
    else:
        values_per_sec = 0.0
    drift_source = []
    for index in changing_samples:
        if len(series[index]) >= 2:
            drift_source = series[index]
            break
    gauges = [h.drift_gauge_ms for h in hosts if h.drift_gauge_ms is not None]
    gauge_ms = gauges[-1] if gauges else None
    sim_cpu = [h.sim_cpu_percent for h in hosts if h.sim_cpu_percent is not None]
    clocks = [h.clock_drift_percent for h in hosts if h.clock_drift_percent is not None]
    record = {
        "points": step.points,
        "previousPoints": previous_points,
        "protocol": step.protocol,
        "periodMs": step.period_ms,
        "percentChanging": step.percent_changing,
        "sampleIndexes": indexes,
        "reads": reads,
        "qualityErrors": quality_errors,
        "mismatches": mismatches,
        "valuesPerSec": values_per_sec,
        "expectedValuesPerSec": expected_values_per_sec(step.points, step.percent_changing, step.period_ms),
        "latencyMs": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "max": max(latencies) if latencies else None,
        },
        "driftPercent": drift_percent(drift_source, step.period_ms),
        "referenceDriftGaugeMs": gauge_ms,
        "referenceDriftPercent": gauge_drift_percent(gauge_ms, step.period_ms),
        "cpuPercentOfQuota": _span([h.cpu_percent_of_quota for h in hosts]),
        "memoryPercent": _span([h.memory_percent for h in hosts]),
        "rssBytes": _span([h.rss_bytes for h in hosts]),
        "heapBytes": _span([h.heap_bytes for h in hosts]),
        "simCpuPercent": _span(sim_cpu),
        "simSaturated": any(v > 80 for v in sim_cpu),
        "clockDriftPercent": max(clocks, key=abs) if clocks else None,
        "error": error,
        "cutoff": None,
    }
    if collect_samples:
        record["samples"] = samples
    return record


def _host_drift(host, period_ms: int) -> float | None:
    if host is None:
        return None
    if host.clock_drift_percent is not None:
        return host.clock_drift_percent
    return gauge_drift_percent(host.drift_gauge_ms, period_ms)


def _accumulate(reading: TagReading, cfg: BenchConfig, now_ms: int, latencies, series, last_good) -> None:
    del last_good
    if not reading.good or reading.value is None:
        return
    tick = implied_tick(reading.index, reading.value, cfg.percent_changing)
    if tick is None:
        return
    series.setdefault(reading.index, []).append((now_ms, tick))
    latency = latency_ms(reading.index, reading.value, now_ms, cfg.epoch_ms, cfg.period_ms, cfg.percent_changing)
    if latency is not None:
        latencies.append(latency)


def _span(values: list) -> dict:
    nums = [float(v) for v in values if v is not None]
    if not nums:
        return {"avg": None, "max": None}
    return {"avg": sum(nums) / len(nums), "max": max(nums)}


def address_of(index: int, cfg: BenchConfig):
    return address(index, cfg)
