"""High-point simulator. One process, one image.

Modbus TCP, OPC UA, and MQTT (plain topics and Sparkplug B) serve the same
address space. Point count, change percent, period, and value pattern are
configurable. The process reports its own CPU and whether a scan missed the
period.

The functional compose stack is not this process. Nothing here is wired to
real equipment.

    python server.py                  # 150,000 points, 10% changing, scan pattern
    python server.py --pace           # PRF-004 and PRF-005 change rates must finish inside 1 s
    python server.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from broker import Broker
from model import (
    PATTERNS,
    VALUE_MOD,
    BenchConfig,
    CpuMeter,
    LoopbackRefused,
    PointSpace,
    changing_indexes,
    raw_at,
    require_bind,
    tick_at,
)
from modbus import ModbusServer
from opcua import OpcUaServer
from publish import Publisher, attach_drain


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return float(raw)


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


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
        publisher = self.server.publisher
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            cfg = space.config
            self._json(200, {
                "ok": True,
                "points": cfg.points,
                "percentChanging": cfg.percent_changing,
                "periodMs": cfg.period_ms,
                "pattern": cfg.pattern,
                "frozen": cfg.frozen,
                "nowMs": space.now_ms(),
                "registersPerUnit": cfg.registers_per_unit,
                "metricsPerDevice": cfg.metrics_per_device,
                "cpuPercent": round(self.server.cpu.percent(), 4),
                "lastScanMs": round(publisher.last_scan_ms, 3),
                "maxScanMs": round(publisher.max_scan_ms, 3),
                "behindScans": publisher.behind,
                "scans": publisher.scans,
                "protocols": ["modbus", "opcua", "mqtt"],
            })
            return
        if parsed.path == "/expected":
            query = parse_qs(parsed.query)
            try:
                index = int(query.get("index", ["0"])[0])
                cfg = space.config
                now = space.now_ms()
                self._json(200, {
                    "index": index,
                    "raw": raw_at(index, now, cfg),
                    "nowMs": now,
                    "tick": tick_at(now, cfg.epoch_ms, cfg.period_ms),
                })
            except (IndexError, ValueError) as exc:
                self._json(400, {"error": str(exc)})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if urlparse(self.path).path != "/control":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("content-length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode() or "{}")
            cfg = apply_control(self.server.space, self.server.publisher, body)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
            return
        self._json(200, {
            "ok": True,
            "points": cfg.points,
            "percentChanging": cfg.percent_changing,
            "periodMs": cfg.period_ms,
            "pattern": cfg.pattern,
            "frozen": cfg.frozen,
        })


class Http(ThreadingHTTPServer):
    def __init__(self, space: PointSpace, publisher: Publisher, cpu: CpuMeter, bind: str, port: int) -> None:
        self.space = space
        self.publisher = publisher
        self.cpu = cpu
        super().__init__((bind, port), Handler)


def apply_control(space: PointSpace, publisher: Publisher, body: dict) -> BenchConfig:
    changes: dict = {}
    if "points" in body:
        changes["points"] = int(body["points"])
    if "percentChanging" in body:
        changes["percent_changing"] = float(body["percentChanging"])
    if "periodMs" in body:
        changes["period_ms"] = int(body["periodMs"])
    if "pattern" in body:
        changes["pattern"] = str(body["pattern"])
    if "frozen" in body:
        changes["frozen"] = bool(body["frozen"])
    if "nowMs" in body:
        changes["now_ms"] = int(body["nowMs"])
    if "epochMs" in body:
        changes["epoch_ms"] = int(body["epochMs"])
    cfg = space.update(**changes)
    publisher.birth()
    return cfg


def self_test() -> int:
    try:
        require_bind("127.0.0.1")
    except LoopbackRefused:
        pass
    else:
        print("SELFTEST FAIL loopback bind was accepted", file=sys.stderr)
        return 1
    for host in ("localhost", "::1", "127.0.0.2"):
        try:
            require_bind(host)
        except LoopbackRefused:
            continue
        print(f"SELFTEST FAIL bind accepted {host}", file=sys.stderr)
        return 1
    require_bind("0.0.0.0")
    cfg = BenchConfig(points=150_000, percent_changing=10, period_ms=1000, pattern="scan").validated()
    if len(changing_indexes(cfg)) != 15_000:
        print("SELFTEST FAIL 10% of 150000", file=sys.stderr)
        return 1
    cfg6 = BenchConfig(points=150_000, percent_changing=6, pattern="scan").validated()
    if len(changing_indexes(cfg6)) != 9_000:
        print("SELFTEST FAIL 6% of 150000", file=sys.stderr)
        return 1
    cfg4 = BenchConfig(points=100_000, percent_changing=10, pattern="scan").validated()
    if len(changing_indexes(cfg4)) != 10_000:
        print("SELFTEST FAIL 10% of 100000", file=sys.stderr)
        return 1
    now = 5_000
    if raw_at(0, now, cfg) != 5 or raw_at(50, now, cfg) != 50 or raw_at(9, now, cfg) != 14:
        print("SELFTEST FAIL scan formula", file=sys.stderr)
        return 1
    step = BenchConfig(points=100, percent_changing=10, pattern="step").validated()
    if raw_at(0, 5_000, step) != 1000 or raw_at(0, 4_000, step) != 0 or raw_at(50, 5_000, step) != 50:
        print("SELFTEST FAIL step", file=sys.stderr)
        return 1
    ramp = BenchConfig(points=100, percent_changing=100, pattern="ramp").validated()
    if raw_at(5, 5_000, ramp) != 10:
        print("SELFTEST FAIL ramp", file=sys.stderr)
        return 1
    sine = BenchConfig(points=100, percent_changing=100, pattern="sine").validated()
    if raw_at(0, 0, sine) != 15_000 or raw_at(0, 3_000, sine) != 16_000:
        print("SELFTEST FAIL sine", file=sys.stderr)
        return 1
    const = BenchConfig(points=100, percent_changing=100, pattern="constant").validated()
    if raw_at(7, 5_000, const) != 7 or changing_indexes(const):
        print("SELFTEST FAIL constant", file=sys.stderr)
        return 1
    rnd = BenchConfig(points=100, percent_changing=100, pattern="random").validated()
    if raw_at(0, 0, rnd) != raw_at(0, 0, rnd) or raw_at(0, 0, rnd) == raw_at(0, 1_000, rnd):
        print("SELFTEST FAIL random", file=sys.stderr)
        return 1
    if raw_at(0, 0, rnd) >= VALUE_MOD:
        print("SELFTEST FAIL random range", file=sys.stderr)
        return 1
    if set(PATTERNS) != {"scan", "constant", "ramp", "step", "sine", "random"}:
        print("SELFTEST FAIL patterns", file=sys.stderr)
        return 1
    if alignment_test() != 0:
        return 1
    print("SELFTEST OK", flush=True)
    return 0


def alignment_test() -> int:
    """MQTT has to publish on the wall-clock tick, not a free-running timer.

    Modbus and OPC UA compute the value from ``now_ms`` on every read, so they
    change at ``epoch + n * period``. This starts the scan in the last tenth of
    a period. A monotonic loop begun there stays late: for most of the next
    period the live protocols show the new tick and MQTT still shows the old
    one. Later publishes have to land just after each boundary, and a read in
    the middle of the tick has to see that tick's value.
    """
    period_ms = 200
    cfg = BenchConfig(
        points=32,
        percent_changing=10,
        period_ms=period_ms,
        pattern="scan",
        epoch_ms=0,
    ).validated()
    space = PointSpace(cfg)
    broker = Broker()
    broker.start("127.0.0.1", 0)
    seen: list[tuple[int, int]] = []
    lock = threading.Lock()
    original = broker.publish_many

    def capture(items: list[tuple[str, bytes, bool]]) -> None:
        now = int(time.time() * 1000)
        original(items)
        for topic, payload, _retain in items:
            if topic == "oip/perf/p0000000":
                with lock:
                    seen.append((now, int(payload.decode())))

    broker.publish_many = capture  # type: ignore[method-assign]
    publisher: Publisher | None = None
    try:
        late_floor = int(period_ms * 0.85)
        for _attempt in range(8):
            with lock:
                seen.clear()
            while int(time.time() * 1000) % period_ms < int(period_ms * 0.90):
                time.sleep(0.0005)
            publisher = Publisher(space, broker, hooks=False)
            publisher.start()
            time.sleep(0.015)
            with lock:
                first = seen[0][0] if seen else None
            if first is not None and first % period_ms >= late_floor:
                break
            publisher.stop()
            publisher = None
        if publisher is None:
            print("SELFTEST FAIL alignment could not start late in the period", file=sys.stderr)
            return 1
        mismatches: list[tuple[int, int, int]] = []
        samples = 0
        deadline = time.time() + 1.25
        while time.time() < deadline:
            time.sleep(0.01)
            now = int(time.time() * 1000)
            offset = now % period_ms
            # The first 50 ms belong to the publish. The rest of the tick must match.
            if offset < 50:
                continue
            with lock:
                if not seen:
                    continue
                latest = seen[-1][1]
            samples += 1
            expect = raw_at(0, now, cfg)
            if latest != expect:
                if len(mismatches) < 6:
                    mismatches.append((offset, latest, expect))
        with lock:
            rows = list(seen)
    finally:
        if publisher is not None:
            publisher.stop()
        broker.stop()
    by_tick: dict[int, tuple[int, int]] = {}
    for ms, value in rows:
        by_tick.setdefault(ms // period_ms, (ms, value))
    ticks = sorted(by_tick)
    if len(ticks) < 5:
        print(f"SELFTEST FAIL alignment ticks={ticks}", file=sys.stderr)
        return 1
    offsets: list[int] = []
    for tick in ticks[1:]:
        ms, value = by_tick[tick]
        offset = ms - tick * period_ms
        expect = raw_at(0, ms, cfg)
        if value != expect:
            print(
                f"SELFTEST FAIL alignment published tick {tick} value {value} "
                f"at offset {offset} ms, live value {expect}",
                file=sys.stderr,
            )
            return 1
        offsets.append(offset)
    close = [offset for offset in offsets if offset <= 80]
    if len(close) < 4:
        print(f"SELFTEST FAIL alignment publish offsetsMs={offsets}", file=sys.stderr)
        return 1
    if samples < 20 or mismatches:
        print(
            f"SELFTEST FAIL alignment held value samples={samples} mismatches={mismatches}",
            file=sys.stderr,
        )
        return 1
    return 0


def run_pace() -> int:
    """Publish the PRF-004 and PRF-005 change sets into a drain socket.

    The drain is inside this process. It is not a driver peer and it is not
    the networked check. A scan that takes the period or longer is behind.
    """
    scenarios = (("prf004", 100_000, 10.0), ("prf005", 150_000, 6.0))
    for name, points, percent in scenarios:
        cfg = BenchConfig(
            points=points,
            percent_changing=percent,
            period_ms=1000,
            pattern="scan",
        ).validated()
        space = PointSpace(cfg)
        broker = Broker()
        port = broker.start("127.0.0.1", 0)
        drain = attach_drain("127.0.0.1", port)
        publisher = Publisher(space, broker, hooks=False)
        try:
            publisher.scan_once(now_ms=0, force=True)
            meter = CpuMeter()
            worst = 0.0
            for n in range(1, 4):
                worst = max(worst, publisher.scan_once(now_ms=n * 1000, force=True))
            cpu = meter.percent()
            behind = 0 if worst < cfg.period_ms else 1
            print(
                f"PACE {name} points={points} percent={percent} periodMs={cfg.period_ms} "
                f"scans=3 maxScanMs={worst:.3f} cpuPercent={cpu:.4f} behind={behind}",
                flush=True,
            )
            if behind:
                print(f"PACE FAIL {name} fell behind", file=sys.stderr)
                return 1
        finally:
            try:
                drain.close()
            except OSError:
                pass
            broker.stop()
    print("PACE OK", flush=True)
    return 0


def serve(args: argparse.Namespace) -> int:
    if args.require_networked:
        require_bind(args.bind)
    cfg = BenchConfig(
        points=args.points,
        percent_changing=args.percent,
        period_ms=args.period_ms,
        epoch_ms=args.epoch_ms,
        frozen=args.frozen,
        now_ms=args.now_ms,
        registers_per_unit=args.registers_per_unit,
        metrics_per_device=args.metrics_per_device,
        pattern=args.pattern,
    ).validated()
    space = PointSpace(cfg)
    cpu = CpuMeter()
    broker = Broker()
    modbus = ModbusServer(space)
    opc = OpcUaServer(space)
    publisher = Publisher(space, broker)
    mqtt_port = broker.start(args.bind, args.mqtt_port)
    modbus_port = modbus.start(args.bind, args.modbus_port)
    opc_port = opc.start(args.bind, args.opcua_port)
    http = Http(space, publisher, cpu, args.bind, args.http_port)
    http_port = http.server_address[1]
    threading.Thread(target=http.serve_forever, name="http", daemon=True).start()
    publisher.start()
    print(
        f"READY points={cfg.points} percent={cfg.percent_changing} periodMs={cfg.period_ms} "
        f"pattern={cfg.pattern} frozen={cfg.frozen} bind={args.bind} "
        f"modbus={modbus_port} opcua={opc_port} mqtt={mqtt_port} http={http_port}",
        flush=True,
    )
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        return 0
    finally:
        publisher.stop()
        modbus.stop()
        opc.stop()
        broker.stop()
        http.shutdown()


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PRF-012 high-point simulator")
    parser.add_argument("--points", type=int, default=_env_int("POINTS", 150_000))
    parser.add_argument("--percent", type=float, default=_env_float("PERCENT_CHANGING", 10))
    parser.add_argument("--period-ms", type=int, default=_env_int("PERIOD_MS", 1000))
    parser.add_argument("--pattern", default=os.environ.get("PATTERN") or "scan")
    parser.add_argument("--epoch-ms", type=int, default=_env_int("EPOCH_MS", 0))
    parser.add_argument("--now-ms", type=int, default=_env_int("NOW_MS", 0))
    parser.add_argument("--registers-per-unit", type=int, default=_env_int("REGISTERS_PER_UNIT", 10_000))
    parser.add_argument("--metrics-per-device", type=int, default=_env_int("METRICS_PER_DEVICE", 500))
    parser.add_argument("--bind", default=os.environ.get("BIND") or "0.0.0.0")
    parser.add_argument("--modbus-port", type=int, default=_env_int("MODBUS_PORT", 5020))
    parser.add_argument("--opcua-port", type=int, default=_env_int("OPCUA_PORT", 4840))
    parser.add_argument("--mqtt-port", type=int, default=_env_int("MQTT_PORT", 1883))
    parser.add_argument("--http-port", type=int, default=_env_int("HTTP_PORT", 8080))
    parser.add_argument("--frozen", action="store_true", default=_env_bool("FROZEN", False))
    parser.add_argument("--allow-loopback", action="store_true")
    parser.add_argument("--pace", action="store_true", help="Prove the PRF-004 and PRF-005 change rates keep up")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    args.require_networked = not args.allow_loopback
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.self_test:
            return self_test()
        if args.pace:
            return run_pace()
        return serve(args)
    except LoopbackRefused as exc:
        print(f"FAIL {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
