"""Non-loopback addresses for the high-point simulator (PRF-012).

Driver code for this scenario calls [require_driver_target] before it opens a
socket. 127.0.0.1, localhost, and any other loopback address are refused.
The functional compose stack is a different process and is not checked here.
"""

from __future__ import annotations

import ipaddress
import os
import socket
import struct

try:
    import fcntl
except ImportError:  # pragma: no cover - CPython on Linux has fcntl
    fcntl = None

_SIOCGIFADDR = 0x8915
_WILDCARDS = {"", "0.0.0.0", "::", "*"}


class LoopbackRefused(ValueError):
    """A high-point driver target or bind address was loopback."""


def is_loopback_host(host: str) -> bool:
    """True for 127.0.0.0/8, ::1, localhost, and the wildcard addresses.

    Connecting to 0.0.0.0 on Linux reaches the local stack, so a wildcard is
    not a non-loopback driver target either.
    """
    name = _normalize(host)
    if name in _WILDCARDS or name in {"localhost", "::1"} or name.endswith(".localhost"):
        return True
    try:
        return _ip_is_loopback(ipaddress.ip_address(name))
    except ValueError:
        pass
    try:
        infos = socket.getaddrinfo(name, None)
    except socket.gaierror:
        return False
    return bool(infos) and any(_ip_is_loopback(item[4][0]) for item in infos)


def require_driver_target(host: str) -> str:
    """Return [host], or raise [LoopbackRefused] before any socket is opened."""
    if is_loopback_host(host):
        raise LoopbackRefused(
            "high-point driver target is loopback; PRF-012 requires a non-loopback address"
        )
    return host


def driver_connect(host: str, port: int, timeout: float = 5.0) -> socket.socket:
    """Connect as a driver. Loopback is refused and no socket is opened."""
    target = require_driver_target(host)
    return socket.create_connection((target, port), timeout)


def networked_bind(bind: str) -> str:
    """Address the PRF-012 process listens on.

    A wildcard is replaced with a unicast IPv4 on a non-loopback interface.
    A loopback bind is refused, so a client of 127.0.0.1 cannot reach this process.
    """
    name = _normalize(bind)
    if name in _WILDCARDS:
        return non_loopback_ipv4()
    if is_loopback_host(name):
        raise LoopbackRefused(
            "high-point simulator refuses a loopback bind; PRF-012 requires a non-loopback interface"
        )
    return name


def non_loopback_ipv4() -> str:
    """Prefer an interface that is up and is not a docker or veth bridge."""
    found = _interface_addresses()
    if not found:
        raise RuntimeError("no non-loopback IPv4 interface is available")
    found.sort(key=lambda row: (row[0], row[1]), reverse=True)
    return found[0][2]


def _normalize(host: str) -> str:
    name = (host or "").strip().lower()
    if name.startswith("[") and name.endswith("]"):
        name = name[1:-1]
    return name


def _ip_is_loopback(value: object) -> bool:
    text = str(value).split("%", 1)[0]
    try:
        ip = value if isinstance(value, (ipaddress.IPv4Address, ipaddress.IPv6Address)) else ipaddress.ip_address(text)
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return bool(ip.is_loopback)


def _interface_addresses() -> list[tuple[bool, bool, str]]:
    if fcntl is None or not os.path.isdir("/sys/class/net"):
        return []
    rows: list[tuple[bool, bool, str]] = []
    for name in sorted(os.listdir("/sys/class/net")):
        ip = _if_ipv4(name)
        if ip is None or _ip_is_loopback(ip):
            continue
        state = _read(f"/sys/class/net/{name}/operstate")
        bridge = name == "docker0" or name.startswith(("br-", "veth", "docker"))
        up = state == "up" and not bridge
        rows.append((up, not bridge, ip))
    return rows


def _if_ipv4(name: str) -> str | None:
    if fcntl is None:
        return None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        packed = fcntl.ioctl(sock.fileno(), _SIOCGIFADDR, struct.pack("256s", name.encode()[:15]))
    except OSError:
        return None
    finally:
        sock.close()
    return socket.inet_ntoa(packed[20:24])


def _read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""
