"""PRF-012 check: the bench point server on a non-loopback address.

The same process serves Modbus TCP, OPC UA and MQTT/Sparkplug. Point count,
scan period and change fraction come from test-infra/perf/prf-012.json.
`python3 -m bench prf012` serves fullPoints. `--ci` serves the smoke size.
"""

from __future__ import annotations

import json
import os
import socket
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
CONFIG_PATH = REPO / "test-infra" / "perf" / "prf-012.json"
MIN_POINTS = 150_000
FROZEN_NOW_MS = 5_000


def load_config(path: Path = CONFIG_PATH) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if int(data["fullPoints"]) < MIN_POINTS:
        raise ValueError(f"fullPoints {data['fullPoints']} is below {MIN_POINTS}")
    if data.get("loopbackAllowed") is not False:
        raise ValueError("loopbackAllowed must be false")
    return data


def resolve_points(config: dict, *, points: int | None, ci: bool) -> int:
    """`--points` wins, then OIP_PRF012_POINTS, then ciPoints, otherwise fullPoints."""
    if points is not None:
        return int(points)
    env = os.environ.get("OIP_PRF012_POINTS")
    if env:
        return int(env)
    if ci:
        return int(config["ciPoints"])
    return int(config["fullPoints"])


def sample_indexes(points: int) -> list[int]:
    if points < 1:
        raise ValueError("points must be positive")
    picked = [0]
    if points > 2:
        picked.append(points // 2)
    if points > 1:
        picked.append(points - 1)
    unique: list[int] = []
    for index in picked:
        if index not in unique:
            unique.append(index)
    return unique


def refusal_probe() -> None:
    """The driver helper refuses 127.0.0.1 before a listening socket accepts."""
    _sim_path()
    import network

    for host in ("127.0.0.1", "localhost", "::1", "127.0.0.2", "0.0.0.0"):
        try:
            network.require_driver_target(host)
        except network.LoopbackRefused:
            continue
        raise AssertionError(f"driver accepted a loopback target {host!r}")
    try:
        network.networked_bind("127.0.0.1")
    except network.LoopbackRefused:
        pass
    else:
        raise AssertionError("loopback bind was accepted")

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(0.2)
        port = listener.getsockname()[1]
        try:
            network.driver_connect("127.0.0.1", port, timeout=1)
        except network.LoopbackRefused:
            pass
        else:
            raise AssertionError("driver_connect opened a loopback socket")
        try:
            listener.accept()
        except socket.timeout:
            pass
        else:
            raise AssertionError("a driver connection reached the loopback listener")
    finally:
        listener.close()


def prove(points: int, *, percent: float | None = None) -> dict:
    """Serve [points] on all three protocols and read the ends of the map.

    The process binds a non-loopback interface. Reads go through the driver
    helper, which refuses a loopback target.
    """
    config = load_config()
    if points < 1:
        raise ValueError("points must be positive")
    refusal_probe()
    _sim_path()
    # server.py puts bench/bench (formula.py) on sys.path. formula is not under sim/.
    import server
    import formula
    import network

    fraction = float(config["percentChanging"] if percent is None else percent)
    bench_cfg = formula.BenchConfig(
        points=points,
        percent_changing=fraction,
        period_ms=int(config["periodMs"]),
        epoch_ms=0,
        frozen=True,
        now_ms=FROZEN_NOW_MS,
        registers_per_unit=int(config["registersPerUnit"]),
        metrics_per_device=int(config["metricsPerDevice"]),
    ).validated()
    running = server.serve(
        bench_cfg,
        bind="0.0.0.0",
        require_networked=True,
        modbus_ports=[0],
        opcua_port=0,
        mqtt_port=0,
        http_port=0,
    )
    try:
        devices = (points + bench_cfg.metrics_per_device - 1) // bench_cfg.metrics_per_device
        server._wait_births(running, timeout=max(30.0, points / 20_000))
        host = running.bind
        network.require_driver_target(host)
        _assert_loopback_closed(running.modbus.ports[0])
        http_port = running.http.server_address[1]
        health = _http_json(host, http_port, "/health")
        if int(health["points"]) != points:
            raise RuntimeError(f"health points {health['points']} != {points}")
        indexes = sample_indexes(points)
        values: dict[int, int] = {}
        for index in indexes:
            located = formula.address(index, bench_cfg)
            expected = formula.raw_at(index, FROZEN_NOW_MS, bench_cfg)
            server._modbus_check(
                running.modbus.ports[0], located.unit_id, located.pdu, expected,
                host=host, networked=True,
            )
            got = server._opcua_read_point(
                running.opcua.port, bench_cfg, index=index, host=host, networked=True,
            )
            if got != expected:
                raise RuntimeError(f"opcua index {index} value {got} != {expected}")
            shown = _http_json(host, http_port, f"/expected?index={index}")
            if int(shown["raw"]) != expected:
                raise RuntimeError(f"http index {index} raw {shown['raw']} != {expected}")
            values[index] = expected
        births = server._sparkplug_births(
            running.broker.port, host=host, networked=True, expect_devices=devices,
            timeout=max(30.0, points / 20_000),
        )
        metric_count = sum(len(metrics) for metrics in births.values())
        if len(births) != devices or metric_count != points:
            raise RuntimeError(f"mqtt births {len(births)} devices, {metric_count} metrics, expected {devices} and {points}")
        for index, expected in values.items():
            located = formula.address(index, bench_cfg)
            device = births.get(located.sparkplug_device)
            if device is None or device.get(located.metric) != expected:
                raise RuntimeError(f"mqtt index {index} missing or mismatched")
        print(
            f"PRF012 points={points} host={host} modbus=ok opcua=ok mqtt=ok",
            flush=True,
        )
        print("PRF012 OK", flush=True)
        return {
            "points": points,
            "host": host,
            "indexes": indexes,
            "values": values,
            "mqttMetrics": metric_count,
            "protocols": ["modbus", "opcua", "mqtt"],
        }
    finally:
        running.stop()


def main(args) -> int:
    try:
        config = load_config()
        points = resolve_points(config, points=getattr(args, "points", None), ci=bool(getattr(args, "ci", False)))
        prove(points)
        return 0
    except (OSError, ValueError, RuntimeError, TimeoutError, AssertionError, json.JSONDecodeError) as exc:
        print(f"PRF012 FAIL {exc}", file=sys.stderr)
        return 1


def _sim_path() -> None:
    sim = ROOT / "sim"
    if str(sim) not in sys.path:
        sys.path.insert(0, str(sim))


def _assert_loopback_closed(port: int) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.3)
    try:
        try:
            sock.connect(("127.0.0.1", port))
        except OSError:
            return
        raise RuntimeError("high-point simulator accepted a loopback connection")
    finally:
        sock.close()


def _http_json(host: str, port: int, path: str) -> dict:
    _sim_path()
    import network

    sock = network.driver_connect(host, port, timeout=5)
    try:
        request = f"GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode()
        sock.sendall(request)
        raw = b""
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            raw += chunk
    finally:
        sock.close()
    head, _, body = raw.partition(b"\r\n\r\n")
    status = head.split(b"\r\n", 1)[0]
    if b" 200 " not in status:
        raise RuntimeError(f"http {path} status {status!r}")
    return json.loads(body)
