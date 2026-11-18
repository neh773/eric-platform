"""High-point bench simulator.

One process serves Modbus TCP (every listen port has the full unit-id map),
OPC UA (virtual numeric nodes) and Sparkplug B over an embedded MQTT broker.
Values are not stored per point. See bench/formula.py for the integer formula
both gateways are compared against.

Nothing here is wired to real equipment.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

_HERE = Path(__file__).resolve().parent
for candidate in (_HERE, _HERE.parent / "bench"):
    if (candidate / "formula.py").exists() and str(candidate) not in sys.path:
        sys.path.append(str(candidate))

import broker as mqttbroker
import formula
import modbus
import network
import opcua
import sparkplug


def _mqtt_string(text: str) -> bytes:
    raw = text.encode()
    return len(raw).to_bytes(2, "big") + raw


class SparkplugPublisher(threading.Thread):
    def __init__(self, space: modbus.PointSpace, host: str, port: int) -> None:
        super().__init__(name="sparkplug", daemon=True)
        self.space = space
        self.host = host
        self.port = port
        self.wake = threading.Event()
        self._stopped = threading.Event()
        self.births = 0
        self.bdseq = 0
        self._last_tick: int | None = None

    def stop(self) -> None:
        self._stopped.set()
        self.wake.set()

    def run(self) -> None:
        while not self._stopped.is_set():
            try:
                self._session()
            except OSError:
                self._stopped.wait(0.3)

    def _session(self) -> None:
        sock = socket.create_connection((self.host, self.port), timeout=5)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            death = sparkplug.encode_payload(0, 0, [sparkplug.metric_u64("bdSeq", self.bdseq)])
            topic = f"spBv1.0/{formula.GROUP_ID}/NDEATH/{formula.EDGE_NODE}"
            flags = 0x02 | 0x04 | 0x08 | 0x20
            variable = _mqtt_string("MQTT") + bytes((4, flags, 0, 30))
            payload = _mqtt_string("oip-bench-edge") + _mqtt_string(topic)
            payload += len(death).to_bytes(2, "big") + death
            sock.sendall(mqttbroker._pack(0x10, variable + payload))
            ack = _read_some(sock, 4)
            if len(ack) < 4 or ack[0] != 0x20 or ack[3] != 0:
                raise OSError(f"mqtt connect rejected {ack.hex()}")
            self._birth(sock)
            while not self._stopped.is_set():
                cfg = self.space.config
                self.wake.wait(max(0.05, cfg.period_ms / 1000.0))
                self.wake.clear()
                if self._stopped.is_set():
                    break
                self._data(sock)
        finally:
            try:
                sock.close()
            except OSError:
                pass

    def _publish(self, sock: socket.socket, topic: str, payload: bytes, retain: bool) -> None:
        body = _mqtt_string(topic) + payload
        header = 0x30 | (0x01 if retain else 0)
        sock.sendall(mqttbroker._pack(header, body))
        self.space.sparkplug_messages += 1

    def _birth(self, sock: socket.socket) -> None:
        cfg = self.space.config
        now = self.space.now_ms()
        self._last_tick = formula.tick_at(now, cfg.epoch_ms, cfg.period_ms)
        node = sparkplug.encode_payload(now, 0, [
            sparkplug.metric_u64("bdSeq", self.bdseq),
            sparkplug.metric_bool("Node Control/Rebirth", False),
        ])
        self._publish(sock, f"spBv1.0/{formula.GROUP_ID}/NBIRTH/{formula.EDGE_NODE}", node, True)
        devices = (cfg.points + cfg.metrics_per_device - 1) // cfg.metrics_per_device if cfg.points else 0
        for device in range(devices):
            metrics = []
            start = device * cfg.metrics_per_device
            end = min(cfg.points, start + cfg.metrics_per_device)
            for index in range(start, end):
                located = formula.address(index, cfg)
                metrics.append(sparkplug.metric_int(located.metric, self.space.raw(index, now)))
            payload = sparkplug.encode_payload(now, 0, metrics)
            topic = f"spBv1.0/{formula.GROUP_ID}/DBIRTH/{formula.EDGE_NODE}/D{device}"
            self._publish(sock, topic, payload, True)
        self.births += 1

    def _data(self, sock: socket.socket) -> None:
        cfg = self.space.config
        now = self.space.now_ms()
        tick = formula.tick_at(now, cfg.epoch_ms, cfg.period_ms)
        if tick == self._last_tick:
            return
        self._last_tick = tick
        devices = (cfg.points + cfg.metrics_per_device - 1) // cfg.metrics_per_device if cfg.points else 0
        for device in range(devices):
            metrics = []
            start = device * cfg.metrics_per_device
            end = min(cfg.points, start + cfg.metrics_per_device)
            for index in range(start, end):
                if not formula.changes(index, cfg.percent_changing):
                    continue
                located = formula.address(index, cfg)
                metrics.append(sparkplug.metric_int(located.metric, self.space.raw(index, now)))
            if not metrics:
                continue
            self.seq = getattr(self, "seq", 0)
            payload = sparkplug.encode_payload(now, self.seq, metrics)
            self.seq = (self.seq + 1) & 0xFF
            topic = f"spBv1.0/{formula.GROUP_ID}/DDATA/{formula.EDGE_NODE}/D{device}"
            self._publish(sock, topic, payload, False)


def _read_some(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            break
        buf += chunk
    return bytes(buf)


class Handler(BaseHTTPRequestHandler):
    server: "Http"

    def log_message(self, fmt: str, *args) -> None:
        return

    def _json(self, code: int, body: dict) -> None:
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:  # noqa: N802
        space = self.server.space
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/health":
            cfg = space.config
            self._json(200, {
                "ok": True,
                "points": cfg.points,
                "percentChanging": cfg.percent_changing,
                "periodMs": cfg.period_ms,
                "frozen": cfg.frozen,
                "sparkplugBirths": self.server.publisher.births if self.server.publisher else 0,
                "modbusPorts": self.server.modbus_ports,
                "opcuaPort": self.server.opcua_port,
                "mqttPort": self.server.mqtt_port,
            })
            return
        if parsed.path == "/config":
            cfg = space.config
            self._json(200, cfg.__dict__)
            return
        if parsed.path == "/stats":
            self._json(200, {
                "modbusRegistersRead": space.modbus_reads,
                "opcuaReads": space.opcua_reads,
                "sparkplugMessages": space.sparkplug_messages,
            })
            return
        if parsed.path in ("/expected", "/map"):
            try:
                index = int(query.get("index", ["0"])[0])
                at = query.get("atMs", [None])[0]
                at_ms = space.now_ms() if at is None else int(at)
                located = formula.address(index, space.config)
                raw = formula.raw_at(index, at_ms, space.config)
            except (ValueError, IndexError) as exc:
                self._json(400, {"error": str(exc)})
                return
            self._json(200, {
                "index": located.index,
                "atMs": at_ms,
                "raw": raw,
                "tick": formula.tick_at(at_ms, space.config.epoch_ms, space.config.period_ms),
                "changing": formula.changes(index, space.config.percent_changing),
                "modbus": {"unitId": located.unit_id, "pdu": located.pdu, "hr": located.hr},
                "opcua": {"namespace": located.opc_ns, "identifier": located.opc_i, "nodeId": located.opc_item},
                "sparkplug": {"group": formula.GROUP_ID, "edge": formula.EDGE_NODE,
                              "device": located.sparkplug_device, "metric": located.metric},
                "referenceItem": located.reference_item,
            })
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if urlparse(self.path).path != "/control":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("content-length", "0"))
        try:
            patch = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError as exc:
            self._json(400, {"error": str(exc)})
            return
        allowed = {
            "points": "points",
            "percentChanging": "percent_changing",
            "periodMs": "period_ms",
            "epochMs": "epoch_ms",
            "frozen": "frozen",
            "nowMs": "now_ms",
            "registersPerUnit": "registers_per_unit",
            "metricsPerDevice": "metrics_per_device",
        }
        changes = {}
        for key, attr in allowed.items():
            if key in patch:
                changes[attr] = patch[key]
        try:
            cfg = self.server.space.update(**changes)
        except (TypeError, ValueError) as exc:
            self._json(400, {"error": str(exc)})
            return
        if self.server.publisher:
            self.server.publisher._last_tick = None
            self.server.publisher.wake.set()
        self._json(200, cfg.__dict__)


class Http(ThreadingHTTPServer):
    def __init__(self, bind: tuple[str, int], space: modbus.PointSpace) -> None:
        super().__init__(bind, Handler)
        self.space = space
        self.publisher: SparkplugPublisher | None = None
        self.modbus_ports: list[int] = []
        self.opcua_port = 0
        self.mqtt_port = 0


@dataclass
class Running:
    space: modbus.PointSpace
    modbus: modbus.ModbusServer
    opcua: opcua.OpcUaServer
    broker: mqttbroker.Broker
    http: Http
    publisher: SparkplugPublisher | None
    bind: str

    def stop(self) -> None:
        if self.publisher:
            self.publisher.stop()
            self.publisher.join(timeout=2)
        self.http.shutdown()
        self.http.server_close()
        self.opcua.stop()
        self.modbus.stop()
        self.broker.stop()


def serve(config: formula.BenchConfig, bind: str = "0.0.0.0", *,
          modbus_ports: list[int] | None = None, opcua_port: int = 4840,
          mqtt_port: int = 1883, http_port: int = 8080, sparkplug_enabled: bool = True,
          browse_page: int = 500, require_networked: bool = False) -> Running:
    """Listen on [bind]. PRF-012 sets require_networked so loopback is refused.

    The Sparkplug publisher dials the same address the broker bound. A wildcard
    bind still publishes through 127.0.0.1, which is how the in-process self-test
    reaches a broker that listens on every interface.
    """
    if require_networked:
        bind = network.networked_bind(bind)
    config = config.validated()
    space = modbus.PointSpace(config)
    modbus_server = modbus.ModbusServer(space)
    ports = modbus_server.start(bind, modbus_ports or [5020])
    opc = opcua.OpcUaServer(space, browse_page=browse_page)
    opc_actual = opc.start(bind, opcua_port)
    mq = mqttbroker.Broker()
    mqtt_actual = mq.start(bind, mqtt_port)
    http = Http((bind, http_port), space)
    http.modbus_ports = ports
    http.opcua_port = opc_actual
    http.mqtt_port = mqtt_actual
    thread = threading.Thread(target=http.serve_forever, name="bench-http", daemon=True)
    thread.start()
    publisher = None
    if sparkplug_enabled:
        publisher_host = "127.0.0.1" if bind in ("0.0.0.0", "::") else bind
        publisher = SparkplugPublisher(space, publisher_host, mqtt_actual)
        http.publisher = publisher
        publisher.start()
    return Running(space, modbus_server, opc, mq, http, publisher, bind)


def _wait_births(running: Running, timeout: float = 3.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if running.publisher and running.publisher.births > 0:
            return
        time.sleep(0.02)
    raise TimeoutError("sparkplug birth did not arrive")


def self_test() -> int:
    """Bind ephemeral ports and check Modbus, OPC UA, Sparkplug and HTTP agree."""
    cfg = formula.BenchConfig(
        points=30, percent_changing=100, period_ms=1000, epoch_ms=0,
        frozen=True, now_ms=2000, registers_per_unit=10, metrics_per_device=10,
    )
    running = serve(cfg, bind="127.0.0.1", modbus_ports=[0, 0], opcua_port=0,
                    mqtt_port=0, http_port=0, browse_page=4)
    try:
        _wait_births(running)
        http_port = running.http.server_address[1]
        with socket.create_connection(("127.0.0.1", http_port), timeout=2) as sock:
            sock.sendall(f"GET /expected?index=0 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".encode())
            raw = b""
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                raw += chunk
        body = raw.split(b"\r\n\r\n", 1)[1]
        expected = json.loads(body)
        if expected["raw"] != 2:
            raise SystemExit(f"http raw {expected['raw']} != 2")
        _modbus_check(running.modbus.ports[0], 1, 0, 2)
        _modbus_check(running.modbus.ports[1], 2, 0, formula.raw_at(10, 2000, cfg))
        got = _opcua_read_point(running.opcua.port, cfg)
        if got != 2:
            raise SystemExit(f"opcua raw {got} != 2")
        metric = _sparkplug_metric(running.broker.port, "P0000000")
        if metric != 2:
            raise SystemExit(f"sparkplug raw {metric} != 2")
        print("SELFTEST OK")
        return 0
    finally:
        running.stop()


def _open_client(host: str, port: int, timeout: float, networked: bool) -> socket.socket:
    if networked:
        return network.driver_connect(host, port, timeout)
    return socket.create_connection((host, port), timeout)


def _modbus_check(port: int, unit: int, pdu: int, expected: int, host: str = "127.0.0.1",
                  networked: bool = False) -> None:
    pdu_bytes = bytes((3, (pdu >> 8) & 0xFF, pdu & 0xFF, 0, 1))
    frame = b"\x00\x01\x00\x00" + (1 + len(pdu_bytes)).to_bytes(2, "big") + bytes((unit,)) + pdu_bytes
    with _open_client(host, port, 2, networked) as sock:
        sock.sendall(frame)
        data = _read_some(sock, 11)
    if len(data) < 11 or data[7] != 3:
        raise SystemExit(f"modbus response {data.hex()}")
    value = int.from_bytes(data[9:11], "big")
    if value != expected:
        raise SystemExit(f"modbus unit {unit} value {value} != {expected}")


def _opcua_read_point(port: int, cfg: formula.BenchConfig, *, index: int = 0,
                      host: str = "127.0.0.1", networked: bool = False) -> int:
    """Anonymous session, one read of [index]. Independent of the server's write helper."""
    sock = _open_client(host, port, 3, networked)
    sock.settimeout(3)
    try:
        url = f"opc.tcp://{host}:{port}/oip/bench"
        hello = opcua.W()
        hello.u32(0)
        hello.u32(65536)
        hello.u32(65536)
        hello.u32(0)
        hello.u32(0)
        hello.string(url)
        _send_plain(sock, b"HEL", bytes(hello.b))
        typ, body = _recv(sock)
        if typ != b"ACK":
            raise SystemExit(f"expected ACK, got {typ}")
        _send_service(sock, b"OPN", _open_request())
        _recv(sock)
        _send_service(sock, b"MSG", _create_session_request(url))
        _recv(sock)
        _send_service(sock, b"MSG", _activate_request())
        _recv(sock)
        read = opcua.W()
        read.nodeid(0, opcua.ENC_READ_REQ)
        _write_header(read, 7)
        read.f64(0)
        read.u32(3)  # both timestamps
        read.i32(1)
        read.nodeid(1, formula.OPC_NODE_BASE + index)
        read.u32(13)
        read.string(None)
        read.qname(0, None)
        _send_service(sock, b"MSG", bytes(read.b))
        _typ, payload = _recv(sock)
        r = opcua.R(payload)
        r.nodeid()
        _read_header(r)
        count = r.i32()
        if count != 1:
            raise SystemExit(f"read count {count}")
        mask = r.u8()
        if not mask & 1:
            raise SystemExit(f"datavalue mask {mask}")
        value = r.variant()
        return int(value)
    finally:
        sock.close()


