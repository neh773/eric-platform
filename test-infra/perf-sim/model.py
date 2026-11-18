"""Point model for the high-point simulator (PRF-012, ADR 0010).

Which points change is fixed by the point index, not by a random draw:
``(index % 100) < percent``. That is the 10% and 6% rule in ADR 0010, so a
1 s scan of 100,000 points moves 10,000 values and a 1 s scan of 150,000
points moves 9,000.

Modbus holding-register numbers are one-based. HR1 is protocol offset 0.

The point count covers the sizing-guide large tier.
"""

from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass

VALUE_MOD = 30_000
OPC_NODE_BASE = 1_000_000
OPC_NAMESPACE_URI = "urn:oip:perf-sim:points"
GROUP_ID = "OIPPERF"
EDGE_NODE = "PerfSim"
PATTERNS = ("scan", "constant", "ramp", "step", "sine", "random")


class LoopbackRefused(ValueError):
    pass


def require_bind(host: str) -> str:
    """Refuse a loopback listen address. 0.0.0.0 is every interface, which is how the container is reached on a bridge."""
    name = host.strip().lower().strip("[]")
    if name in {"127.0.0.1", "localhost", "::1"} or name.startswith("127."):
        raise LoopbackRefused(
            "high-point simulator refuses a loopback bind; PRF-012 requires a non-loopback interface"
        )
    return host


def tick_at(now_ms: int, epoch_ms: int, period_ms: int) -> int:
    if period_ms <= 0:
        return 0
    elapsed = int(now_ms) - int(epoch_ms)
    if elapsed < 0:
        return 0
    return elapsed // int(period_ms)


def changes(index: int, percent: float, pattern: str = "scan") -> bool:
    if pattern == "constant" or percent <= 0:
        return False
    if percent >= 100:
        return True
    return (index % 100) < percent


def _triangle(deg: int) -> int:
    """Integer triangle wave, -1000..1000, one cycle per 360 degrees."""
    q = deg % 360
    if q <= 90:
        tri = q
    elif q <= 180:
        tri = 180 - q
    elif q <= 270:
        tri = -(q - 180)
    else:
        tri = -(360 - q)
    return tri * 1000 // 90


def _mix(index: int, tick: int) -> int:
    n = ((index + 1) * 1_664_525 + (tick + 1) * 1_013_904_223) & 0xFFFFFFFF
    return n % VALUE_MOD


def raw_at(index: int, now_ms: int, cfg: "BenchConfig") -> int:
    """Integer both protocols report for this point at this millisecond."""
    if index < 0 or index >= cfg.points:
        raise IndexError(index)
    base = index % 1000
    if not changes(index, cfg.percent_changing, cfg.pattern):
        return base
    tick = tick_at(now_ms, cfg.epoch_ms, cfg.period_ms)
    pattern = cfg.pattern
    if pattern == "ramp":
        return (index + tick) % VALUE_MOD
    if pattern == "step":
        return 1000 if tick % 2 else 0
    if pattern == "sine":
        return (15_000 + _triangle((tick * 30 + index) % 360)) % VALUE_MOD
    if pattern == "random":
        return _mix(index, tick)
    return (base + tick) % VALUE_MOD


def changing_indexes(cfg: "BenchConfig") -> list[int]:
    points = cfg.points
    percent = cfg.percent_changing
    if cfg.pattern == "constant" or percent <= 0 or points <= 0:
        return []
    if percent >= 100:
        return list(range(points))
    if percent == int(percent):
        width = int(percent)
        out: list[int] = []
        block = 0
        while block < points:
            out.extend(range(block, min(points, block + width)))
            block += 100
        return out
    return [index for index in range(points) if (index % 100) < percent]


@dataclass(frozen=True)
class BenchConfig:
    points: int = 150_000
    percent_changing: float = 10.0
    period_ms: int = 1000
    epoch_ms: int = 0
    frozen: bool = False
    now_ms: int = 0
    registers_per_unit: int = 10_000
    metrics_per_device: int = 500
    pattern: str = "scan"

    def validated(self) -> "BenchConfig":
        if self.registers_per_unit < 1 or self.registers_per_unit > 65_535:
            raise ValueError("registers_per_unit must be 1..65535")
        max_points = 247 * self.registers_per_unit
        if self.points < 0 or self.points > max_points:
            raise ValueError(f"points must be 0..{max_points}")
        if not 0 <= self.percent_changing <= 100:
            raise ValueError("percent_changing must be 0..100")
        if self.period_ms <= 0:
            raise ValueError("period_ms must be > 0")
        if self.metrics_per_device < 1:
            raise ValueError("metrics_per_device must be >= 1")
        if self.pattern not in PATTERNS:
            raise ValueError(f"pattern must be one of {PATTERNS}")
        return self


class PointSpace:
    """Shared clock and config. Readers copy the config reference."""

    def __init__(self, config: BenchConfig) -> None:
        self._cfg = config.validated()
        self._lock = threading.Lock()
        self.modbus_reads = 0
        self.opcua_reads = 0

    @property
    def config(self) -> BenchConfig:
        return self._cfg

    def update(self, **changes: object) -> BenchConfig:
        with self._lock:
            data = dict(self._cfg.__dict__)
            data.update(changes)
            self._cfg = BenchConfig(**data).validated()
            return self._cfg

    def now_ms(self) -> int:
        cfg = self._cfg
        if cfg.frozen:
            return int(cfg.now_ms)
        return int(time.time() * 1000)


class CpuMeter:
    """Process CPU as a percent of one core since this meter was created."""

    def __init__(self) -> None:
        self._clk = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
        self._start_ticks = _proc_ticks()
        self._start = time.monotonic()

    def percent(self) -> float:
        elapsed = time.monotonic() - self._start
        if elapsed <= 0:
            return 0.0
        used = (_proc_ticks() - self._start_ticks) / float(self._clk)
        return 100.0 * used / elapsed


def _proc_ticks() -> int:
    try:
        raw = open("/proc/self/stat", encoding="ascii").read()
    except OSError:
        return 0
    end = raw.rfind(")")
    fields = raw[end + 2 :].split()
    # utime and stime are fields 14 and 15; index 0 of this tail is field 3.
    return int(fields[11]) + int(fields[12])
