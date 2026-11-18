"""Shared scriptable signal and the control HTTP API.

GET  /health   -> 200 {"status":"ok"} or 503 while a simulator is not ready
GET  /value    -> signal snapshot
POST /control  -> merge a patch, return the snapshot

Patch fields: mode, amplitude, offset, periodMs, epochMs, frozen, nowMs.
Timestamps are Unix epoch milliseconds. When frozen is true, nowMs is the
clock the waveform is evaluated at; otherwise the wall clock is used and
nowMs in the snapshot is that wall-clock reading.
"""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from common.waveform import (
    CODE_TO_MODE,
    MAX_PERIOD_MS,
    MODE_TO_CODE,
    from_register,
    to_register,
    value_at,
)

# Holding-register map. Keep in lockstep with org.oip.testinfra.ModbusSimMap.
REG_MODE = 0
REG_AMPLITUDE = 1
REG_OFFSET = 2
REG_PERIOD = 3
REG_VALUE = 4
REG_FROZEN = 5
REG_NOW_HI = 6
REG_NOW_LO = 7
REG_EPOCH_HI = 8
REG_EPOCH_LO = 9
REGISTER_COUNT = 10

_PATCH_FIELDS = {"mode", "amplitude", "offset", "periodMs", "epochMs", "frozen", "nowMs"}


class RegisterError(Exception):
    def __init__(self, code: int):
        super().__init__(f"modbus exception {code}")
        self.code = code