def _write_header(w: opcua.W, handle: int) -> None:
    w.nodeid(0, 0)
    w.i64(opcua.now_ticks())
    w.u32(handle)
    w.u32(0)
    w.string(None)
    w.u32(5000)
    w.ext_null()


def _read_header(r: opcua.R) -> int:
    r.i64()
    handle = r.u32()
    status = r.u32()
    r.u8()
    n = r.i32()
    for _ in range(max(0, n)):
        r.string()
    r.extension()
    if status != 0:
        raise SystemExit(f"opc status {status:#x}")
    return handle


def _open_request() -> bytes:
    w = opcua.W()
    w.nodeid(0, opcua.ENC_OPEN_SECURE_CHANNEL_REQ)
    _write_header(w, 1)
    w.u32(0)
    w.u32(0)
    w.u32(1)
    w.bstr(b"\x00" * 32)
    w.u32(60000)
    return bytes(w.b)


def _create_session_request(url: str) -> bytes:
    w = opcua.W()
    w.nodeid(0, opcua.ENC_CREATE_SESSION_REQ)
    _write_header(w, 2)
    w.string("urn:oip:bench-client")
    w.string("urn:oip:bench-client")
    w.ltext("bench")
    w.u32(1)
    w.string(None)
    w.string(None)
    w.i32(0)
    w.string(None)
    w.string(url)
    w.string("bench")
    w.bstr(b"\x01" * 32)
    w.bstr(None)
    w.f64(60000)
    w.u32(0)
    return bytes(w.b)


