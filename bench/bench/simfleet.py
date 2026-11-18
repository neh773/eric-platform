"""Simulator processes for a scaling run.

One CPython process stays near one core under the GIL, so a single container
with ``--cpus 4`` still saturates at one core and the gateway cutoff is the
simulator. Scaling starts one process per planned core. Our gateway shards
Modbus units across those ports. the reference platform's driver is one device per host IP,
so a local fanout on the published port spreads connections across the same
processes.
"""

from __future__ import annotations

import socket
import threading
from dataclasses import dataclass


SIM_IMAGE = "oip-bench-points:local"


@dataclass(frozen=True)
class SimHost:
    """One machine that can run simulator processes.

    ``address`` is where the harness opens the control port. ``gateway_address``
    is what the gateway dials, when that differs (a container reaches the host
    through the bridge). ``docker_host`` is a ``docker -H`` target such as
    ``ssh://bench@10.1.0.20`` for a second machine. Empty means the local docker.
    """

    id: str
    address: str
    slots: int
    gateway_address: str
    docker_host: str | None = None


def load_sim_hosts(data: dict) -> tuple[list[SimHost], dict]:
    """Parse a sim-host config. ``pointsPerVcpu`` above the saturation budget is refused."""
    from bench.scaling import POINTS_PER_SIM_VCPU

    hosts = []
    for item in data.get("hosts") or []:
        if not isinstance(item, dict):
            raise ValueError("each simulator host must be an object")
        slots = int(item.get("slots") or 0)
        if slots < 1:
            raise ValueError("simulator host slots must be >= 1")
        address = str(item.get("address") or "").strip()
        if not address:
            raise ValueError("simulator host address is required")
        gateway = str(item.get("gatewayAddress") or address).strip()
        docker_host = item.get("dockerHost")
        docker_host = None if docker_host in (None, "") else str(docker_host)
        hosts.append(SimHost(
            id=str(item.get("id") or address),
            address=address,
            slots=slots,
            gateway_address=gateway,
            docker_host=docker_host,
        ))
    if not hosts:
        raise ValueError("sim host config needs at least one host")
    headroom = float(data.get("headroom", 2.0))
    if headroom < 1:
        raise ValueError("headroom must be >= 1")
    points_per_vcpu = int(data.get("pointsPerVcpu", POINTS_PER_SIM_VCPU))
    if points_per_vcpu > POINTS_PER_SIM_VCPU:
        raise ValueError(
            f"pointsPerVcpu {points_per_vcpu} is above {POINTS_PER_SIM_VCPU}, "
            "which would shrink the fleet below the budget that stayed under the 80% flag"
        )
    if points_per_vcpu < 1:
        raise ValueError("pointsPerVcpu must be positive")
    return hosts, {"headroom": headroom, "pointsPerVcpu": points_per_vcpu}


def place_simulators(
    instances: int,
    hosts: list[SimHost],
    *,
    modbus_base: int = 25020,
    http_base: int = 28080,
    reference: bool = False,
) -> list[dict]:
    """Spread ``instances`` across hosts in order. Overflow goes to the next host.

    the reference platform publishes one Modbus port per host IP. Those sims move off
    ``modbus_base`` so a fanout can listen there. A shortfall names how many
    slots are missing instead of packing past a host.
    """
    if instances < 1:
        raise ValueError("at least one simulator")
    if not hosts:
        raise ValueError("at least one simulator host")
    capacity = sum(host.slots for host in hosts)
    if instances > capacity:
        raise ValueError(
            f"simulator fleet needs {instances} processes but the configured hosts "
            f"only have {capacity} slots. Add capacity on the second host."
        )
    placements = []
    remaining = instances
    index = 0
    for host in hosts:
        take = min(host.slots, remaining)
        for _local in range(take):
            if reference:
                modbus_port = modbus_base + 1000 + index
            else:
                modbus_port = modbus_base + index
            placements.append({
                "name": f"oip-bench-sim-{index}",
                "index": index,
                "hostId": host.id,
                "address": host.address,
                "gatewayAddress": host.gateway_address,
                "dockerHost": host.docker_host,
                "modbusPort": modbus_port,
                "httpPort": http_base + index,
            })
            index += 1
            remaining -= 1
        if remaining == 0:
            break
    return placements


