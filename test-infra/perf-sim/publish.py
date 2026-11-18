"""MQTT publisher: plain topics and Sparkplug B for the changing subset.

Sparkplug payloads are the public protobuf encoding (specification 3.0):
https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf

A scan publishes only points selected by the change rule. Birth still exposes
every point, so a client can read the full address space. The scan clock is
the wall-clock grid ``epoch + n * period``, the same grid Modbus and OPC UA
use when they compute a value from ``now_ms``. A free-running monotonic timer
keeps whatever phase the process started in, so MQTT can show tick N-1 for
most of tick N. If building and sending one scan takes longer than the
period, or the next scan starts more than one period later, that gap is
counted as behind.
"""

from __future__ import annotations

import socket
import threading
import time

from broker import Broker, _pack
from model import GROUP_ID, EDGE_NODE, BenchConfig, PointSpace, changing_indexes, raw_at, tick_at

_NBIRTH = f"spBv1.0/{GROUP_ID}/NBIRTH/{EDGE_NODE}"
_NCMD = f"spBv1.0/{GROUP_ID}/NCMD/{EDGE_NODE}"


def _varint(buf: bytearray, value: int) -> None:
    if value < 0:
        raise ValueError("varint must be >= 0")
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            buf.append(byte | 0x80)
        else:
            buf.append(byte)
            return


def _key(buf: bytearray, field: int, wire: int) -> None:
    _varint(buf, (field << 3) | wire)


def _metric_int(name: bytes, alias: int, value: int) -> bytes:
    body = bytearray()
    _key(body, 1, 2)
    _varint(body, len(name))
    body += name
    if alias:
        _key(body, 2, 0)
        _varint(body, alias)
    _key(body, 4, 0)
    body.append(3)
    _key(body, 10, 0)
    _varint(body, value & 0xFFFFFFFF)
    return bytes(body)


def _metric_u64(name: bytes, value: int) -> bytes:
    body = bytearray()
    _key(body, 1, 2)
    _varint(body, len(name))
    body += name
    _key(body, 4, 0)
    _varint(body, 8)
    _key(body, 11, 0)
    _varint(body, value)
    return bytes(body)


def _metric_bool(name: bytes, value: bool) -> bytes:
    body = bytearray()
    _key(body, 1, 2)
    _varint(body, len(name))
    body += name
    _key(body, 4, 0)
    _varint(body, 11)
    _key(body, 14, 0)
    body.append(1 if value else 0)
    return bytes(body)


def _payload(timestamp: int, seq: int, metrics: list[bytes]) -> bytes:
    buf = bytearray()
    if timestamp:
        _key(buf, 1, 0)
        _varint(buf, int(timestamp) & 0xFFFFFFFFFFFFFFFF)
    for metric in metrics:
        _key(buf, 2, 2)
        _varint(buf, len(metric))
        buf += metric
    _key(buf, 3, 0)
    _varint(buf, seq & 0xFF)
    return bytes(buf)


