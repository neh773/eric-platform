"""Modbus TCP simulator with scriptable holding registers.

Implements function codes 03 (read holding registers), 06 (write single
register) and 16 (write multiple registers) from the Modbus Application
Protocol Specification V1.1b3:
https://modbus.org/docs/Modbus_Application_Protocol_V1_1b3.pdf

The register map and waveform are shared with the HTTP control API
(GET /value, POST /control) so a test can freeze the clock and read the
same engineering value over Modbus. Unit id is echoed; use 1.
"""

from __future__ import annotations

import argparse
import socket
import threading

from common.signal import REGISTER_COUNT, RegisterError, SignalState, serve_http

_MAX_READ = 125
_MAX_WRITE = 123


def _exception(fc: int, code: int) -> bytes:
    return bytes((fc | 0x80, code))


def _handle_pdu(state: SignalState, pdu: bytes) -> bytes:
    if not pdu:
        return _exception(0, 1)
    fc = pdu[0]
    if fc == 3:
        if len(pdu) != 5:
            return _exception(fc, 3)
        start = int.from_bytes(pdu[1:3], "big")
        count = int.from_bytes(pdu[3:5], "big")
        if count < 1 or count > _MAX_READ:
            return _exception(fc, 3)
        if start + count > REGISTER_COUNT:
            return _exception(fc, 2)
        regs = state.registers()
        data = b"".join((regs[start + i] & 0xFFFF).to_bytes(2, "big") for i in range(count))
        return bytes((fc, len(data))) + data
    if fc == 6:
        if len(pdu) != 5:
            return _exception(fc, 3)
        addr = int.from_bytes(pdu[1:3], "big")
        value = int.from_bytes(pdu[3:5], "big")
        try:
            state.write_registers(addr, [value])
        except RegisterError as exc:
            return _exception(fc, exc.code)
        return pdu
    if fc == 16:
        if len(pdu) < 6:
            return _exception(fc, 3)
        start = int.from_bytes(pdu[1:3], "big")
        quantity = int.from_bytes(pdu[3:5], "big")
        byte_count = pdu[5]
        if quantity < 1 or quantity > _MAX_WRITE or byte_count != quantity * 2 or len(pdu) != 6 + byte_count:
            return _exception(fc, 3)
        values = [int.from_bytes(pdu[6 + 2 * i : 8 + 2 * i], "big") for i in range(quantity)]
        try:
            state.write_registers(start, values)
        except RegisterError as exc:
            return _exception(fc, exc.code)
        return pdu[:5]
    return _exception(fc, 1)


def _frame_response(request: bytes, pdu: bytes) -> bytes:
    tid = request[0:2]
    unit = request[6:7]
    length = 1 + len(pdu)
    return tid + b"\x00\x00" + length.to_bytes(2, "big") + unit + pdu


def _serve_connection(state: SignalState, conn: socket.socket) -> None:
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(60)
        buf = b""
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                return
            buf += chunk
            if len(buf) > 8192:
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
                response = _frame_response(frame, _handle_pdu(state, frame[7:]))
                conn.sendall(response)
    except (ConnectionError, TimeoutError, OSError):
        return
    finally:
        conn.close()


def serve_modbus(state: SignalState, bind: str, port: int) -> int:
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((bind, port))
    listener.listen(16)
    actual = listener.getsockname()[1]

    def accept_loop() -> None:
        while True:
            try:
                conn, _addr = listener.accept()
            except OSError:
                return
            threading.Thread(target=_serve_connection, args=(state, conn), daemon=True).start()

    threading.Thread(target=accept_loop, name="modbus-accept", daemon=True).start()
    return actual


def main() -> None:
    parser = argparse.ArgumentParser(description="OIP Modbus TCP simulator")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--modbus-port", type=int, default=5020)
    parser.add_argument("--http-port", type=int, default=8080)
    args = parser.parse_args()
    state = SignalState()
    modbus_port = serve_modbus(state, args.bind, args.modbus_port)
    http_port = serve_http(state, args.bind, args.http_port)
    print(f"MODBUS_PORT={modbus_port}", flush=True)
    print(f"HTTP_PORT={http_port}", flush=True)
    print("READY", flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
