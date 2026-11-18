"""Minimal OPC UA TCP server for a virtual numeric address space.

Speaks the binary protocol in OPC UA Part 6 and the session/subscription
services in Part 4 that a tag client needs for SecurityPolicy None and
anonymous identity:

https://reference.opcfoundation.org/Core/Part6/v105/docs/
https://reference.opcfoundation.org/Core/Part4/v105/docs/

Nodes are not materialised. Namespace 1 numeric ids `1_000_000 + index`
are computed from the shared formula when a client reads or subscribes.
Memory grows with monitored items, not with the size of the address space.
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from dataclasses import dataclass, field

from formula import OPC_NAMESPACE_URI, OPC_NODE_BASE, BenchConfig, raw_at, tick_at

POLICY_NONE = "http://opcfoundation.org/UA/SecurityPolicy#None"
TRANSPORT_TCP = "http://opcfoundation.org/UA/profiles/transport/uatcp-uasc-uabinary"

# Binary encoding node ids (namespace 0) from the OPC UA NodeSet.
ENC_SERVICE_FAULT = 397
ENC_GET_ENDPOINTS_REQ = 428
ENC_GET_ENDPOINTS_RES = 431
ENC_OPEN_SECURE_CHANNEL_REQ = 446
ENC_OPEN_SECURE_CHANNEL_RES = 449
ENC_CLOSE_SECURE_CHANNEL_REQ = 452
ENC_CREATE_SESSION_REQ = 461
ENC_CREATE_SESSION_RES = 464
ENC_ACTIVATE_SESSION_REQ = 467
ENC_ACTIVATE_SESSION_RES = 470
ENC_CLOSE_SESSION_REQ = 473
ENC_CLOSE_SESSION_RES = 476
ENC_BROWSE_REQ = 527
ENC_BROWSE_RES = 530
ENC_BROWSE_NEXT_REQ = 533
ENC_BROWSE_NEXT_RES = 536
ENC_READ_REQ = 631
ENC_READ_RES = 634
ENC_CREATE_MONITORED_ITEMS_REQ = 751
ENC_CREATE_MONITORED_ITEMS_RES = 754
ENC_SET_MONITORING_MODE_REQ = 769
ENC_SET_MONITORING_MODE_RES = 772
ENC_DELETE_MONITORED_ITEMS_REQ = 781
ENC_DELETE_MONITORED_ITEMS_RES = 784
ENC_CREATE_SUBSCRIPTION_REQ = 787
ENC_CREATE_SUBSCRIPTION_RES = 790
ENC_MODIFY_SUBSCRIPTION_REQ = 793
ENC_MODIFY_SUBSCRIPTION_RES = 796
ENC_SET_PUBLISHING_MODE_REQ = 799
ENC_SET_PUBLISHING_MODE_RES = 802
ENC_PUBLISH_REQ = 826
ENC_PUBLISH_RES = 829
ENC_REPUBLISH_REQ = 832
ENC_REPUBLISH_RES = 835
ENC_DELETE_SUBSCRIPTIONS_REQ = 847
ENC_DELETE_SUBSCRIPTIONS_RES = 850
ENC_TRANSFER_SUBSCRIPTIONS_REQ = 841
ENC_TRANSFER_SUBSCRIPTIONS_RES = 844
ENC_DATA_CHANGE = 811
ENC_ANONYMOUS_TOKEN = 321

GOOD = 0
BAD_DECODING = 0x80070000
BAD_SERVICE_UNSUPPORTED = 0x800B0000
BAD_SUBSCRIPTION_ID_INVALID = 0x80280000
BAD_NODEID_UNKNOWN = 0x80340000
BAD_ATTRIBUTE_ID_INVALID = 0x80350000
BAD_NOTHING_TO_DO = 0x800F0000
BAD_MESSAGE_NOT_AVAILABLE = 0x807B0000
BAD_SEQUENCE_NUMBER_UNKNOWN = 0x80740000
BAD_SESSION_ID_INVALID = 0x80250000

# Unacknowledged NotificationMessages kept per subscription for Republish
# (Part 4, 5.13.6). Older ones are dropped first, as a real server does.
RETRANSMIT_QUEUE = 64

ATTR_NODEID = 1
ATTR_NODECLASS = 2
ATTR_BROWSENAME = 3
ATTR_DISPLAYNAME = 4
ATTR_VALUE = 13
ATTR_DATATYPE = 14
ATTR_VALUERANK = 15
ATTR_ACCESS = 17
ATTR_USER_ACCESS = 18
ATTR_HISTORIZING = 20

NODECLASS_OBJECT = 1
NODECLASS_VARIABLE = 2

_UNIX_TO_FILETIME_100NS = 116444736000000000


class DecodeError(Exception):
    pass


def ticks_from_unix_ms(ms: int) -> int:
    return int(ms) * 10_000 + _UNIX_TO_FILETIME_100NS


def unix_ms_from_ticks(ticks: int) -> int:
    return (int(ticks) - _UNIX_TO_FILETIME_100NS) // 10_000


def now_ticks() -> int:
    return ticks_from_unix_ms(int(time.time() * 1000))


class W:
    def __init__(self) -> None:
        self.b = bytearray()

    def u8(self, v: int) -> None:
        self.b.append(v & 0xFF)

    def u16(self, v: int) -> None:
        self.b += struct.pack("<H", v & 0xFFFF)

    def u32(self, v: int) -> None:
        self.b += struct.pack("<I", v & 0xFFFFFFFF)

    def i32(self, v: int) -> None:
        self.b += struct.pack("<i", int(v))

    def i64(self, v: int) -> None:
        self.b += struct.pack("<q", int(v))

    def f64(self, v: float) -> None:
        self.b += struct.pack("<d", float(v))

    def raw(self, data: bytes) -> None:
        self.b += data

    def boolean(self, v: bool) -> None:
        self.u8(1 if v else 0)

    def string(self, s: str | None) -> None:
        if s is None:
            self.i32(-1)
            return
        raw = s.encode()
        self.i32(len(raw))
        self.raw(raw)

    def bstr(self, data: bytes | None) -> None:
        if data is None:
            self.i32(-1)
            return
        self.i32(len(data))
        self.raw(data)

    def nodeid(self, ns: int, ident: int) -> None:
        ident = int(ident)
        ns = int(ns)
        if ns == 0 and 0 <= ident <= 255:
            self.u8(0x00)
            self.u8(ident)
        elif 0 <= ns <= 255 and 0 <= ident <= 65535:
            self.u8(0x01)
            self.u8(ns)
            self.u16(ident)
        else:
            self.u8(0x02)
            self.u16(ns)
            self.u32(ident)

    def expanded(self, ns: int, ident: int) -> None:
        self.nodeid(ns, ident)

    def qname(self, ns: int, name: str) -> None:
        self.u16(ns)
        self.string(name)

    def ltext(self, text: str) -> None:
        self.u8(0x02)
        self.string(text)

    def ext_null(self) -> None:
        self.nodeid(0, 0)
        self.u8(0)

    def ext_body(self, ns: int, ident: int, body: bytes) -> None:
        self.nodeid(ns, ident)
        self.u8(1)
        self.bstr(body)

    def variant_i32(self, v: int) -> None:
        self.u8(6)
        self.i32(v)

    def variant_u32(self, v: int) -> None:
        self.u8(7)
        self.u32(v)

    def variant_i64(self, v: int) -> None:
        self.u8(8)
        self.i64(v)

    def variant_bool(self, v: bool) -> None:
        self.u8(1)
        self.boolean(v)

    def variant_datetime(self, ticks: int) -> None:
        self.u8(13)
        self.i64(ticks)

    def variant_nodeid(self, ns: int, ident: int) -> None:
        self.u8(17)
        self.nodeid(ns, ident)

    def variant_qname(self, ns: int, name: str) -> None:
        self.u8(20)
        self.qname(ns, name)

    def variant_ltext(self, text: str) -> None:
        self.u8(21)
        self.ltext(text)

    def variant_string(self, text: str) -> None:
        self.u8(12)
        self.string(text)

    def variant_string_array(self, items: list[str]) -> None:
        self.u8(0x80 | 12)
        self.i32(len(items))
        for item in items:
            self.string(item)

    def data_value_status(self, status: int) -> None:
        self.u8(0x02)
        self.u32(status)

    def data_value_variant(self, encode_variant, status: int, source_ticks: int, server_ticks: int) -> None:
        self.u8(0x01 | 0x02 | 0x04 | 0x08)
        encode_variant(self)
        self.u32(status)
        self.i64(source_ticks)
        self.i64(server_ticks)


class R:
    def __init__(self, data: bytes) -> None:
        self.b = data
        self.i = 0

    def need(self, n: int) -> None:
        if self.i + n > len(self.b):
            raise DecodeError("truncated")

    def u8(self) -> int:
        self.need(1)
        v = self.b[self.i]
        self.i += 1
        return v

    def u16(self) -> int:
        self.need(2)
        v = struct.unpack_from("<H", self.b, self.i)[0]
        self.i += 2
        return v

    def u32(self) -> int:
        self.need(4)
        v = struct.unpack_from("<I", self.b, self.i)[0]
        self.i += 4
        return v

    def i32(self) -> int:
        self.need(4)
        v = struct.unpack_from("<i", self.b, self.i)[0]
        self.i += 4
        return v

    def i64(self) -> int:
        self.need(8)
        v = struct.unpack_from("<q", self.b, self.i)[0]
        self.i += 8
        return v

    def f64(self) -> float:
        self.need(8)
        v = struct.unpack_from("<d", self.b, self.i)[0]
        self.i += 8
        return v

    def boolean(self) -> bool:
        return bool(self.u8())

    def string(self) -> str | None:
        n = self.i32()
        if n < 0:
            return None
        if n > 8_000_000:
            raise DecodeError("string too long")
        self.need(n)
        raw = self.b[self.i : self.i + n]
        self.i += n
        return raw.decode()

    def bstr(self) -> bytes | None:
        n = self.i32()
        if n < 0:
            return None
        if n > 8_000_000:
            raise DecodeError("bytestring too long")
        self.need(n)
        raw = self.b[self.i : self.i + n]
        self.i += n
        return raw

    def nodeid(self) -> tuple[int, int]:
        enc = self.u8()
        flags = enc & 0xF0
        kind = enc & 0x0F
        if flags & 0x40:
            _uri = self.string()
        ns_from_uri = bool(flags & 0x40)
        if kind == 0x00:
            ns, ident = 0, self.u8()
        elif kind == 0x01:
            ns, ident = self.u8(), self.u16()
        elif kind == 0x02:
            ns, ident = self.u16(), self.u32()
        elif kind == 0x03:
            ns = 0 if ns_from_uri else self.u16()
            ident = hash(self.string()) & 0xFFFFFFFF
        elif kind == 0x04:
            ns = 0 if ns_from_uri else self.u16()
            self.need(16)
            self.i += 16
            ident = 0
        elif kind == 0x05:
            ns = 0 if ns_from_uri else self.u16()
            self.bstr()
            ident = 0
        else:
            raise DecodeError(f"nodeid encoding {enc}")
        if flags & 0x80:
            self.u32()
        return ns, int(ident)

    def qname(self) -> tuple[int, str | None]:
        return self.u16(), self.string()

    def ltext(self) -> str | None:
        mask = self.u8()
        if mask & 0x01:
            self.string()
        if mask & 0x02:
            return self.string()
        return None

    def variant(self):
        mask = self.u8()
        typ = mask & 0x3F
        is_array = bool(mask & 0x80)
        has_dims = bool(mask & 0x40)

        def one():
            if typ == 1:
                return bool(self.u8())
            if typ == 3:
                return self.u8()
            if typ == 6:
                return self.i32()
            if typ == 7:
                return self.u32()
            if typ == 8:
                return self.i64()
            if typ == 11:
                return self.f64()
            if typ == 12:
                return self.string()
            if typ == 13:
                return self.i64()
            if typ == 17:
                return self.nodeid()
            if typ == 20:
                return self.qname()
            if typ == 21:
                return self.ltext()
            raise DecodeError(f"variant type {typ}")

        if not is_array:
            return one()
        n = self.i32()
        vals = [] if n < 0 else [one() for _ in range(n)]
        if has_dims:
            dims = self.i32()
            for _ in range(max(0, dims)):
                self.i32()
        return vals

    def extension(self) -> tuple[tuple[int, int], bytes | None]:
        type_id = self.nodeid()
        enc = self.u8()
        if enc == 0:
            return type_id, None
        if enc == 1:
            return type_id, self.bstr()
        if enc == 2:
            self.string()
            return type_id, None
        raise DecodeError(f"extension encoding {enc}")

    def request_header(self) -> tuple[int, int]:
        _token, handle, timeout = self.request_header_token()
        return handle, timeout

    def request_header_token(self) -> tuple[tuple[int, int], int, int]:
        token = self.nodeid()
        self.i64()
        handle = self.u32()
        self.u32()
        self.string()
        timeout = self.u32()
        self.extension()
        return token, handle, timeout

    def status_array(self) -> list[int]:
        n = self.i32()
        if n < 0:
            return []
        return [self.u32() for _ in range(n)]

    def u32_array(self) -> list[int]:
        n = self.i32()
        if n < 0:
            return []
        return [self.u32() for _ in range(n)]


def response_header(w: W, handle: int, status: int = GOOD) -> None:
    w.i64(now_ticks())
    w.u32(handle)
    w.u32(status)
    w.u8(0)
    w.i32(0)
    w.ext_null()


def _asym_header() -> bytes:
    w = W()
    w.string(POLICY_NONE)
    w.bstr(None)
    w.bstr(None)
    return bytes(w.b)


ASYM = _asym_header()


@dataclass
class Monitored:
    item_id: int
    client_handle: int
    index: int | None
    server_node: int | None
    sampling_ms: float
    last_raw: int | None = None
    reporting: bool = True


@dataclass
class Subscription:
    sub_id: int
    interval_ms: float
    lifetime: int
    keepalive: int
    enabled: bool = True
    items: dict[int, Monitored] = field(default_factory=dict)
    seq: int = 0
    pending: list[tuple[int, int, int]] = field(default_factory=list)
    last_sent: float = 0.0
    # Sent and not yet acknowledged: sequence number -> encoded NotificationMessage.
    retained: dict[int, bytes] = field(default_factory=dict)


@dataclass
class Session:
    session_id: int
    auth_token: int
    subscriptions: dict[int, Subscription] = field(default_factory=dict)
    continuations: dict[bytes, int] = field(default_factory=dict)
    timeout_ms: float = 60_000.0
    # The connection the session is activated on; None while detached.
    bound: Connection | None = None
    detached_at: float = 0.0


def _point_index(ns: int, ident: int, cfg: BenchConfig) -> int | None:
    if ns != 1 or ident < OPC_NODE_BASE:
        return None
    index = ident - OPC_NODE_BASE
    if index < 0 or index >= cfg.points:
        return None
    return index


class Connection:
    def __init__(self, sock: socket.socket, server: OpcUaServer) -> None:
        self.sock = sock
        self.server = server
        self.peer_recv = 65536
        self.channel_id = 1
        self.token_id = 1
        self.seq = 0
        self.session: Session | None = None
        self.pending_publish: list[tuple[int, list[tuple[int, int]]]] = []
        # Guards session and subscription state. Re-entrant: handlers run under
        # it and _publish takes it again. Order: lock, then send_lock.
        self.lock = threading.RLock()
        self.send_lock = threading.Lock()
        self.alive = True
        self._chunks = bytearray()
        self.endpoint_url = "opc.tcp://localhost/oip/bench"

    def close(self) -> None:
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass

    def run(self) -> None:
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.sock.settimeout(1.0)
            if not self._hello():
                return
            while self.alive and not self.server.stopped.is_set():
                chunk = self._read_chunk()
                if chunk is None:
                    continue
                msg_type, flag, request_id, body = chunk
                if msg_type == b"ERR":
                    return
                if flag == b"A":
                    self._chunks.clear()
                    continue
                self._chunks += body
                if flag == b"C":
                    continue
                payload = bytes(self._chunks)
                self._chunks.clear()
                if msg_type == b"CLO":
                    return
                self._dispatch(msg_type, request_id, payload)
                self.flush_publish()
        except (DecodeError, ConnectionError, OSError, TimeoutError, struct.error, UnicodeError):
            return
        finally:
            self.close()
            self.server.forget(self)

    def _read_exact(self, n: int) -> bytes | None:
        buf = bytearray()
        while len(buf) < n:
            if not self.alive or self.server.stopped.is_set():
                return None
            try:
                chunk = self.sock.recv(n - len(buf))
            except socket.timeout:
                continue
            if not chunk:
                return None
            buf += chunk
        return bytes(buf)

    def _hello(self) -> bool:
        header = self._read_exact(8)
        if header is None or header[0:3] != b"HEL":
            return False
        size = struct.unpack_from("<I", header, 4)[0]
        if size < 28 or size > 2_000_000:
            return False
        rest = self._read_exact(size - 8)
        if rest is None:
            return False
        r = R(rest)
        r.u32()
        recv_buf = r.u32()
        r.u32()
        r.u32()
        r.u32()
        url = r.string()
        if url:
            self.endpoint_url = url
        # Honour a small client buffer so tests can force chunking, but stay
        # large enough that the message header itself fits.
        self.peer_recv = max(256, min(recv_buf or 65536, 1_048_576))
        ack = W()
        ack.u32(0)
        ack.u32(1 << 18)
        ack.u32(1 << 18)
        ack.u32(0)
        ack.u32(0)
        self._write_raw(b"ACK", b"F", bytes(ack.b), secure=False, request_id=0)
        return True

    def _read_chunk(self) -> tuple[bytes, bytes, int, bytes] | None:
        header = self._read_exact(8)
        if header is None:
            self.alive = False
            return None
        msg_type = header[0:3]
        flag = header[3:4]
        size = struct.unpack_from("<I", header, 4)[0]
        if size < 8 or size > 2_000_000:
            raise DecodeError("bad chunk size")
        rest = self._read_exact(size - 8)
        if rest is None:
            self.alive = False
            return None
        if msg_type == b"ERR":
            return msg_type, flag, 0, rest
        r = R(rest)
        r.u32()
        if msg_type == b"OPN":
            r.string()
            r.bstr()
            r.bstr()
        else:
            r.u32()
        r.u32()
        request_id = r.u32()
        return msg_type, flag, request_id, rest[r.i :]

    def _write_raw(self, msg_type: bytes, flag: bytes, body: bytes, secure: bool, request_id: int) -> None:
        with self.send_lock:
            self._write_locked(msg_type, flag, body, secure, request_id)

    def _write_locked(self, msg_type: bytes, flag: bytes, body: bytes, secure: bool, request_id: int) -> None:
        """Caller holds send_lock, so the sequence number goes on the wire in order."""
        packet = bytearray()
        packet += msg_type + flag
        packet += b"\x00\x00\x00\x00"
        if secure:
            packet += struct.pack("<I", self.channel_id)
            if msg_type == b"OPN":
                packet += ASYM
            else:
                packet += struct.pack("<I", self.token_id)
            self.seq = (self.seq + 1) & 0xFFFFFFFF or 1
            packet += struct.pack("<II", self.seq, request_id & 0xFFFFFFFF)
        packet += body
        struct.pack_into("<I", packet, 4, len(packet))
        self.sock.sendall(packet)

    def send_body(self, msg_type: bytes, body: bytes, request_id: int) -> None:
        """Send one message. All of its chunks go out under one send_lock hold,
        so another thread's message is never interleaved between them (Part 6, 6.7.2)."""
        overhead = 8 + 4 + (len(ASYM) if msg_type == b"OPN" else 4) + 8
        budget = max(64, self.peer_recv - overhead)
        with self.send_lock:
            if not body:
                self._write_locked(msg_type, b"F", b"", secure=True, request_id=request_id)
                return
            start = 0
            while start < len(body):
                part = body[start : start + budget]
                start += len(part)
                flag = b"F" if start >= len(body) else b"C"
                self._write_locked(msg_type, flag, part, secure=True, request_id=request_id)

    def fault(self, msg_type: bytes, request_id: int, handle: int, status: int) -> None:
        w = W()
        w.nodeid(0, ENC_SERVICE_FAULT)
        response_header(w, handle, status)
        self.send_body(msg_type, bytes(w.b), request_id)

    def _dispatch(self, msg_type: bytes, request_id: int, payload: bytes) -> None:
        try:
            r = R(payload)
            ns, ident = r.nodeid()
        except DecodeError:
            self.fault(b"MSG", request_id, 0, BAD_DECODING)
            return
        if ns != 0:
            self.fault(b"MSG", request_id, 0, BAD_SERVICE_UNSUPPORTED)
            return
        handler = {
            ENC_OPEN_SECURE_CHANNEL_REQ: self._open,
            ENC_GET_ENDPOINTS_REQ: self._endpoints,
            ENC_CREATE_SESSION_REQ: self._create_session,
            ENC_ACTIVATE_SESSION_REQ: self._activate,
            ENC_CLOSE_SESSION_REQ: self._close_session,
            ENC_READ_REQ: self._read,
            ENC_BROWSE_REQ: self._browse,
            ENC_BROWSE_NEXT_REQ: self._browse_next,
            ENC_CREATE_SUBSCRIPTION_REQ: self._create_sub,
            ENC_MODIFY_SUBSCRIPTION_REQ: self._modify_sub,
            ENC_DELETE_SUBSCRIPTIONS_REQ: self._delete_subs,
            ENC_TRANSFER_SUBSCRIPTIONS_REQ: self._transfer_subs,
            ENC_SET_PUBLISHING_MODE_REQ: self._publishing_mode,
            ENC_CREATE_MONITORED_ITEMS_REQ: self._create_items,
            ENC_DELETE_MONITORED_ITEMS_REQ: self._delete_items,
            ENC_SET_MONITORING_MODE_REQ: self._monitoring_mode,
            ENC_PUBLISH_REQ: self._publish,
            ENC_REPUBLISH_REQ: self._republish,
            ENC_CLOSE_SECURE_CHANNEL_REQ: None,
        }.get(ident, "unsupported")
        if ident == ENC_CLOSE_SECURE_CHANNEL_REQ:
            self.alive = False
            return
        if handler == "unsupported":
            handle = 0
            try:
                handle, _timeout = r.request_header()
            except DecodeError:
                handle = 0
            reply_type = b"OPN" if msg_type == b"OPN" else b"MSG"
            self.fault(reply_type, request_id, handle, BAD_SERVICE_UNSUPPORTED)
            return
        assert handler is not None
        try:
            with self.lock:
                handler(msg_type, request_id, r)
        except DecodeError:
            self.fault(b"MSG", request_id, 0, BAD_DECODING)

    def _open(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        r.u32()
        r.u32()
        mode = r.u32()
        r.bstr()
        lifetime = r.u32()
        if mode not in (0, 1):
            self.fault(b"OPN", request_id, handle, BAD_SERVICE_UNSUPPORTED)
            return
        w = W()
        w.nodeid(0, ENC_OPEN_SECURE_CHANNEL_RES)
        response_header(w, handle)
        w.u32(0)
        w.u32(self.channel_id)
        w.u32(self.token_id)
        w.i64(now_ticks())
        w.u32(lifetime or 60_000)
        w.bstr(b"\x00" * 32)
        self.send_body(b"OPN", bytes(w.b), request_id)

    def _endpoint_description(self, w: W) -> None:
        w.string(self.endpoint_url)
        w.string("urn:oip:bench")
        w.string("urn:oip:bench")
        w.ltext("OIP bench points")
        w.u32(0)
        w.string(None)
        w.string(None)
        w.i32(1)
        w.string(self.endpoint_url)
        w.bstr(None)
        w.u32(1)
        w.string(POLICY_NONE)
        w.i32(1)
        w.string("anonymous")
        w.u32(0)
        w.string(None)
        w.string(None)
        w.string(None)
        w.string(TRANSPORT_TCP)
        w.u8(0)

    def _endpoints(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        url = r.string()
        if url:
            self.endpoint_url = url
        w = W()
        w.nodeid(0, ENC_GET_ENDPOINTS_RES)
        response_header(w, handle)
        w.i32(1)
        self._endpoint_description(w)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _create_session(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        timeout = 60_000.0
        # ApplicationDescription is variable; read it field by field.
        r.string()
        r.string()
        r.ltext()
        r.u32()
        r.string()
        r.string()
        n = r.i32()
        if n > 0:
            for _ in range(n):
                r.string()
        r.string()
        url = r.string()
        if url:
            self.endpoint_url = url
        r.string()
        r.bstr()
        r.bstr()
        try:
            timeout = r.f64()
        except DecodeError:
            timeout = 60_000.0
        n = self.server.next_id()
        session = Session(session_id=10_000 + n, auth_token=20_000 + n,
                          timeout_ms=timeout if timeout > 0 else 60_000.0)
        self.server.add_session(session)
        self.server.bind(self, session)
        w = W()
        w.nodeid(0, ENC_CREATE_SESSION_RES)
        response_header(w, handle)
        w.nodeid(1, session.session_id)
        w.nodeid(1, session.auth_token)
        w.f64(timeout if timeout > 0 else 60_000.0)
        w.bstr(b"\x00" * 32)
        w.bstr(None)
        w.i32(1)
        self._endpoint_description(w)
        w.i32(0)
        w.string(None)
        w.bstr(None)
        w.u32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _activate(self, msg_type: bytes, request_id: int, r: R) -> None:
        (token_ns, token), handle, _timeout = r.request_header_token()
        r.string()
        r.bstr()
        n = r.i32()
        if n and n > 0:
            for _ in range(n):
                r.bstr()
                r.bstr()
        locales = r.i32()
        if locales and locales > 0:
            for _ in range(locales):
                r.string()
        r.extension()
        r.string()
        r.bstr()
        # Sessions belong to the server, not the TCP connection, so a client
        # that lost its channel can activate its session on a new one
        # (Part 4, 5.6.3) and keep its subscriptions.
        session = self.server.session_for(token) if token_ns == 1 else None
        if session is None:
            self.fault(b"MSG", request_id, handle, BAD_SESSION_ID_INVALID)
            return
        self.server.bind(self, session)
        w = W()
        w.nodeid(0, ENC_ACTIVATE_SESSION_RES)
        response_header(w, handle)
        w.bstr(b"\x00" * 32)
        w.i32(0)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _close_session(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        w = W()
        w.nodeid(0, ENC_CLOSE_SESSION_RES)
        response_header(w, handle)
        if self.session is not None:
            self.server.drop_session(self.session)
        self.session = None
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _source_ticks(self, index: int, cfg: BenchConfig, now_ms: int) -> int:
        tick = tick_at(now_ms, cfg.epoch_ms, cfg.period_ms)
        source_ms = cfg.epoch_ms + tick * cfg.period_ms
        return ticks_from_unix_ms(source_ms)

    def _encode_value(self, ns: int, ident: int, w_variant_target: W) -> int:
        """Write a variant for the node into a throwaway buffer. Returns status."""
        return GOOD

    def _value_encoder(self, ns: int, ident: int, cfg: BenchConfig, now_ms: int):
        index = _point_index(ns, ident, cfg)
        source = self._source_ticks(index, cfg, now_ms) if index is not None else now_ticks()
        server_ticks = now_ticks()

        def encode(w: W) -> None:
            if index is not None:
                w.variant_i32(raw_at(index, now_ms, cfg))
                return
            if ns == 0 and ident == 2255:
                w.variant_string_array(["http://opcfoundation.org/UA/", OPC_NAMESPACE_URI])
                return
            if ns == 0 and ident == 2259:
                w.variant_i32(0)
                return
            if ns == 0 and ident == 2258:
                w.variant_datetime(now_ticks())
                return
            if ns == 1 and ident == 1:
                w.variant_ltext("Points")
                return
            w.variant_i32(0)

        status = GOOD
        if index is None and not _known_node(ns, ident, cfg):
            status = BAD_NODEID_UNKNOWN
        return status, encode, source, server_ticks

    def _read(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        r.f64()
        r.u32()
        count = r.i32()
        nodes = []
        for _ in range(max(0, count)):
            node = r.nodeid()
            attr = r.u32()
            r.string()
            r.qname()
            nodes.append((node, attr))
        cfg = self.server.space.config
        now_ms = self.server.space.now_ms()
        w = W()
        w.nodeid(0, ENC_READ_RES)
        response_header(w, handle)
        w.i32(len(nodes))
        for (ns, ident), attr in nodes:
            self._write_attribute(w, ns, ident, attr, cfg, now_ms)
            self.server.space.opcua_reads += 1
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _write_attribute(self, w: W, ns: int, ident: int, attr: int, cfg: BenchConfig, now_ms: int) -> None:
        if not _known_node(ns, ident, cfg):
            w.data_value_status(BAD_NODEID_UNKNOWN)
            return
        index = _point_index(ns, ident, cfg)
        source = self._source_ticks(index, cfg, now_ms) if index is not None else now_ticks()

        def put(encoder) -> None:
            w.data_value_variant(encoder, GOOD, source, now_ticks())

        if attr == ATTR_VALUE:
            status, encoder, source, server = self._value_encoder(ns, ident, cfg, now_ms)
            if status != GOOD:
                w.data_value_status(status)
            else:
                w.data_value_variant(encoder, GOOD, source, server)
            return
        if attr == ATTR_NODEID:
            put(lambda out: out.variant_nodeid(ns, ident))
            return
        if attr == ATTR_NODECLASS:
            kind = NODECLASS_VARIABLE if index is not None or ident in (2255, 2258, 2259) else NODECLASS_OBJECT
            put(lambda out: out.variant_i32(kind))
            return
        if attr == ATTR_BROWSENAME:
            name = f"P{index:07d}" if index is not None else _browse_name(ns, ident)
            put(lambda out: out.variant_qname(ns if index is not None else 0, name))
            return
        if attr == ATTR_DISPLAYNAME:
            text = f"P{index:07d}" if index is not None else _browse_name(ns, ident)
            put(lambda out: out.variant_ltext(text))
            return
        if attr == ATTR_DATATYPE:
            put(lambda out: out.variant_nodeid(0, 6 if index is not None else 12))
            return
        if attr == ATTR_VALUERANK:
            put(lambda out: out.variant_i32(-1 if index is not None else 1))
            return
        if attr in (ATTR_ACCESS, ATTR_USER_ACCESS):
            put(lambda out: out.variant_u32(1))
            return
        if attr == ATTR_HISTORIZING:
            put(lambda out: out.variant_bool(False))
            return
        w.data_value_status(BAD_ATTRIBUTE_ID_INVALID)

    def _refs_for(self, ns: int, ident: int, cfg: BenchConfig) -> list[tuple[int, int, int, str, int, int]]:
        """Forward references: (ref_ns, ref_i, target_ns, name, node_class, type_i) plus target id."""
        if ns == 0 and ident == 85:
            return [(0, 35, 1, 1, "Points", NODECLASS_OBJECT, 61)]
        if ns == 1 and ident == 1:
            rows = []
            for index in range(cfg.points):
                rows.append((0, 35, 1, OPC_NODE_BASE + index, f"P{index:07d}", NODECLASS_VARIABLE, 63))
            return rows
        if _point_index(ns, ident, cfg) is not None:
            return [(0, 40, 0, 63, "BaseDataVariableType", NODECLASS_VARIABLE, 0)]
        return []

    def _write_refs(self, w: W, rows: list[tuple], start: int) -> bytes | None:
        page = self.server.browse_page
        chunk = rows[start : start + page]
        w.u32(GOOD)
        if start + page < len(rows):
            token = (start + page).to_bytes(4, "little")
            w.bstr(token)
        else:
            token = None
            w.bstr(None)
        w.i32(len(chunk))
        for ref_ns, ref_i, t_ns, t_i, name, node_class, type_i in chunk:
            w.nodeid(ref_ns, ref_i)
            w.boolean(True)
            w.expanded(t_ns, t_i)
            w.qname(t_ns, name)
            w.ltext(name)
            w.u32(node_class)
            if type_i:
                w.expanded(0, type_i)
            else:
                w.expanded(0, 0)
        return token

    def _browse(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        r.nodeid()
        r.i64()
        r.u32()
        r.u32()
        count = r.i32()
        nodes = []
        for _ in range(max(0, count)):
            nodes.append(r.nodeid())
            r.u32()
            r.nodeid()
            r.boolean()
            r.u32()
            r.u32()
        cfg = self.server.space.config
        w = W()
        w.nodeid(0, ENC_BROWSE_RES)
        response_header(w, handle)
        w.i32(len(nodes))
        for ns, ident in nodes:
            if not _known_node(ns, ident, cfg) and not (ns == 0 and ident == 85):
                w.u32(BAD_NODEID_UNKNOWN)
                w.bstr(None)
                w.i32(0)
                continue
            rows = self._refs_for(ns, ident, cfg)
            token = self._write_refs(w, rows, 0)
            if token is not None and self.session is not None:
                self.session.continuations[token] = (ns << 32) | ident
                # store next index in the token itself; node identity is the key prefix
                self.session.continuations[bytes((ns & 0xFF,)) + ident.to_bytes(4, "little") + token] = 1
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _browse_next(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        release = r.boolean()
        n = r.i32()
        tokens = [r.bstr() or b"" for _ in range(max(0, n))]
        cfg = self.server.space.config
        w = W()
        w.nodeid(0, ENC_BROWSE_NEXT_RES)
        response_header(w, handle)
        w.i32(len(tokens))
        for token in tokens:
            if release or len(token) < 4:
                w.u32(GOOD)
                w.bstr(None)
                w.i32(0)
                continue
            start = int.from_bytes(token[:4], "little")
            rows = self._refs_for(1, 1, cfg)
            self._write_refs(w, rows, start)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _revise_sub(self, interval: float, lifetime: int, keepalive: int) -> tuple[float, int, int]:
        interval = 100.0 if interval <= 0 else max(50.0, interval)
        keepalive = max(1, keepalive)
        lifetime = max(lifetime, keepalive * 3)
        return interval, lifetime, keepalive

    def _create_sub(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        if self.session is None:
            self.fault(b"MSG", request_id, handle, BAD_SUBSCRIPTION_ID_INVALID)
            return
        interval = r.f64()
        lifetime = r.u32()
        keepalive = r.u32()
        r.u32()
        enabled = r.boolean()
        r.u8()
        interval, lifetime, keepalive = self._revise_sub(interval, lifetime, keepalive)
        sub = Subscription(sub_id=self.server.next_id(), interval_ms=interval, lifetime=lifetime, keepalive=keepalive, enabled=enabled)
        sub.last_sent = time.monotonic()
        self.session.subscriptions[sub.sub_id] = sub
        w = W()
        w.nodeid(0, ENC_CREATE_SUBSCRIPTION_RES)
        response_header(w, handle)
        w.u32(sub.sub_id)
        w.f64(interval)
        w.u32(lifetime)
        w.u32(keepalive)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _modify_sub(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        sub_id = r.u32()
        interval = r.f64()
        lifetime = r.u32()
        keepalive = r.u32()
        sub = self.session.subscriptions.get(sub_id) if self.session else None
        w = W()
        w.nodeid(0, ENC_MODIFY_SUBSCRIPTION_RES)
        if sub is None:
            response_header(w, handle, BAD_SUBSCRIPTION_ID_INVALID)
            self.send_body(b"MSG", bytes(w.b), request_id)
            return
        interval, lifetime, keepalive = self._revise_sub(interval, lifetime, keepalive)
        sub.interval_ms = interval
        sub.lifetime = lifetime
        sub.keepalive = keepalive
        response_header(w, handle)
        w.f64(interval)
        w.u32(lifetime)
        w.u32(keepalive)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _delete_subs(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        ids = r.u32_array()
        w = W()
        w.nodeid(0, ENC_DELETE_SUBSCRIPTIONS_RES)
        response_header(w, handle)
        w.i32(len(ids))
        for sub_id in ids:
            if self.session and sub_id in self.session.subscriptions:
                del self.session.subscriptions[sub_id]
                w.u32(GOOD)
            else:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _publishing_mode(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        enabled = r.boolean()
        ids = r.u32_array()
        w = W()
        w.nodeid(0, ENC_SET_PUBLISHING_MODE_RES)
        response_header(w, handle)
        w.i32(len(ids))
        for sub_id in ids:
            sub = self.session.subscriptions.get(sub_id) if self.session else None
            if sub is None:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID)
            else:
                sub.enabled = enabled
                w.u32(GOOD)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _create_items(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        sub_id = r.u32()
        r.u32()
        count = r.i32()
        specs = []
        for _ in range(max(0, count)):
            node = r.nodeid()
            attr = r.u32()
            r.string()
            r.qname()
            mode = r.u32()
            client_handle = r.u32()
            sampling = r.f64()
            r.extension()
            queue = r.u32()
            r.boolean()
            specs.append((node, attr, mode, client_handle, sampling, queue))
        sub = self.session.subscriptions.get(sub_id) if self.session else None
        w = W()
        w.nodeid(0, ENC_CREATE_MONITORED_ITEMS_RES)
        response_header(w, handle)
        w.i32(len(specs))
        cfg = self.server.space.config
        for (ns, ident), attr, mode, client_handle, sampling, queue in specs:
            if sub is None:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID)
                w.u32(0)
                w.f64(0)
                w.u32(0)
                w.ext_null()
                continue
            point = _point_index(ns, ident, cfg)
            server_value = ns == 0 and ident in (2255, 2258, 2259)
            if attr != ATTR_VALUE or (point is None and not server_value):
                w.u32(BAD_ATTRIBUTE_ID_INVALID if attr != ATTR_VALUE and (point is not None or server_value) else BAD_NODEID_UNKNOWN)
                w.u32(0)
                w.f64(0)
                w.u32(0)
                w.ext_null()
                continue
            item = Monitored(
                item_id=self.server.next_id(),
                client_handle=client_handle,
                index=_point_index(ns, ident, cfg),
                server_node=ident if ns == 0 else None,
                sampling_ms=0 if sampling < 0 else sampling,
                reporting=mode != 0,
            )
            sub.items[item.item_id] = item
            self._enqueue_item(sub, item)
            revised = max(item.sampling_ms, float(cfg.period_ms))
            w.u32(GOOD)
            w.u32(item.item_id)
            w.f64(revised)
            w.u32(max(1, queue))
            w.ext_null()
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _delete_items(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        sub_id = r.u32()
        ids = r.u32_array()
        sub = self.session.subscriptions.get(sub_id) if self.session else None
        w = W()
        w.nodeid(0, ENC_DELETE_MONITORED_ITEMS_RES)
        response_header(w, handle)
        w.i32(len(ids))
        for item_id in ids:
            if sub is not None and item_id in sub.items:
                del sub.items[item_id]
                w.u32(GOOD)
            else:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID if sub is None else BAD_NODEID_UNKNOWN)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _monitoring_mode(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        sub_id = r.u32()
        mode = r.u32()
        ids = r.u32_array()
        sub = self.session.subscriptions.get(sub_id) if self.session else None
        w = W()
        w.nodeid(0, ENC_SET_MONITORING_MODE_RES)
        response_header(w, handle)
        w.i32(len(ids))
        for item_id in ids:
            item = sub.items.get(item_id) if sub else None
            if item is None:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID if sub is None else BAD_NODEID_UNKNOWN)
            else:
                item.reporting = mode != 0
                w.u32(GOOD)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _enqueue_item(self, sub: Subscription, item: Monitored) -> None:
        cfg = self.server.space.config
        now_ms = self.server.space.now_ms()
        if item.index is None:
            raw = 0
        else:
            raw = raw_at(item.index, now_ms, cfg)
        item.last_raw = raw
        sub.pending.append((item.client_handle, raw if item.index is not None else 0, item.index if item.index is not None else -1))

    def sample(self) -> None:
        """Caller holds self.lock."""
        session = self.session
        if session is None:
            return
        cfg = self.server.space.config
        now_ms = self.server.space.now_ms()
        for sub in session.subscriptions.values():
            if not sub.enabled:
                continue
            for item in sub.items.values():
                if not item.reporting or item.index is None:
                    continue
                raw = raw_at(item.index, now_ms, cfg)
                if item.last_raw != raw:
                    item.last_raw = raw
                    sub.pending.append((item.client_handle, raw, item.index))

    def _publish(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        n = r.i32()
        acks = []
        for _ in range(max(0, n)):
            acks.append((r.u32(), r.u32()))
        with self.lock:
            # Acknowledged messages leave the retransmission queue now; the
            # results go out with the response to this request (Part 4, 5.13.5).
            results = []
            session = self.session
            for sub_id, ack_seq in acks:
                target = session.subscriptions.get(sub_id) if session else None
                if target is None:
                    results.append(BAD_SUBSCRIPTION_ID_INVALID)
                elif target.retained.pop(ack_seq, None) is not None:
                    results.append(GOOD)
                else:
                    results.append(BAD_SEQUENCE_NUMBER_UNKNOWN)
            self.pending_publish.append((handle, request_id, results))

    def flush_publish(self) -> None:
        if not self.alive:
            return
        with self.lock:
            self.sample()
            session = self.session
            if not self.pending_publish or session is None:
                return
            handle, request_id, ack_results = self.pending_publish[0]
            sub = self._sub_for_publish(session)
            if sub is None:
                return
            notifications = sub.pending[:1000]
            more = len(sub.pending) > 1000
            keepalive_due = (time.monotonic() - sub.last_sent) >= (sub.interval_ms * max(1, sub.keepalive) / 1000.0)
            if not notifications and not keepalive_due:
                return
            if notifications:
                sub.pending = sub.pending[1000:]
                sub.seq = (sub.seq + 1) & 0xFFFFFFFF or 1
                seq = sub.seq
            else:
                seq = sub.seq
                more = False
            sub.last_sent = time.monotonic()
            self.pending_publish.pop(0)
            message = self._notification_message(seq, notifications)
            if notifications:
                sub.retained[seq] = message
                while len(sub.retained) > RETRANSMIT_QUEUE:
                    del sub.retained[next(iter(sub.retained))]
            available = list(sub.retained)
            w = W()
            w.nodeid(0, ENC_PUBLISH_RES)
            response_header(w, handle)
            w.u32(sub.sub_id)
            w.i32(len(available))
            for number in available:
                w.u32(number)
            w.boolean(more)
            w.raw(message)
            w.i32(len(ack_results))
            for status in ack_results:
                w.u32(status)
            w.i32(0)
            try:
                self.send_body(b"MSG", bytes(w.b), request_id)
            except OSError:
                self.alive = False

    def _notification_message(self, seq: int, notifications: list[tuple[int, int, int]]) -> bytes:
        """Encoded NotificationMessage: the bytes Publish sends and Republish resends."""
        cfg = self.server.space.config
        now_ms = self.server.space.now_ms()
        w = W()
        w.u32(seq)
        w.i64(now_ticks())
        if not notifications:
            w.i32(0)
            return bytes(w.b)
        note = W()
        note.i32(len(notifications))
        for client_handle, raw, index in notifications:
            note.u32(client_handle)
            source = self._source_ticks(index, cfg, now_ms) if index >= 0 else now_ticks()

            def encode(out: W, value=raw) -> None:
                out.variant_i32(value)

            note.data_value_variant(encode, GOOD, source, now_ticks())
        note.i32(0)
        w.i32(1)
        w.ext_body(0, ENC_DATA_CHANGE, bytes(note.b))
        return bytes(w.b)

    def _sub_for_publish(self, session: Session) -> Subscription | None:
        subs = list(session.subscriptions.values())
        for sub in subs:
            if sub.pending:
                return sub
        return subs[0] if subs else None

    def _republish(self, msg_type: bytes, request_id: int, r: R) -> None:
        handle, _timeout = r.request_header()
        sub_id = r.u32()
        seq = r.u32()
        sub = self.session.subscriptions.get(sub_id) if self.session else None
        if sub is None:
            self.fault(b"MSG", request_id, handle, BAD_SUBSCRIPTION_ID_INVALID)
            return
        message = sub.retained.get(seq)
        if message is None:
            self.fault(b"MSG", request_id, handle, BAD_MESSAGE_NOT_AVAILABLE)
            return
        w = W()
        w.nodeid(0, ENC_REPUBLISH_RES)
        response_header(w, handle)
        w.raw(message)
        self.send_body(b"MSG", bytes(w.b), request_id)

    def _transfer_subs(self, msg_type: bytes, request_id: int, r: R) -> None:
        """TransferSubscriptions (Part 4, 5.13.7): move subscriptions from any
        session on this server to the caller's session."""
        handle, _timeout = r.request_header()
        ids = r.u32_array()
        send_initial = r.boolean()
        w = W()
        w.nodeid(0, ENC_TRANSFER_SUBSCRIPTIONS_RES)
        if self.session is None:
            response_header(w, handle, BAD_SESSION_ID_INVALID)
            w.i32(0)
            w.i32(0)
            self.send_body(b"MSG", bytes(w.b), request_id)
            return
        response_header(w, handle)
        w.i32(len(ids))
        for sub_id in ids:
            sub = self.server.take_subscription(sub_id, self.session)
            if sub is None:
                w.u32(BAD_SUBSCRIPTION_ID_INVALID)
                w.i32(0)
                continue
            if send_initial:
                for item in sub.items.values():
                    if item.reporting:
                        self._enqueue_item(sub, item)
            w.u32(GOOD)
            w.i32(len(sub.retained))
            for number in sub.retained:
                w.u32(number)
        w.i32(0)
        self.send_body(b"MSG", bytes(w.b), request_id)


def _browse_name(ns: int, ident: int) -> str:
    return {
        (0, 85): "Objects",
        (0, 2253): "Server",
        (0, 2255): "NamespaceArray",
        (0, 2258): "CurrentTime",
        (0, 2259): "State",
        (1, 1): "Points",
    }.get((ns, ident), f"N{ident}")


def _known_node(ns: int, ident: int, cfg: BenchConfig) -> bool:
    if ns == 1 and ident == 1:
        return True
    if ns == 0 and ident in (84, 85, 2253, 2255, 2256, 2258, 2259):
        return True
    return _point_index(ns, ident, cfg) is not None


class OpcUaServer:
    def __init__(self, space, browse_page: int = 500) -> None:
        self.space = space
        self.browse_page = browse_page
        self.stopped = threading.Event()
        self.port = 0
        self._listener: socket.socket | None = None
        self._conns: list[Connection] = []
        self._lock = threading.Lock()
        self._sessions: dict[int, Session] = {}
        self._next_id = 1

    def next_id(self) -> int:
        """Server-wide ids for sessions, subscriptions and monitored items."""
        with self._lock:
            n = self._next_id
            self._next_id += 1
            return n

    def add_session(self, session: Session) -> None:
        with self._lock:
            self._sessions[session.auth_token] = session

    def session_for(self, auth_token: int) -> Session | None:
        with self._lock:
            return self._sessions.get(auth_token)

    def drop_session(self, session: Session) -> None:
        with self._lock:
            self._sessions.pop(session.auth_token, None)

    def bind(self, conn: Connection, session: Session) -> None:
        """Activate `session` on `conn`, taking it away from any other connection."""
        with self._lock:
            previous = session.bound
            session.bound = conn
            if conn.session is not None and conn.session is not session:
                conn.session.bound = None
                conn.session.detached_at = time.monotonic()
        if previous is not None and previous is not conn:
            previous.session = None
        conn.session = session

    def take_subscription(self, sub_id: int, target: Session) -> Subscription | None:
        with self._lock:
            sessions = list(self._sessions.values())
        for session in sessions:
            sub = session.subscriptions.get(sub_id)
            if sub is not None:
                if session is not target:
                    owner = session.bound
                    lock = owner.lock if owner is not None else None
                    if lock is not None:
                        with lock:
                            session.subscriptions.pop(sub_id, None)
                    else:
                        session.subscriptions.pop(sub_id, None)
                    target.subscriptions[sub_id] = sub
                return sub
        return None

    def _expire_sessions(self) -> None:
        now = time.monotonic()
        with self._lock:
            for token, session in list(self._sessions.items()):
                if session.bound is None and now - session.detached_at > session.timeout_ms / 1000.0:
                    del self._sessions[token]

    def start(self, bind: str, port: int) -> int:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((bind, port))
        listener.listen(64)
        listener.settimeout(0.5)
        self._listener = listener
        self.port = listener.getsockname()[1]
        threading.Thread(target=self._accept, name="opcua-accept", daemon=True).start()
        threading.Thread(target=self._sample_loop, name="opcua-sample", daemon=True).start()
        return self.port

    def stop(self) -> None:
        self.stopped.set()
        if self._listener is not None:
            try:
                self._listener.close()
            except OSError:
                pass
        with self._lock:
            conns = list(self._conns)
        for conn in conns:
            conn.close()

    def forget(self, conn: Connection) -> None:
        with self._lock:
            if conn in self._conns:
                self._conns.remove(conn)
            session = conn.session
            if session is not None and session.bound is conn:
                session.bound = None
                session.detached_at = time.monotonic()

    def _accept(self) -> None:
        assert self._listener is not None
        while not self.stopped.is_set():
            try:
                sock, _addr = self._listener.accept()
            except (socket.timeout, OSError):
                continue
            conn = Connection(sock, self)
            with self._lock:
                self._conns.append(conn)
            threading.Thread(target=conn.run, name="opcua-conn", daemon=True).start()

    def _sample_loop(self) -> None:
        while not self.stopped.is_set():
            with self._lock:
                conns = list(self._conns)
            for conn in conns:
                try:
                    conn.flush_publish()
                except OSError:
                    conn.alive = False
            self._expire_sessions()
            self.stopped.wait(0.05)