class Publisher:
    def __init__(self, space: PointSpace, broker: Broker, *, hooks: bool = True) -> None:
        self.space = space
        self.broker = broker
        self.lock = threading.Lock()
        self.seq = 0
        self.data_seq = 0
        self.last_tick: int | None = None
        self.scans = 0
        self.behind = 0
        self.last_scan_ms = 0.0
        self.max_scan_ms = 0.0
        self.groups: list[tuple[int, list[int]]] = []
        self._key: tuple | None = None
        self._stopped = threading.Event()
        self._thread: threading.Thread | None = None
        if hooks:
            broker.on_subscribe = self._on_subscribe
            broker.on_message = self._on_message

    def start(self) -> None:
        self.birth()
        self._thread = threading.Thread(target=self._loop, name="perf-scan", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stopped.set()
        if self._thread is not None:
            self._thread.join(2)

    def _ensure(self, cfg: BenchConfig) -> None:
        key = (cfg.points, cfg.percent_changing, cfg.pattern, cfg.metrics_per_device)
        if key == self._key:
            return
        grouped: dict[int, list[int]] = {}
        order: list[int] = []
        for index in changing_indexes(cfg):
            device = index // cfg.metrics_per_device
            bucket = grouped.get(device)
            if bucket is None:
                bucket = []
                grouped[device] = bucket
                order.append(device)
            bucket.append(index)
        self.groups = [(device, grouped[device]) for device in order]
        self._key = key

    def birth(self) -> None:
        with self.lock:
            cfg = self.space.config
            self._ensure(cfg)
            now = self.space.now_ms()
            self.seq = 0
            nbirth = _payload(now, 0, [
                _metric_u64(b"bdSeq", 0),
                _metric_bool(b"Node Control/Rebirth", False),
            ])
            self.broker.publish(_NBIRTH, nbirth, retain=True)
            devices = 0
            if cfg.points:
                devices = (cfg.points + cfg.metrics_per_device - 1) // cfg.metrics_per_device
            for device in range(devices):
                self._dbirth(cfg, now, device)
            self.data_seq = (self.seq + 1) & 0xFF
            self._plain_all(cfg, now)
            self.last_tick = tick_at(now, cfg.epoch_ms, cfg.period_ms)

    def _dbirth(self, cfg: BenchConfig, now: int, device: int) -> None:
        start = device * cfg.metrics_per_device
        end = min(cfg.points, start + cfg.metrics_per_device)
        metrics = [
            _metric_int(f"P{index:07d}".encode(), index + 1, raw_at(index, now, cfg))
            for index in range(start, end)
        ]
        self.seq = (self.seq + 1) & 0xFF
        payload = _payload(now, self.seq, metrics)
        topic = f"spBv1.0/{GROUP_ID}/DBIRTH/{EDGE_NODE}/D{device}"
        self.broker.publish(topic, payload, retain=True)

    def _plain_all(self, cfg: BenchConfig, now: int) -> None:
        chunk: list[tuple[str, bytes, bool]] = []
        for index in range(cfg.points):
            chunk.append((f"oip/perf/p{index:07d}", str(raw_at(index, now, cfg)).encode(), True))
            if len(chunk) >= 2000:
                self.broker.publish_many(chunk)
                chunk = []
        if chunk:
            self.broker.publish_many(chunk)

    def scan_once(self, now_ms: int | None = None, force: bool = False) -> float:
        with self.lock:
            cfg = self.space.config
            now = self.space.now_ms() if now_ms is None else int(now_ms)
            tick = tick_at(now, cfg.epoch_ms, cfg.period_ms)
            previous = self.last_tick
            if not force and tick == previous:
                self.last_scan_ms = 0.0
                return 0.0
            self._ensure(cfg)
            self.last_tick = tick
            started = time.perf_counter()
            self._emit(cfg, now)
            elapsed = (time.perf_counter() - started) * 1000.0
            self.last_scan_ms = elapsed
            if elapsed > self.max_scan_ms:
                self.max_scan_ms = elapsed
            self.scans += 1
            if elapsed > cfg.period_ms:
                self.behind += 1
            # Boundaries that passed with no scan. Birth resets last_tick to
            # the tick it just published, so a rebirth is not counted here.
            if previous is not None and tick > previous + 1:
                self.behind += tick - previous - 1
            return elapsed

    def _emit(self, cfg: BenchConfig, now: int) -> None:
        items: list[tuple[str, bytes, bool]] = []
        seq = self.data_seq
        for device, indexes in self.groups:
            metrics: list[bytes] = []
            plains: list[tuple[str, bytes, bool]] = []
            for index in indexes:
                raw = raw_at(index, now, cfg)
                metrics.append(_metric_int(f"P{index:07d}".encode(), index + 1, raw))
                plains.append((f"oip/perf/p{index:07d}", str(raw).encode(), True))
            items.append((f"spBv1.0/{GROUP_ID}/DDATA/{EDGE_NODE}/D{device}", _payload(now, seq, metrics), False))
            items.extend(plains)
            seq = (seq + 1) & 0xFF
        self.data_seq = seq
        self.broker.publish_many(items)

    def _published_tick(self) -> int | None:
        with self.lock:
            return self.last_tick

    def _loop(self) -> None:
        """Publish tick N just after its wall-clock boundary and hold it until N+1.

        ``Event.wait`` may return early. The tick is read again after every
        wait, so an early wake publishes nothing and a late wake publishes the
        tick that is current then.
        """
        while not self._stopped.is_set():
            cfg = self.space.config
            now = self.space.now_ms()
            tick = tick_at(now, cfg.epoch_ms, cfg.period_ms)
            if self._published_tick() != tick:
                self.scan_once()
                if cfg.frozen:
                    if self._stopped.wait(0.05):
                        return
                continue
            if cfg.frozen:
                if self._stopped.wait(0.05):
                    return
                continue
            period = max(1, int(cfg.period_ms))
            next_ms = int(cfg.epoch_ms) + (tick + 1) * period
            delay = (next_ms - self.space.now_ms()) / 1000.0
            # delay <= 0 means the clock moved past the boundary we just
            # measured. Yield once so a stalled clock cannot spin this thread.
            if delay <= 0:
                if self._stopped.wait(0.001):
                    return
                continue
            if self._stopped.wait(min(delay, 0.05)):
                return

    def _on_subscribe(self, filters: list[str]) -> None:
        if any(item.startswith("spBv1.0/") for item in filters):
            self.birth()

    def _on_message(self, topic: str, payload: bytes) -> None:
        del payload
        if topic == _NCMD:
            self.birth()


def _consume(sock: socket.socket) -> None:
    try:
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                return
    except OSError:
        return


def _subscribe(sock: socket.socket, packet_id: int, filters: list[str]) -> None:
    body = bytearray(packet_id.to_bytes(2, "big"))
    for filt in filters:
        raw = filt.encode()
        body += len(raw).to_bytes(2, "big") + raw + bytes((0,))
    sock.sendall(_pack(0x82, bytes(body)))


def _read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise RuntimeError("pace drain closed")
        buf += chunk
    return buf


def attach_drain(host: str, port: int) -> socket.socket:
    """One subscriber that reads and discards, so a pace scan pays for a socket write.

    The socket is an in-process drain for the pace measurement. It is not a
    driver peer and the acceptance harness does not dial it.
    """
    sock = socket.create_connection((host, port), timeout=5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(5)
    proto = b"\x00\x04MQTT\x04\x02\x00\x3c\x00\x0apace-drain"
    sock.sendall(bytes((0x10, len(proto))) + proto)
    ack = _read_exact(sock, 4)
    if ack[0] != 0x20 or ack[3] != 0:
        raise RuntimeError(f"pace drain connect rejected {ack.hex()}")
    _subscribe(sock, 1, ["oip/perf/#", "spBv1.0/#"])
    head = _read_exact(sock, 2)
    if head[0] != 0x90:
        raise RuntimeError(f"pace drain subscribe rejected {head.hex()}")
    _read_exact(sock, head[1])
    sock.settimeout(None)
    threading.Thread(target=_consume, args=(sock,), name="pace-drain", daemon=True).start()
    return sock
