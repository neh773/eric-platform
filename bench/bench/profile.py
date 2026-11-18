"""Ramp profiles. A profile is a JSON file; the default ones live in bench/profiles."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


class PlanError(Exception):
    pass


@dataclass
class Limits:
    cpu_percent: float = 50.0
    memory_percent: float = 50.0
    drift_percent: float = 10.0
    quality_error_fraction: float = 0.01
    min_reads: int = 4


@dataclass
class StepPlan:
    points: int
    period_ms: int
    percent_changing: float
    protocol: str
    warmup_seconds: float
    sample_seconds: float
    sample_interval_seconds: float
    sample_tags: int


@dataclass
class Profile:
    name: str
    description: str
    points: list[int]
    period_ms: list[int]
    percent_changing: float
    protocols: list[str]
    warmup_seconds: float
    sample_seconds: float
    sample_interval_seconds: float
    sample_tags: int
    limits: Limits
    max_wall_seconds: float | None
    registers_per_unit: int
    metrics_per_device: int
    modbus_extra_ports: int
    tag_batch: int
    require_devices: bool
    reference: dict
    oip: dict
    raw: dict = field(default_factory=dict)

    def steps(self) -> list[StepPlan]:
        out = []
        for protocol in self.protocols:
            for points in self.points:
                for period in self.period_ms:
                    out.append(StepPlan(
                        points=points,
                        period_ms=period,
                        percent_changing=self.percent_changing,
                        protocol=protocol,
                        warmup_seconds=self.warmup_seconds,
                        sample_seconds=self.sample_seconds,
                        sample_interval_seconds=self.sample_interval_seconds,
                        sample_tags=self.sample_tags,
                    ))
        return out

    def planned_seconds(self) -> float:
        return sum(step.warmup_seconds + step.sample_seconds for step in self.steps())


def load_profile(path: Path) -> Profile:
    data = json.loads(path.read_text())
    return profile_from_dict(data, name=data.get("name") or path.stem)


def profile_from_dict(data: dict, name: str | None = None) -> Profile:
    limits = data.get("cutoffs") or {}
    periods = data.get("periodMs", 1000)
    if isinstance(periods, int):
        periods = [periods]
    points = [int(p) for p in data["points"]]
    protocols = list(data.get("protocols") or ["modbus"])
    for protocol in protocols:
        if protocol not in ("modbus", "opcua", "sparkplug"):
            raise PlanError(f"unknown protocol {protocol}")
    if not points or min(points) < 1:
        raise PlanError("points must be positive")
    if any(p <= 0 for p in periods):
        raise PlanError("periodMs must be > 0")
    profile = Profile(
        name=name or str(data.get("name") or "profile"),
        description=str(data.get("description") or ""),
        points=points,
        period_ms=[int(p) for p in periods],
        percent_changing=float(data.get("percentChanging", 10)),
        protocols=protocols,
        warmup_seconds=float(data.get("warmupSeconds", 0)),
        sample_seconds=float(data.get("sampleSeconds", 5)),
        sample_interval_seconds=float(data.get("sampleIntervalSeconds", 1)),
        sample_tags=int(data.get("sampleTags", 16)),
        limits=Limits(
            cpu_percent=float(limits.get("cpuPercent", 50)),
            memory_percent=float(limits.get("memoryPercent", 50)),
            drift_percent=float(limits.get("driftPercent", 10)),
            quality_error_fraction=float(limits.get("qualityErrorFraction", 0.01)),
            min_reads=int(limits.get("minReads", 4)),
        ),
        max_wall_seconds=None if data.get("maxWallSeconds") in (None, 0) else float(data["maxWallSeconds"]),
        registers_per_unit=int(data.get("registersPerUnit", 10000)),
        metrics_per_device=int(data.get("metricsPerDevice", 500)),
        modbus_extra_ports=int(data.get("modbusExtraPorts", 3)),
        tag_batch=int(data.get("tagBatch", 2000)),
        require_devices=bool(data.get("requireDevices", True)),
        reference=dict(data.get("reference") or {}),
        oip=dict(data.get("oip") or {}),
        raw=data,
    )
    _reject_if_plan_exceeds_budget(profile)
    return profile


def _reject_if_plan_exceeds_budget(profile: Profile) -> None:
    if profile.max_wall_seconds is None:
        return
    planned = profile.planned_seconds()
    if planned > profile.max_wall_seconds:
        raise PlanError(
            f"profile {profile.name} plans {planned:.0f}s of sampling but the wall-clock cap is "
            f"{profile.max_wall_seconds:.0f}s. Shrink the steps, or raise maxWallSeconds. "
            "the reference platform trial runs should stay under 6600s so a 2-hour trial still has setup margin."
        )
