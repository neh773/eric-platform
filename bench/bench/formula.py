"""Deterministic point values shared by the simulator and the load harness.

Both gateways are compared against this function, not against each other and not
against a stored waveform. The same index and the same millisecond produce the
same integer on Modbus, OPC UA and Sparkplug.

    tick = 0 if now < epoch else (now - epoch) // period_ms
    changing = (index % 100) < percent_changing
    base = index % 1000
    raw = base if not changing else (base + tick) % 30000

Modbus holding registers are the uint16 form of that integer. the reference platform's public
one-based HR address is the protocol offset plus one
(-).
OPC UA NodeIds are numeric in namespace 1: ns=1;i=(1_000_000 + index).
"""

from __future__ import annotations

from dataclasses import dataclass

VALUE_MOD = 30_000
OPC_NODE_BASE = 1_000_000
OPC_NAMESPACE_URI = "urn:oip:bench:points"
GROUP_ID = "OIPBENCH"
EDGE_NODE = "Bench"
REFERENCE_DEVICE = "benchmodbus"
REFERENCE_OPC_SERVER = "the reference platform OPC UA Server"
OPC_CONNECTION = "benchopc"
TAG_MODULE = "tags"
TAG_KIND = "Tag"


@dataclass(frozen=True)
class BenchConfig:
    points: int = 1000
    percent_changing: float = 10.0
    period_ms: int = 1000
    epoch_ms: int = 0
    frozen: bool = False
    now_ms: int = 0
    registers_per_unit: int = 10_000
    metrics_per_device: int = 500

    def validated(self) -> BenchConfig:
        if self.registers_per_unit < 1 or self.registers_per_unit > 65535:
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
        return self


@dataclass(frozen=True)
class Address:
    index: int
    unit_id: int
    pdu: int
    hr: int
    opc_ns: int
    opc_i: int
    device_index: int
    metric: str

    @property
    def reference_item(self) -> str:
        """Unit id plus one-based holding register, without the device name."""
        return f"{self.unit_id}.HR{self.hr}"

    @property
    def opc_item(self) -> str:
        return f"ns={self.opc_ns};i={self.opc_i}"

    @property
    def sparkplug_device(self) -> str:
        return f"D{self.device_index}"


def changes(index: int, percent: float) -> bool:
    if percent <= 0:
        return False
    if percent >= 100:
        return True
    return (index % 100) < percent


def tick_at(now_ms: int, epoch_ms: int, period_ms: int) -> int:
    if period_ms <= 0:
        return 0
    elapsed = int(now_ms) - int(epoch_ms)
    if elapsed < 0:
        return 0
    return elapsed // int(period_ms)


def raw_at(index: int, now_ms: int, cfg: BenchConfig) -> int:
    """Engineering integer both gateways should report for this point."""
    if index < 0 or index >= cfg.points:
        raise IndexError(index)
    base = index % 1000
    if not changes(index, cfg.percent_changing):
        return base
    tick = tick_at(now_ms, cfg.epoch_ms, cfg.period_ms)
    return (base + tick) % VALUE_MOD


def address(index: int, cfg: BenchConfig) -> Address:
    if index < 0 or index >= cfg.points:
        raise IndexError(index)
    unit = 1 + index // cfg.registers_per_unit
    pdu = index % cfg.registers_per_unit
    if unit > 247:
        raise ValueError(f"unit id {unit} exceeds 247")
    device_index = index // cfg.metrics_per_device
    return Address(
        index=index,
        unit_id=unit,
        pdu=pdu,
        hr=pdu + 1,
        opc_ns=1,
        opc_i=OPC_NODE_BASE + index,
        device_index=device_index,
        metric=f"P{index:07d}",
    )


def tag_name(index: int) -> str:
    """DNS-label resource name and the reference platform tag name (starts with a letter)."""
    return f"p{index:07d}"


def expected_values_per_sec(points: int, percent: float, period_ms: int) -> float:
    if period_ms <= 0:
        return 0.0
    changing = points * (min(100.0, max(0.0, percent)) / 100.0)
    return changing * (1000.0 / period_ms)
