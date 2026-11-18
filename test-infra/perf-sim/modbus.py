"""Modbus TCP front end. Values are computed from the point model, not stored.

Function 03 and 04 return the same integers. Writes are rejected so a client
cannot pull the three protocols apart. Protocol:
https://modbus.org/docs/Modbus_Application_Protocol_V1_1b3.pdf
"""

from __future__ import annotations

import socket
import threading

from model import PointSpace, raw_at

_MAX_READ = 125


def read_registers(space: PointSpace, unit: int, start: int, count: int) -> bytes | int:
    """Return the register payload, or a Modbus exception code."""
    cfg = space.config
    if count < 1 or count > _MAX_READ:
        return 3
    if unit < 1 or unit > 247 or start < 0 or start + count > cfg.registers_per_unit:
        return 2
    now = space.now_ms()
    base = (unit - 1) * cfg.registers_per_unit
    out = bytearray(count * 2)
    for i in range(count):
        index = base + start + i
        if index >= cfg.points:
            return 2
        value = raw_at(index, now, cfg) & 0xFFFF
        out[2 * i] = (value >> 8) & 0xFF
        out[2 * i + 1] = value & 0xFF
    space.modbus_reads += count
    return bytes(out)


def handle_request(space: PointSpace, unit: int, pdu: bytes) -> bytes:
    if not pdu:
        return bytes((0x83, 1))
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
    return bytes((fc | 0x80, 1))


class ModbusServer:
    def __init__(self, space: PointSpace) -> None:
        self.space = space
        self.port = 0
        self._stop = threading.Event()
        self._listener: socket.socket | None = None

    def start(self, bind: str, port: int) -> int:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((bind, port))
        listener.listen(64)
        listener.settimeout(0.5)
        self._listener = listener
        self.port = listener.getsockname()[1]
        threading.Thread(target=self._accept, name="modbus-accept", daemon=True).start()
        return self.port

    def stop(self) -> None:
        self._stop.set()
        if self._listener is not None:
            try:
                self._listener.close()
            except OSError:
                pass

    def _accept(self) -> None:
        assert self._listener is not None
        while not self._stop.is_set():
            try:
                sock, _addr = self._listener.accept()
            except (socket.timeout, OSError):
                continue
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self._serve, args=(sock,), name="modbus-conn", daemon=True).start()

    def _serve(self, sock: socket.socket) -> None:
        try:
            buf = b""
            while not self._stop.is_set():
                chunk = sock.recv(4096)
                if not chunk:
                    return
                buf += chunk
                while len(buf) >= 7:
                    length = int.from_bytes(buf[4:6], "big")
                    total = 6 + length
                    if length < 2 or total > 260:
                        return
                    if len(buf) < total:
                        break
                    frame = buf[:total]
                    buf = buf[total:]
                    unit = frame[6]
                    pdu = handle_request(self.space, unit, frame[7:])
                    reply_len = 1 + len(pdu)
                    sock.sendall(frame[0:4] + reply_len.to_bytes(2, "big") + bytes((unit,)) + pdu)
        except OSError:
            return
        finally:
            try:
                sock.close()
            except OSError:
                pass