def _activate_request() -> bytes:
    w = opcua.W()
    w.nodeid(0, opcua.ENC_ACTIVATE_SESSION_REQ)
    _write_header(w, 3)
    w.string(None)
    w.bstr(None)
    w.i32(0)
    w.i32(0)
    token = opcua.W()
    token.string("anonymous")
    w.ext_body(0, opcua.ENC_ANONYMOUS_TOKEN, bytes(token.b))
    w.string(None)
    w.bstr(None)
    return bytes(w.b)


_req_id = 1


def _send_plain(sock: socket.socket, typ: bytes, body: bytes) -> None:
    packet = bytearray(typ + b"F" + b"\x00\x00\x00\x00" + body)
    import struct
    struct.pack_into("<I", packet, 4, len(packet))
    sock.sendall(packet)


def _send_service(sock: socket.socket, typ: bytes, body: bytes) -> None:
    global _req_id
    _req_id += 1
    packet = bytearray()
    packet += typ + b"F" + b"\x00\x00\x00\x00"
    packet += (1).to_bytes(4, "little")
    if typ == b"OPN":
        packet += opcua.ASYM
    else:
        packet += (1).to_bytes(4, "little")
    packet += (1).to_bytes(4, "little")
    packet += _req_id.to_bytes(4, "little")
    packet += body
    import struct
    struct.pack_into("<I", packet, 4, len(packet))
    sock.sendall(packet)


