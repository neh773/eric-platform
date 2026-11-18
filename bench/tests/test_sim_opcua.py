"""Bench simulator OPC UA server: secure-channel framing under concurrent senders."""

from __future__ import annotations

import socket
import struct
import sys
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sim"))
sys.path.append(str(ROOT / "bench"))  # the simulator's `formula` module

import importlib.util  # noqa: E402

# Load bench/sim/opcua.py by path: bench/bench/opcua.py is a different module.
_spec = importlib.util.spec_from_file_location("bench_sim_opcua", ROOT / "sim" / "opcua.py")
opcua = importlib.util.module_from_spec(_spec)
sys.modules["bench_sim_opcua"] = opcua
_spec.loader.exec_module(opcua)

from formula import OPC_NODE_BASE  # noqa: E402


class _SlowSocket:
    """Records every chunk and yields the CPU inside sendall so senders interleave."""

    def __init__(self) -> None:
        self.packets: list[bytes] = []
        self._guard = threading.Lock()

    def sendall(self, data: bytes) -> None:
        time.sleep(0.0005)
        with self._guard:
            self.packets.append(bytes(data))

    def close(self) -> None:
        pass


def _chunks(packets: list[bytes]) -> list[tuple[bytes, bytes, int, int, bytes]]:
    """(type, flag, sequence number, request id, body) for each secure chunk."""
    out = []
    for packet in packets:
        msg_type = packet[0:3]
        flag = packet[3:4]
        size = struct.unpack_from("<I", packet, 4)[0]
        assert size == len(packet), (size, len(packet))
        offset = 8 + 4 + (len(opcua.ASYM) if msg_type == b"OPN" else 4)
        seq, request_id = struct.unpack_from("<II", packet, offset)
        out.append((msg_type, flag, seq, request_id, packet[offset + 8 :]))
    return out


