"""Deterministic signal used by every simulator.

phase = floorMod(nowMs - epochMs, periodMs) / periodMs, in [0, 1)
constant: offset
ramp:     offset + amplitude * phase
sine:     offset + amplitude * sin(2π * phase)
step:     offset if phase < 0.5 else offset + amplitude

Register scaling (Modbus holding registers) is round-half-up via floor(x + 0.5),
matching Java Math.round, then saturated to int16. The on-wire word is the
uint16 bit pattern of that int16.
"""

from __future__ import annotations

import math

MODES = ("constant", "ramp", "sine", "step")
MODE_TO_CODE = {name: i for i, name in enumerate(MODES)}
CODE_TO_MODE = {i: name for name, i in MODE_TO_CODE.items()}

# One holding register. Matches ModbusSimMap.PERIOD_MS in the Kotlin library.
MAX_PERIOD_MS = 65535


def value_at(mode: str, now_ms: int, epoch_ms: int, period_ms: int, amplitude: float, offset: float) -> float:
    if mode not in MODE_TO_CODE:
        raise ValueError(f"invalid mode: {mode}")
    if period_ms <= 0:
        raise ValueError("periodMs must be > 0")
    elapsed = int(now_ms) - int(epoch_ms)
    mod = elapsed % period_ms
    phase = mod / period_ms
    if mode == "constant":
        return float(offset)
    if mode == "ramp":
        return float(offset) + float(amplitude) * phase
    if mode == "sine":
        return float(offset) + float(amplitude) * math.sin(2.0 * math.pi * phase)
    return float(offset) + (float(amplitude) if phase >= 0.5 else 0.0)


def to_register(engineering: float) -> int:
    """int16 register as a uint16 bit pattern (0..65535)."""
    scaled = math.floor(float(engineering) * 100.0 + 0.5)
    if scaled > 32767:
        scaled = 32767
    elif scaled < -32768:
        scaled = -32768
    return int(scaled) & 0xFFFF


def from_register(word: int) -> float:
    word &= 0xFFFF
    if word & 0x8000:
        word -= 0x10000
    return word / 100.0


if __name__ == "__main__":
    import json
    import sys

    cases = json.load(sys.stdin)
    json.dump([value_at(**case) for case in cases], sys.stdout)