def _recv(sock: socket.socket) -> tuple[bytes, bytes]:
    import struct
    header = _read_some(sock, 8)
    if len(header) < 8:
        raise SystemExit("short opc header")
    size = struct.unpack_from("<I", header, 4)[0]
    rest = _read_some(sock, size - 8)
    typ = header[0:3]
    if typ in (b"ACK", b"HEL"):
        return typ, rest
    r = opcua.R(rest)
    r.u32()
    if typ == b"OPN":
        r.string()
        r.bstr()
        r.bstr()
    else:
        r.u32()
    r.u32()
    r.u32()
    return typ, rest[r.i :]


def _sparkplug_births(port: int, *, host: str = "127.0.0.1", networked: bool = False,
                      expect_devices: int, timeout: float = 30.0) -> dict[str, dict[str, int]]:
    """Device id to metric name to integer, read from retained DBIRTH publishes."""
    sock = _open_client(host, port, 5, networked)
    try:
        variable = _mqtt_string("MQTT") + bytes((4, 0x02, 0, 30))
        sock.sendall(mqttbroker._pack(0x10, variable + _mqtt_string("prf012")))
        ack = _read_some(sock, 4)
        if len(ack) < 4 or ack[0] != 0x20 or ack[3] != 0:
            raise RuntimeError(f"mqtt connect rejected {ack.hex()}")
        filt = f"spBv1.0/{formula.GROUP_ID}/DBIRTH/{formula.EDGE_NODE}/+"
        body = b"\x00\x01" + _mqtt_string(filt) + bytes((0,))
        sock.sendall(mqttbroker._pack(0x82, body))
        deadline = time.time() + timeout
        buf = b""
        devices: dict[str, dict[str, int]] = {}
        while time.time() < deadline and len(devices) < expect_devices:
            sock.settimeout(0.5)
            try:
                chunk = sock.recv(1 << 20)
            except socket.timeout:
                continue
            if not chunk:
                break
            buf += chunk
            while len(buf) >= 2:
                try:
                    length, header = mqttbroker._decode_remaining(buf, 1)
                except ValueError:
                    break
                total = header + length
                if len(buf) < total:
                    break
                packet = buf[:total]
                buf = buf[total:]
                if packet[0] >> 4 != 3:
                    continue
                payload_body = packet[header:]
                n = int.from_bytes(payload_body[0:2], "big")
                topic = payload_body[2 : 2 + n].decode()
                payload = payload_body[2 + n :]
                if "/DBIRTH/" not in topic:
                    continue
                decoded = sparkplug.decode_payload(payload)
                metrics: dict[str, int] = {}
                for metric in decoded["metrics"]:
                    if "name" in metric and "int_value" in metric:
                        metrics[str(metric["name"])] = int(metric["int_value"])
                devices[topic.rsplit("/", 1)[-1]] = metrics
        if len(devices) < expect_devices:
            raise RuntimeError(f"sparkplug births {len(devices)} < {expect_devices}")
        return devices
    finally:
        sock.close()


