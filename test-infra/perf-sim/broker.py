"""Tiny MQTT 3.1.1 broker for the bench Sparkplug publisher.

QoS 0 and 1, retain, wildcard filters (`+` and `#`) and a will message.
No authentication. This is a test double, not a general-purpose broker.
"""

from __future__ import annotations

import socket
import threading
from dataclasses import dataclass, field


def topic_match(filt: str, topic: str) -> bool:
    if filt == "#":
        return True
    if filt.endswith("/#") and "+" not in filt:
        base = filt[:-2]
        return topic == base or topic.startswith(base + "/")
    parts_f = filt.split("/")
    parts_t = topic.split("/")
    fi = ti = 0
    while fi < len(parts_f):
        token = parts_f[fi]
        if token == "#":
            return fi == len(parts_f) - 1
        if ti >= len(parts_t):
            return False
        if token != "+" and token != parts_t[ti]:
            return False
        fi += 1
        ti += 1
    return ti == len(parts_t)


def _encode_remaining(n: int) -> bytes:
    out = bytearray()
    while True:
        digit = n % 128
        n //= 128
        if n:
            out.append(digit | 0x80)
        else:
            out.append(digit)
            return bytes(out)


def _decode_remaining(buf: bytes, index: int) -> tuple[int, int]:
    value = 0
    shift = 0
    for _ in range(4):
        if index >= len(buf):
            raise ValueError("truncated remaining length")
        digit = buf[index]
        index += 1
        value += (digit & 0x7F) << shift
        if not digit & 0x80:
            return value, index
        shift += 7
    raise ValueError("remaining length too long")


def _pack(header: int, payload: bytes) -> bytes:
    return bytes((header,)) + _encode_remaining(len(payload)) + payload


@dataclass
class Client:
    sock: socket.socket
    client_id: str = ""
    subscriptions: list[tuple[str, int]] = field(default_factory=list)
    will: tuple[str, bytes, int, bool] | None = None
    alive: bool = True
    send_lock: threading.Lock = field(default_factory=threading.Lock)