def sim_container_command(
    *,
    name: str,
    image: str,
    cpus: float,
    memory: str,
    host_modbus_port: int,
    host_http_port: int,
    points: int,
    docker_host: str | None = None,
) -> list[str]:
    """``docker run`` for one simulator. Sparkplug births are off; this ramp is Modbus.

    ``docker_host`` is passed as ``docker -H``, which is how a second machine
    runs its share of the fleet. The local docker is used when it is omitted.
    """
    if cpus <= 0:
        raise ValueError("cpus must be > 0")
    if points < 1:
        raise ValueError("points must be positive")
    command = ["docker"]
    if docker_host:
        command += ["-H", docker_host]
    command += [
        "run", "-d", "--name", name,
        "--cpus", str(int(cpus) if float(cpus).is_integer() else cpus),
        "--memory", memory,
        "-p", f"{host_modbus_port}:5020",
        "-p", f"{host_http_port}:8080",
        "-e", f"POINTS={points}",
        "-e", "PERCENT_CHANGING=100",
        "-e", "PERIOD_MS=1000",
        "-e", "MODBUS_PORT=5020",
        "-e", "MODBUS_EXTRA_PORTS=0",
        "-e", "HTTP_PORT=8080",
        image,
        "python", "-u", "/app/server.py", "--no-sparkplug",
    ]
    return command


def fleet_layout(
    instances: int,
    *,
    modbus_base: int = 25020,
    http_base: int = 28080,
    reference: bool = False,
) -> list[dict]:
    """Host ports for each simulator.

    the reference platform publishes one Modbus port. Those sims move off ``modbus_base`` so a
    fanout can listen there and the gateway still dials a single address.
    """
    if instances < 1:
        raise ValueError("at least one simulator")
    rows = []
    for index in range(instances):
        if reference:
            modbus_port = modbus_base + 1000 + index
        else:
            modbus_port = modbus_base + index
        rows.append({
            "name": f"oip-bench-sim-{index}",
            "index": index,
            "modbusPort": modbus_port,
            "httpPort": http_base + index,
        })
    return rows


class ConnectionFanout:
    """Round-robin TCP proxy. Each accepted connection is pinned to one backend."""

    def __init__(self, host: str, port: int, backends: list[tuple[str, int]]) -> None:
        if not backends:
            raise ValueError("fanout needs a backend")
        self.host = host
        self.port = port
        self.backends = list(backends)
        self._stop = threading.Event()
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._rr = 0
        self.accepted = 0

    def start(self) -> int:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.host, self.port))
        sock.listen(128)
        sock.settimeout(0.2)
        self._sock = sock
        self.port = sock.getsockname()[1]
        self._thread = threading.Thread(target=self._accept, name="sim-fanout", daemon=True)
        self._thread.start()
        return self.port

    def stop(self) -> None:
        self._stop.set()
        sock = self._sock
        self._sock = None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        thread = self._thread
        if thread is not None:
            thread.join(timeout=2)

    def _accept(self) -> None:
        while not self._stop.is_set():
            sock = self._sock
            if sock is None:
                return
            try:
                client, _addr = sock.accept()
            except (socket.timeout, OSError):
                continue
            backend = self.backends[self._rr % len(self.backends)]
            self._rr += 1
            self.accepted += 1
            threading.Thread(target=_bridge, args=(client, backend), name="sim-bridge", daemon=True).start()


def _bridge(client: socket.socket, backend: tuple[str, int]) -> None:
    remote = None
    try:
        remote = socket.create_connection(backend, timeout=5)
        remote.settimeout(1.0)
        client.settimeout(1.0)
        threads = [
            threading.Thread(target=_copy, args=(client, remote), daemon=True),
            threading.Thread(target=_copy, args=(remote, client), daemon=True),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
    except OSError:
        pass
    finally:
        for sock in (client, remote):
            if sock is None:
                continue
            try:
                sock.close()
            except OSError:
                pass


def _copy(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            try:
                chunk = src.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        return
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass
