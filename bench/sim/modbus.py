"""Modbus TCP front end for the bench point space.

Function 03 (read holding registers) and function 04 (read input registers)
return the same deterministic integers. Function 06 and 16 are rejected:
the map is read-only so a gateway write cannot desynchronise the two sides.
Protocol: https://modbus.org/docs/Modbus_Application_Protocol_V1_1b3.pdf

Every listen port serves the full unit-id map. A gateway that is allowed to
open more than one connection (ours) can shard units across ports. the reference platform's
public driver page says to add only one Modbus device per IP and to select
the unit id in the item path, so the harness does that for the reference platform and uses
the first port only.
"""

from __future__ import annotations

import socket
import threading

from formula import BenchConfig, address, raw_at

_MAX_READ = 125


class PointSpace:
    """Shared clock and config. Readers copy the config reference (GIL-atomic)."""

    def __init__(self, config: BenchConfig) -> None:
        self._cfg = config.validated()
        self._lock = threading.Lock()
        self.modbus_reads = 0
        self.opcua_reads = 0
        self.sparkplug_messages = 0

    @property
    def config(self) -> BenchConfig:
        return self._cfg

    def replace(self, config: BenchConfig) -> None:
        config.validated()
        with self._lock:
            self._cfg = config

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
        import time
        return int(time.time() * 1000)

    def raw(self, index: int, now_ms: int | None = None) -> int:
        cfg = self._cfg
        at = self.now_ms() if now_ms is None else int(now_ms)
        return raw_at(index, at, cfg)


def read_registers(space: PointSpace, unit: int, start: int, count: int, now_ms: int | None = None) -> bytes | int:
    """Return the register payload, or a Modbus exception code (int)."""
    cfg = space.config
    if count < 1 or count > _MAX_READ:
        return 3
    if unit < 1 or unit > 247 or start < 0 or start + count > cfg.registers_per_unit:
        return 2
    at = space.now_ms() if now_ms is None else int(now_ms)
    base = (unit - 1) * cfg.registers_per_unit
    out = bytearray(count * 2)
    for i in range(count):
        index = base + start + i
        if index >= cfg.points:
            return 2
        value = raw_at(index, at, cfg) & 0xFFFF
        out[2 * i] = (value >> 8) & 0xFF
        out[2 * i + 1] = value & 0xFF
    space.modbus_reads += count
    return bytes(out)


def handle_request(space: PointSpace, unit: int, pdu: bytes) -> bytes:
    if not pdu:
        return bytes((0x80, 1))
    fc = pdu[0]
    if fc in (3, 4):
        if len(pdu) != 5:
            return bytes((fc | 0x80, 3))
        start = int.from_bytes(pdu[1:3], "big")
        count = int.from_bytes(pdu[3:5], "big")
        result = read_registers(space, unit, start, count)
        if isinstance(result, int):
            return bytes((fc | 0x80, result))
        return bytes((fc, len(result))) + result
    if fc in (6, 16, 22):
        return bytes((fc | 0x80, 1))
    return bytes((fc | 0x80, 1))


def _frame(request: bytes, unit: int, pdu: bytes) -> bytes:
    length = 1 + len(pdu)
    return request[0:2] + b"\x00\x00" + length.to_bytes(2, "big") + bytes((unit & 0xFF,)) + pdu


class ModbusServer:
    def __init__(self, space: PointSpace) -> None:
        self.space = space
        self.ports: list[int] = []
        self._stop = threading.Event()
        self._listeners: list[socket.socket] = []

    def start(self, bind: str, ports: list[int]) -> list[int]:
        actual: list[int] = []
        for port in ports:
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((bind, port))
            listener.listen(64)
            listener.settimeout(0.5)
            self._listeners.append(listener)
            actual.append(listener.getsockname()[1])
            threading.Thread(target=self._accept, args=(listener,), name=f"modbus-{port}", daemon=True).start()
        self.ports = actual
        return actual

    def stop(self) -> None:
        self._stop.set()
        for listener in self._listeners:
            try:
                listener.close()
            except OSError:
                pass

    def _accept(self, listener: socket.socket) -> None:
        while not self._stop.is_set():
            try:
                conn, _addr = listener.accept()
            except (socket.timeout, OSError):
                continue
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn: socket.socket) -> None:
        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.settimeout(60)
            buf = b""
            while not self._stop.is_set():
                try:
                    chunk = conn.recv(8192)
                except socket.timeout:
                    continue
                if not chunk:
                    return
                buf += chunk
                if len(buf) > 1_000_000:
                    return
                while len(buf) >= 7:
                    length = int.from_bytes(buf[4:6], "big")
                    if length < 2 or length > 260:
                        return
                    total = 6 + length
                    if len(buf) < total:
                        break
                    frame = buf[:total]
                    buf = buf[total:]
                    if frame[2:4] != b"\x00\x00":
                        return
                    unit = frame[6]
                    response = handle_request(self.space, unit, frame[7:])
                    conn.sendall(_frame(frame, unit, response))
        except (ConnectionError, OSError):
            return
        finally:
            try:
                conn.close()
            except OSError:
                pass


def pdu_for_index(index: int, cfg: BenchConfig) -> tuple[int, int]:
    located = address(index, cfg)
    return located.unit_id, located.pdu