class SecureChannelFramingTest(unittest.TestCase):
    def _connection(self, peer_recv: int) -> tuple[opcua.Connection, _SlowSocket]:
        sock = _SlowSocket()
        conn = opcua.Connection(sock, server=None)  # type: ignore[arg-type]
        conn.peer_recv = peer_recv
        return conn, sock

    def test_sequence_numbers_leave_in_order_and_messages_are_never_interleaved(self):
        # Two threads send on one channel, as the reader thread and the sample
        # thread do. Each message spans several chunks (small peer buffer).
        conn, sock = self._connection(peer_recv=256)
        bodies = {}
        senders = 4
        per_sender = 15

        def send(sender: int) -> None:
            for n in range(per_sender):
                request_id = sender * 1000 + n + 1
                body = bytes([sender]) * (700 + n)
                bodies[request_id] = body
                conn.send_body(b"MSG", body, request_id)

        threads = [threading.Thread(target=send, args=(s,)) for s in range(senders)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
            self.assertFalse(thread.is_alive())

        chunks = _chunks(sock.packets)
        sequence = [seq for _t, _f, seq, _r, _b in chunks]
        self.assertEqual(sequence, list(range(1, len(chunks) + 1)), "sequence numbers on the wire")

        # Each message's chunks are contiguous: C...C F with one request id.
        reassembled: dict[int, bytes] = {}
        current: int | None = None
        buf = bytearray()
        for _t, flag, _seq, request_id, body in chunks:
            if current is None:
                current = request_id
            self.assertEqual(request_id, current, "a chunk of another message was interleaved")
            buf += body
            if flag == b"F":
                reassembled[current] = bytes(buf)
                buf.clear()
                current = None
        self.assertIsNone(current)
        self.assertEqual(reassembled, bodies)

    def test_single_chunk_sequence_numbers_follow_send_order(self):
        conn, sock = self._connection(peer_recv=65536)

        def send(sender: int) -> None:
            for n in range(50):
                conn.send_body(b"MSG", b"x" * 10, sender * 1000 + n + 1)

        threads = [threading.Thread(target=send, args=(s,)) for s in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        sequence = [seq for _t, _f, seq, _r, _b in _chunks(sock.packets)]
        self.assertEqual(sequence, list(range(1, 201)))


class _Space:
    """Frozen point space: values never change, so a lost notification is never re-sent."""

    def __init__(self) -> None:
        from formula import BenchConfig

        self.config = BenchConfig(points=10, percent_changing=100, period_ms=1000, frozen=True, now_ms=2000)
        self.opcua_reads = 0

    def now_ms(self) -> int:
        return 2000


class _Client:
    """Just enough of a binary client to drive sessions, subscriptions and Publish."""

    def __init__(self, port: int) -> None:
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.request_id = 0
        self.token = (0, 0)
        hello = opcua.W()
        for v in (0, 1 << 20, 1 << 20, 0, 0):
            hello.u32(v)
        hello.string(f"opc.tcp://127.0.0.1:{port}")
        packet = bytearray(b"HELF\x00\x00\x00\x00" + bytes(hello.b))
        struct.pack_into("<I", packet, 4, len(packet))
        self.sock.sendall(packet)
        assert self._recv()[0] == b"ACK"
        w = self._begin(opcua.ENC_OPEN_SECURE_CHANNEL_REQ)
        for v in (0, 0, 1):
            w.u32(v)
        w.bstr(b"\x00" * 32)
        w.u32(60_000)
        self._call(w, b"OPN")

    def close(self) -> None:
        self.sock.close()

    def _begin(self, encoding: int) -> opcua.W:
        w = opcua.W()
        w.nodeid(0, encoding)
        w.nodeid(*self.token)
        w.i64(opcua.now_ticks())
        w.u32(self.request_id + 1)
        w.u32(0)
        w.string(None)
        w.u32(5000)
        w.ext_null()
        return w

    def _recv(self) -> tuple[bytes, bytes]:
        body = bytearray()
        while True:
            header = self._exact(8)
            size = struct.unpack_from("<I", header, 4)[0]
            rest = self._exact(size - 8)
            if header[0:3] == b"ACK":
                return b"ACK", rest
            r = opcua.R(rest)
            r.u32()
            if header[0:3] == b"OPN":
                r.string(); r.bstr(); r.bstr()
            else:
                r.u32()
            r.u32(); r.u32()
            body += rest[r.i :]
            if header[3:4] == b"F":
                return header[0:3], bytes(body)

    def _exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return bytes(buf)

    def _call(self, w: opcua.W, msg_type: bytes = b"MSG") -> tuple[int, int, opcua.R]:
        """Returns (response encoding id, service result, reader after the header)."""
        self.request_id += 1
        packet = bytearray(msg_type + b"F\x00\x00\x00\x00")
        packet += struct.pack("<I", 1)
        packet += opcua.ASYM if msg_type == b"OPN" else struct.pack("<I", 1)
        packet += struct.pack("<II", self.request_id, self.request_id)
        packet += bytes(w.b)
        struct.pack_into("<I", packet, 4, len(packet))
        self.sock.sendall(packet)
        _typ, body = self._recv()
        r = opcua.R(body)
        _ns, encoding = r.nodeid()
        r.i64(); r.u32()
        status = r.u32()
        r.u8()
        for _ in range(max(0, r.i32())):
            r.string()
        r.extension()
        return encoding, status, r

    def create_session(self) -> tuple[int, int]:
        w = self._begin(opcua.ENC_CREATE_SESSION_REQ)
        w.string("urn:test"); w.string("urn:test"); w.ltext("test"); w.u32(1)
        w.string(None); w.string(None); w.i32(0)
        w.string(None); w.string("opc.tcp://127.0.0.1"); w.string("test")
        w.bstr(b"\x01" * 32); w.bstr(None); w.f64(60_000); w.u32(0)
        _enc, status, r = self._call(w)
        assert status == 0, hex(status)
        r.nodeid()
        return r.nodeid()

    def activate(self, token: tuple[int, int]) -> int:
        self.token = token
        w = self._begin(opcua.ENC_ACTIVATE_SESSION_REQ)
        w.string(None); w.bstr(None); w.i32(0); w.i32(0)
        anon = opcua.W()
        anon.string("anonymous")
        w.ext_body(0, opcua.ENC_ANONYMOUS_TOKEN, bytes(anon.b))
        w.string(None); w.bstr(None)
        return self._call(w)[1]

    def subscribe(self, points: int) -> int:
        w = self._begin(opcua.ENC_CREATE_SUBSCRIPTION_REQ)
        w.f64(50); w.u32(300); w.u32(1); w.u32(0); w.boolean(True); w.u8(0)
        _enc, status, r = self._call(w)
        assert status == 0, hex(status)
        sub_id = r.u32()
        w = self._begin(opcua.ENC_CREATE_MONITORED_ITEMS_REQ)
        w.u32(sub_id); w.u32(2); w.i32(points)
        for index in range(points):
            w.nodeid(1, OPC_NODE_BASE + index)
            w.u32(opcua.ATTR_VALUE); w.string(None); w.qname(0, None)
            w.u32(2); w.u32(100 + index); w.f64(0); w.ext_null(); w.u32(1); w.boolean(True)
        assert self._call(w)[1] == 0
        return sub_id

    def publish(self, acks: list[tuple[int, int]] = ()) -> tuple[int, list[int], int, list[int], list[int]]:
        """(subscription id, available sequence numbers, message sequence number,
        notified client handles, ack results)."""
        w = self._begin(opcua.ENC_PUBLISH_REQ)
        w.i32(len(acks))
        for sub_id, seq in acks:
            w.u32(sub_id); w.u32(seq)
        _enc, status, r = self._call(w)
        assert status == 0, hex(status)
        sub_id = r.u32()
        available = r.u32_array()
        r.boolean()
        seq, handles = _notification(r)
        return sub_id, available, seq, handles, r.status_array()

    def republish(self, sub_id: int, seq: int) -> tuple[int, list[int]]:
        w = self._begin(opcua.ENC_REPUBLISH_REQ)
        w.u32(sub_id); w.u32(seq)
        _enc, status, r = self._call(w)
        if status != 0:
            return status, []
        got, handles = _notification(r)
        assert got == seq
        return status, handles

    def transfer(self, ids: list[int]) -> list[int]:
        w = self._begin(opcua.ENC_TRANSFER_SUBSCRIPTIONS_REQ)
        w.i32(len(ids))
        for sub_id in ids:
            w.u32(sub_id)
        w.boolean(True)
        _enc, status, r = self._call(w)
        assert status == 0, hex(status)
        results = []
        for _ in range(r.i32()):
            results.append(r.u32())
            r.u32_array()
        return results


def _notification(r) -> tuple[int, list[int]]:
    seq = r.u32()
    r.i64()
    handles = []
    for _ in range(max(0, r.i32())):
        _type, body = r.extension()
        if body is None:
            continue
        inner = opcua.R(body)
        for _ in range(max(0, inner.i32())):
            handles.append(inner.u32())
            mask = inner.u8()
            if mask & 1:
                inner.variant()
            if mask & 2:
                inner.u32()
            if mask & 4:
                inner.i64()
            if mask & 8:
                inner.i64()
    return seq, handles


class SessionRecoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = opcua.OpcUaServer(_Space())
        self.port = self.server.start("127.0.0.1", 0)
        self.clients: list[_Client] = []

    def tearDown(self) -> None:
        for client in self.clients:
            client.close()
        self.server.stop()

    def _client(self) -> _Client:
        client = _Client(self.port)
        self.clients.append(client)
        return client

    def test_a_session_survives_a_lost_channel_and_republish_returns_the_lost_message(self):
        first = self._client()
        token = first.create_session()
        self.assertEqual(first.activate(token), 0)
        sub_id = first.subscribe(3)
        got_sub, available, seq, handles, _acks = first.publish()
        self.assertEqual(got_sub, sub_id)
        self.assertEqual(sorted(handles), [100, 101, 102])
        self.assertEqual(available, [seq])
        # The channel drops; the client never saw that response (or never acked it).
        first.close()

        second = self._client()
        self.assertEqual(second.activate(token), 0, "the session belongs to the server, not the TCP connection")
        status, again = second.republish(sub_id, seq)
        self.assertEqual(status, 0)
        self.assertEqual(sorted(again), [100, 101, 102])
        got_sub, available, _seq, handles, acks = second.publish([(sub_id, seq)])
        self.assertEqual(got_sub, sub_id, "the subscription is still alive on the new channel")
        self.assertEqual(handles, [])
        self.assertEqual(acks, [0])
        self.assertEqual(available, [])
        self.assertEqual(second.republish(sub_id, seq)[0], opcua.BAD_MESSAGE_NOT_AVAILABLE)

    def test_transfer_subscriptions_moves_a_subscription_to_a_new_session(self):
        first = self._client()
        old = first.create_session()
        self.assertEqual(first.activate(old), 0)
        sub_id = first.subscribe(2)
        first.publish()
        first.close()

        second = self._client()
        new = second.create_session()
        self.assertEqual(second.activate(new), 0)
        self.assertEqual(second.transfer([sub_id, 999_999]), [0, opcua.BAD_SUBSCRIPTION_ID_INVALID])
        got_sub, _available, _seq, handles, _acks = second.publish()
        self.assertEqual(got_sub, sub_id)
        self.assertEqual(sorted(handles), [100, 101], "sendInitialValues re-sends every item")

    def test_an_unknown_session_token_is_refused(self):
        client = self._client()
        self.assertEqual(client.activate((1, 424242)), opcua.BAD_SESSION_ID_INVALID)

    def test_acknowledging_an_unknown_sequence_number_reports_it(self):
        client = self._client()
        token = client.create_session()
        client.activate(token)
        sub_id = client.subscribe(1)
        _sub, _available, seq, _handles, _acks = client.publish()
        _sub, _available, _seq, _handles, acks = client.publish([(sub_id, seq), (sub_id, seq + 50)])
        self.assertEqual(acks, [0, opcua.BAD_SEQUENCE_NUMBER_UNKNOWN])


if __name__ == "__main__":
    unittest.main()