class Broker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._clients: list[Client] = []
        self._retained: dict[str, tuple[bytes, int]] = {}
        self.published = 0
        self._stop = threading.Event()
        self._listener: socket.socket | None = None
        self.port = 0
        # Called after a SUBSCRIBE is recorded and before retained messages are sent,
        # so a Sparkplug rebirth can refresh those retained births first.
        self.on_subscribe = None
        # Client-originated publishes (NCMD rebirth). Not called for in-process publishes.
        self.on_message = None

    def start(self, bind: str, port: int) -> int:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((bind, port))
        listener.listen(64)
        listener.settimeout(0.5)
        self._listener = listener
        self.port = listener.getsockname()[1]
        threading.Thread(target=self._accept, name="mqtt-accept", daemon=True).start()
        return self.port

    def stop(self) -> None:
        self._stop.set()
        if self._listener is not None:
            try:
                self._listener.close()
            except OSError:
                pass
        with self._lock:
            clients = list(self._clients)
        for client in clients:
            self._drop(client, fire_will=False)

    def publish(self, topic: str, payload: bytes, qos: int = 0, retain: bool = False) -> None:
        """In-process publish. The edge node uses this; clients use the socket."""
        self._route(topic, payload, qos, retain, sender=None)

    def publish_many(self, items: list[tuple[str, bytes, bool]]) -> None:
        """Publish a scan's worth of QoS 0 messages under one routing lock.

        Each item is (topic, payload, retain). Live copies are not marked retain;
        a retained copy is stored for a later subscribe. Matching subscribers
        receive one socket write.
        """
        if not items:
            return
        with self._lock:
            self.published += len(items)
            buckets: dict[int, tuple[Client, list[bytes]]] = {}
            clients = [c for c in self._clients if c.alive]
            for topic, payload, retain in items:
                if retain:
                    if payload:
                        self._retained[topic] = (payload, 0)
                    else:
                        self._retained.pop(topic, None)
                if not clients:
                    continue
                raw = topic.encode()
                body = len(raw).to_bytes(2, "big") + raw + payload
                packet = _pack(0x30, body)
                for client in clients:
                    if any(topic_match(filt, topic) for filt, _q in client.subscriptions):
                        bucket = buckets.get(id(client))
                        if bucket is None:
                            bucket = (client, [])
                            buckets[id(client)] = bucket
                        bucket[1].append(packet)
        for client, packets in buckets.values():
            self._send_raw(client, b"".join(packets))

    def _accept(self) -> None:
        assert self._listener is not None
        while not self._stop.is_set():
            try:
                sock, _addr = self._listener.accept()
            except (socket.timeout, OSError):
                continue
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(60)
            client = Client(sock=sock)
            with self._lock:
                self._clients.append(client)
            threading.Thread(target=self._serve, args=(client,), daemon=True).start()

    def _serve(self, client: Client) -> None:
        try:
            buf = b""
            while client.alive and not self._stop.is_set():
                try:
                    chunk = client.sock.recv(8192)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buf += chunk
                while True:
                    if len(buf) < 2:
                        break
                    try:
                        length, header = _decode_remaining(buf, 1)
                    except ValueError:
                        return
                    total = header + length
                    if len(buf) < total:
                        break
                    packet = buf[:total]
                    buf = buf[total:]
                    self._handle(client, packet[0], packet[header:])
        except (ConnectionError, OSError, ValueError):
            pass
        finally:
            self._drop(client, fire_will=True)

    def _drop(self, client: Client, fire_will: bool) -> None:
        with self._lock:
            already = not client.alive and client not in self._clients
            client.alive = False
            if client in self._clients:
                self._clients.remove(client)
            will = client.will
            client.will = None
        if already:
            return
        try:
            client.sock.close()
        except OSError:
            pass
        if fire_will and will is not None:
            topic, payload, qos, retain = will
            self._route(topic, payload, qos, retain, sender=None)

    def _handle(self, client: Client, header: int, body: bytes) -> None:
        kind = header >> 4
        if kind == 1:
            self._connect(client, body)
        elif kind == 3:
            self._publish_packet(client, header, body)
        elif kind == 8:
            self._subscribe(client, body)
        elif kind == 10:
            self._unsubscribe(client, body)
        elif kind == 12:
            client.sock.sendall(b"\xd0\x00")
        elif kind == 14:
            client.will = None
            client.alive = False
            try:
                client.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        elif kind in (4, 5, 6, 7):
            return
        else:
            raise ValueError(f"unsupported mqtt packet {kind}")

    def _connect(self, client: Client, body: bytes) -> None:
        index = 0

        def take_str() -> str:
            nonlocal index
            if index + 2 > len(body):
                raise ValueError("truncated string")
            n = int.from_bytes(body[index : index + 2], "big")
            index += 2
            raw = body[index : index + n]
            index += n
            return raw.decode()

        protocol = take_str()
        if protocol != "MQTT" or index >= len(body) or body[index] != 4:
            client.sock.sendall(b"\x20\x02\x00\x01")
            client.alive = False
            return
        index += 1
        flags = body[index]
        index += 1
        index += 2  # keepalive
        client.client_id = take_str()
        if flags & 0x04:
            will_topic = take_str()
            if index + 2 > len(body):
                raise ValueError("truncated will")
            wn = int.from_bytes(body[index : index + 2], "big")
            index += 2
            will_payload = body[index : index + wn]
            index += wn
            client.will = (will_topic, will_payload, (flags >> 3) & 0x03, bool(flags & 0x20))
        if flags & 0x80:
            take_str()
        if flags & 0x40:
            take_str()
        client.sock.sendall(b"\x20\x02\x00\x00")

    def _publish_packet(self, client: Client, header: int, body: bytes) -> None:
        qos = (header >> 1) & 0x03
        retain = bool(header & 0x01)
        if len(body) < 2:
            return
        n = int.from_bytes(body[0:2], "big")
        topic = body[2 : 2 + n].decode()
        index = 2 + n
        packet_id = 0
        if qos:
            packet_id = int.from_bytes(body[index : index + 2], "big")
            index += 2
        payload = body[index:]
        self._route(topic, payload, qos, retain, sender=client)
        if qos == 1:
            client.sock.sendall(bytes((0x40, 0x02)) + packet_id.to_bytes(2, "big"))

    def _subscribe(self, client: Client, body: bytes) -> None:
        if len(body) < 2:
            return
        packet_id = body[0:2]
        index = 2
        granted = bytearray()
        new_filters: list[str] = []
        while index + 2 <= len(body):
            n = int.from_bytes(body[index : index + 2], "big")
            index += 2
            filt = body[index : index + n].decode()
            index += n
            if index >= len(body):
                break
            qos = body[index]
            index += 1
            granted.append(min(qos, 1))
            new_filters.append(filt)
            with self._lock:
                client.subscriptions.append((filt, min(qos, 1)))
        client.sock.sendall(_pack(0x90, packet_id + bytes(granted)))
        hook = self.on_subscribe
        if hook is not None and new_filters:
            hook(new_filters)
        retained: list[tuple[str, bytes, int]] = []
        with self._lock:
            for filt in new_filters:
                if "+" not in filt and "#" not in filt:
                    found = self._retained.get(filt)
                    if found is not None:
                        retained.append((filt, found[0], found[1]))
                    continue
                for topic, (payload, qos) in self._retained.items():
                    if topic_match(filt, topic):
                        retained.append((topic, payload, qos))
        for topic, payload, qos in retained:
            self._send(client, topic, payload, qos=0, retain=True)

    def _unsubscribe(self, client: Client, body: bytes) -> None:
        if len(body) < 2:
            return
        packet_id = body[0:2]
        index = 2
        filters = []
        while index + 2 <= len(body):
            n = int.from_bytes(body[index : index + 2], "big")
            index += 2
            filters.append(body[index : index + n].decode())
            index += n
        with self._lock:
            client.subscriptions = [s for s in client.subscriptions if s[0] not in filters]
        client.sock.sendall(_pack(0xB0, packet_id))

    def _route(self, topic: str, payload: bytes, qos: int, retain: bool, sender: Client | None) -> None:
        with self._lock:
            self.published += 1
            if retain:
                if payload:
                    self._retained[topic] = (payload, qos)
                else:
                    self._retained.pop(topic, None)
            targets = []
            for client in self._clients:
                if client is sender or not client.alive:
                    continue
                if any(topic_match(filt, topic) for filt, _q in client.subscriptions):
                    targets.append(client)
        for client in targets:
            self._send(client, topic, payload, qos=0, retain=False)
        hook = self.on_message
        if hook is not None and sender is not None:
            hook(topic, payload)

    def _send(self, client: Client, topic: str, payload: bytes, qos: int, retain: bool) -> None:
        raw = topic.encode()
        body = len(raw).to_bytes(2, "big") + raw + payload
        header = 0x30 | (1 if retain else 0)
        self._send_raw(client, _pack(header, body))

    def _send_raw(self, client: Client, packet: bytes) -> None:
        if not packet:
            return
        try:
            with client.send_lock:
                client.sock.sendall(packet)
        except OSError:
            client.alive = False