def _as_float(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number")
    return float(value)


def _as_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def _as_bool(value: Any, name: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{name} must be a boolean")
    return value


class SignalState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.mode = "sine"
        self.amplitude = 100.0
        self.offset = 0.0
        self.period_ms = 10_000
        self.epoch_ms = 0
        self.frozen = False
        self.frozen_now_ms = 0
        self.generation = 0
        self.ready = True

    def set_ready(self, ready: bool) -> None:
        with self._lock:
            self.ready = ready

    def is_ready(self) -> bool:
        with self._lock:
            return self.ready

    def _clock_unlocked(self) -> int:
        if self.frozen:
            return self.frozen_now_ms
        return int(time.time() * 1000)

    def _value_unlocked(self, now_ms: int) -> float:
        return value_at(self.mode, now_ms, self.epoch_ms, self.period_ms, self.amplitude, self.offset)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            now_ms = self._clock_unlocked()
            return {
                "mode": self.mode,
                "amplitude": self.amplitude,
                "offset": self.offset,
                "periodMs": self.period_ms,
                "epochMs": self.epoch_ms,
                "frozen": self.frozen,
                "nowMs": now_ms,
                "value": self._value_unlocked(now_ms),
            }

    def update(self, patch: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(patch, dict):
            raise ValueError("control body must be an object")
        extra = set(patch) - _PATCH_FIELDS
        if extra:
            raise ValueError(f"unknown fields: {sorted(extra)}")
        with self._lock:
            if "mode" in patch:
                mode = patch["mode"]
                if mode not in MODE_TO_CODE:
                    raise ValueError(f"invalid mode: {mode}")
                self.mode = mode
            if "amplitude" in patch:
                self.amplitude = _as_float(patch["amplitude"], "amplitude")
            if "offset" in patch:
                self.offset = _as_float(patch["offset"], "offset")
            if "periodMs" in patch:
                period = _as_int(patch["periodMs"], "periodMs")
                if period < 1 or period > MAX_PERIOD_MS:
                    raise ValueError(f"periodMs must be 1..{MAX_PERIOD_MS}")
                self.period_ms = period
            if "epochMs" in patch:
                self.epoch_ms = _as_int(patch["epochMs"], "epochMs")
            if "nowMs" in patch:
                self.frozen_now_ms = _as_int(patch["nowMs"], "nowMs")
            if "frozen" in patch:
                frozen = _as_bool(patch["frozen"], "frozen")
                if frozen and not self.frozen and "nowMs" not in patch:
                    self.frozen_now_ms = int(time.time() * 1000)
                self.frozen = frozen
            self.generation += 1
            now_ms = self._clock_unlocked()
            return {
                "mode": self.mode,
                "amplitude": self.amplitude,
                "offset": self.offset,
                "periodMs": self.period_ms,
                "epochMs": self.epoch_ms,
                "frozen": self.frozen,
                "nowMs": now_ms,
                "value": self._value_unlocked(now_ms),
            }

    def registers(self) -> list[int]:
        with self._lock:
            now_ms = self._clock_unlocked()
            value = self._value_unlocked(now_ms)
            now_bits = self.frozen_now_ms & 0xFFFFFFFF
            epoch_bits = self.epoch_ms & 0xFFFFFFFF
            return [
                MODE_TO_CODE[self.mode],
                to_register(self.amplitude),
                to_register(self.offset),
                self.period_ms & 0xFFFF,
                to_register(value),
                1 if self.frozen else 0,
                (now_bits >> 16) & 0xFFFF,
                now_bits & 0xFFFF,
                (epoch_bits >> 16) & 0xFFFF,
                epoch_bits & 0xFFFF,
            ]

    def write_registers(self, start: int, values: list[int]) -> None:
        if start < 0 or start + len(values) > REGISTER_COUNT:
            raise RegisterError(2)
        for word in values:
            if word < 0 or word > 0xFFFF:
                raise RegisterError(3)
        with self._lock:
            mode = self.mode
            amplitude = self.amplitude
            offset = self.offset
            period = self.period_ms
            frozen = self.frozen
            now_bits = self.frozen_now_ms & 0xFFFFFFFF
            epoch_bits = self.epoch_ms & 0xFFFFFFFF
            for offset_i, word in enumerate(values):
                addr = start + offset_i
                if addr == REG_MODE:
                    if word not in CODE_TO_MODE:
                        raise RegisterError(3)
                    mode = CODE_TO_MODE[word]
                elif addr == REG_AMPLITUDE:
                    amplitude = from_register(word)
                elif addr == REG_OFFSET:
                    offset = from_register(word)
                elif addr == REG_PERIOD:
                    if word < 1:
                        raise RegisterError(3)
                    period = word
                elif addr == REG_VALUE:
                    continue
                elif addr == REG_FROZEN:
                    if word not in (0, 1):
                        raise RegisterError(3)
                    if word == 1 and not frozen:
                        now_bits = int(time.time() * 1000) & 0xFFFFFFFF
                    frozen = word == 1
                elif addr == REG_NOW_HI:
                    now_bits = ((word & 0xFFFF) << 16) | (now_bits & 0xFFFF)
                elif addr == REG_NOW_LO:
                    now_bits = (now_bits & 0xFFFF0000) | (word & 0xFFFF)
                elif addr == REG_EPOCH_HI:
                    epoch_bits = ((word & 0xFFFF) << 16) | (epoch_bits & 0xFFFF)
                elif addr == REG_EPOCH_LO:
                    epoch_bits = (epoch_bits & 0xFFFF0000) | (word & 0xFFFF)
            self.mode = mode
            self.amplitude = amplitude
            self.offset = offset
            self.period_ms = period
            self.frozen = frozen
            self.frozen_now_ms = now_bits if now_bits < 0x80000000 else now_bits - 0x100000000
            # epoch is a signed timestamp; keep the low 32 bits as an unsigned instant
            # when the stored epoch already fits in uint32 (the test contract).
            if 0 <= self.epoch_ms <= 0xFFFFFFFF:
                self.epoch_ms = epoch_bits
            else:
                self.epoch_ms = (self.epoch_ms & ~0xFFFFFFFF) | epoch_bits
            self.generation += 1


def serve_http(state: SignalState, bind: str, port: int) -> int:
    owner = state

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt: str, *args: Any) -> None:
            return

        def do_GET(self) -> None:  # noqa: N802
            path = self.path.split("?", 1)[0]
            if path == "/health":
                if owner.is_ready():
                    self._json(200, {"status": "ok"})
                else:
                    self._json(503, {"status": "starting"})
            elif path == "/value":
                self._json(200, owner.snapshot())
            else:
                self._json(404, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802
            path = self.path.split("?", 1)[0]
            if path != "/control":
                self._json(404, {"error": "not found"})
                return
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length < 0 or length > 65536:
                self._json(400, {"error": "invalid content length"})
                return
            raw = self.rfile.read(length) if length else b""
            try:
                patch = json.loads(raw.decode() or "{}")
                snap = owner.update(patch)
            except (ValueError, json.JSONDecodeError, RegisterError) as exc:
                self._json(400, {"error": str(exc)})
                return
            self._json(200, snap)

        def _json(self, code: int, body: dict[str, Any]) -> None:
            raw = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(raw)

    httpd = ThreadingHTTPServer((bind, port), Handler)
    actual = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, name="sim-http", daemon=True)
    thread.start()
    return actual