def _sparkplug_metric(port: int, name: str) -> int:
    sock = socket.create_connection(("127.0.0.1", port), timeout=3)
    try:
        variable = _mqtt_string("MQTT") + bytes((4, 0x02, 0, 30))
        sock.sendall(mqttbroker._pack(0x10, variable + _mqtt_string("selftest")))
        ack = _read_some(sock, 4)
        if ack[0] != 0x20:
            raise SystemExit(f"sub connect {ack.hex()}")
        filt = f"spBv1.0/{formula.GROUP_ID}/#"
        body = b"\x00\x01" + _mqtt_string(filt) + bytes((0,))
        sock.sendall(mqttbroker._pack(0x82, body))
        deadline = time.time() + 3
        buf = b""
        while time.time() < deadline:
            sock.settimeout(0.5)
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            buf += chunk
            while len(buf) >= 2:
                length, header = mqttbroker._decode_remaining(buf, 1)
                total = header + length
                if len(buf) < total:
                    break
                packet = buf[:total]
                buf = buf[total:]
                if packet[0] >> 4 != 3:
                    continue
                body = packet[header:]
                n = int.from_bytes(body[0:2], "big")
                topic = body[2 : 2 + n].decode()
                payload = body[2 + n :]
                if "/DBIRTH/" not in topic and "/DDATA/" not in topic:
                    continue
                decoded = sparkplug.decode_payload(payload)
                for metric in decoded["metrics"]:
                    if metric.get("name") == name:
                        return int(metric["int_value"])
        raise SystemExit(f"metric {name} not published")
    finally:
        sock.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="OIP high-point bench simulator")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--points", type=int, default=int(_env("POINTS", "1000")))
    parser.add_argument("--percent", type=float, default=float(_env("PERCENT_CHANGING", "10")))
    parser.add_argument("--period-ms", type=int, default=int(_env("PERIOD_MS", "1000")))
    parser.add_argument("--registers-per-unit", type=int, default=int(_env("REGISTERS_PER_UNIT", "10000")))
    parser.add_argument("--metrics-per-device", type=int, default=int(_env("METRICS_PER_DEVICE", "500")))
    parser.add_argument("--modbus-port", type=int, default=int(_env("MODBUS_PORT", "5020")))
    parser.add_argument("--modbus-extra-ports", type=int, default=int(_env("MODBUS_EXTRA_PORTS", "3")))
    parser.add_argument("--opcua-port", type=int, default=int(_env("OPCUA_PORT", "4840")))
    parser.add_argument("--mqtt-port", type=int, default=int(_env("MQTT_PORT", "1883")))
    parser.add_argument("--http-port", type=int, default=int(_env("HTTP_PORT", "8080")))
    parser.add_argument("--no-sparkplug", action="store_true")
    parser.add_argument("--require-networked", action="store_true",
                        help="Bind a non-loopback interface. 127.0.0.1 is refused (PRF-012).")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    ports = [args.modbus_port + i for i in range(args.modbus_extra_ports + 1)]
    cfg = formula.BenchConfig(
        points=args.points,
        percent_changing=args.percent,
        period_ms=args.period_ms,
        registers_per_unit=args.registers_per_unit,
        metrics_per_device=args.metrics_per_device,
    )
    running = serve(
        cfg, bind=args.bind, modbus_ports=ports, opcua_port=args.opcua_port,
        mqtt_port=args.mqtt_port, http_port=args.http_port,
        sparkplug_enabled=not args.no_sparkplug,
        require_networked=args.require_networked,
    )
    print(f"BIND={running.bind}", flush=True)
    print(f"MODBUS_PORTS={','.join(str(p) for p in running.modbus.ports)}", flush=True)
    print(f"OPCUA_PORT={running.opcua.port}", flush=True)
    print(f"MQTT_PORT={running.broker.port}", flush=True)
    print(f"HTTP_PORT={running.http.server_address[1]}", flush=True)
    print("READY", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        running.stop()
    return 0


def _env(name: str, default: str) -> str:
    import os
    return os.environ.get(name, default)


if __name__ == "__main__":
    sys.exit(main())
