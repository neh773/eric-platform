"""Container CPU and RSS. This looks at docker stats, not at gateway files."""

from __future__ import annotations

import shutil
import subprocess

from bench.metrics import cpu_percent_of_quota, parse_bytes, parse_percent
from bench.targets import HostSample


def docker_available() -> bool:
    return shutil.which("docker") is not None and subprocess.run(
        ["docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def allocated_cpus(container: str) -> float:
    """NanoCpus is 1e9 per CPU. 0 means the container is not capped; use the host count."""
    try:
        out = subprocess.check_output(
            ["docker", "inspect", "-f", "{{.HostConfig.NanoCpus}}", container],
            text=True, stderr=subprocess.DEVNULL,
        ).strip()
        nano = int(out or "0")
    except (subprocess.CalledProcessError, ValueError):
        nano = 0
    if nano > 0:
        return nano / 1_000_000_000
    return host_cpus()


def host_cpus() -> float:
    try:
        out = subprocess.check_output(["nproc"], text=True).strip()
        return max(1.0, float(out))
    except (subprocess.CalledProcessError, ValueError):
        return 1.0


def container_gateway_ip(container: str) -> str | None:
    """Bridge address the container uses to reach published ports on the host."""
    try:
        out = subprocess.check_output(
            ["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}", container],
            text=True, stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return None
    for token in out.split():
        if token and token != "0.0.0.0":
            return token
    return None


def sample_container(container: str, allocated: float | None = None) -> HostSample:
    """One `docker stats` sample. CPU percent of quota uses the cgroup CPU limit."""
    proc = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}", container],
        text=True, capture_output=True,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        return HostSample()
    cpu_text, mem_text, mem_pct = (proc.stdout.strip().split("\t") + ["", "", ""])[:3]
    cpus = allocated if allocated and allocated > 0 else allocated_cpus(container)
    sample = HostSample(cpu_percent_of_quota=cpu_percent_of_quota(parse_percent(cpu_text), cpus))
    sample.memory_percent = parse_percent(mem_pct) if mem_pct else None
    if "/" in mem_text:
        used, _limit = [part.strip() for part in mem_text.split("/", 1)]
        sample.rss_bytes = parse_bytes(used)
    return sample


def sample_cpu_many(containers: list[str], allocated: float, docker_host: str | None = None) -> list[float]:
    """CPU percent of quota for many containers from one `docker stats` call.

    `docker stats --no-stream` takes about a second per call, so sampling a
    25-process simulator fleet one container at a time takes longer than the
    sample window and leaves one host sample per step. ``docker_host`` selects
    a second machine with ``docker -H``.
    """
    if not containers:
        return []
    command = ["docker"]
    if docker_host:
        command += ["-H", docker_host]
    command += ["stats", "--no-stream", "--format", "{{.CPUPerc}}", *containers]
    proc = subprocess.run(
        command,
        text=True, capture_output=True,
    )
    if proc.returncode != 0:
        return []
    out = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(cpu_percent_of_quota(parse_percent(line), allocated))
        except (ValueError, IndexError):
            continue
    return out


def oip_gateway_command(
    *,
    image: str,
    cpus: float,
    memory: str,
    publish: str,
    heap: str,
    env_file: str | None = None,
    name: str = "oip-bench-gw",
) -> list[str]:
    """``docker run`` for one scaling tier of our gateway.

    The admin password is not an argument. Pass ``env_file`` from
    ``password_env_text`` so ``OIP_ADMIN_PASSWORD`` stays out of the process list.
    ``heap`` is an ``-Xmx`` size (``2g``) and follows the tier's memory. Port 4840
    is the OPC UA server; anonymous access is off, so the client uses the same user.
    No named volume is mounted: the image's anonymous ``/data`` volume is removed
    with ``docker rm -v`` after the tier, so a later tier does not see the previous tags.
    """
    if cpus <= 0:
        raise ValueError("cpus must be > 0")
    if not memory:
        raise ValueError("memory is empty")
    if not heap:
        raise ValueError("heap is empty")
    command = [
        "docker", "run", "-d", "--name", name,
        "--cpus", _cpus_arg(cpus), "--memory", memory,
        "-p", publish,
        "-p", "4840:4840",
        "--add-host", "host.docker.internal:host-gateway",
        "-e", "OIP_DATA_DIR=/data",
        "-e", "OIP_PROFILE=standard",
        "-e", "OIP_HTTP_HOST=0.0.0.0",
        "-e", "OIP_HTTP_PORT=8088",
        "-e", "OIP_OPCUA_BIND=0.0.0.0",
        "-e", f"JAVA_OPTS=-Xmx{heap} -Xlog:gc*:file=/data/gc.log:time,uptime:filecount=3,filesize=20m",
    ]
    if env_file:
        command += ["--env-file", env_file]
    command.append(image)
    return command


def remove_container_command(name: str) -> list[str]:
    """Remove the container and its anonymous volumes (a fresh ``/data`` next time)."""
    return ["docker", "rm", "-v", "-f", name]


def update_limits_command(container: str, cpus: float | None, memory: str | None) -> list[str]:
    """``docker update`` applies the same cgroup cap to whichever gateway is under test."""
    if not container:
        raise ValueError("container name is required")
    if cpus is None and memory is None:
        raise ValueError("no limits to apply")
    command = ["docker", "update"]
    if cpus is not None:
        if cpus <= 0:
            raise ValueError("cpus must be > 0")
        command += ["--cpus", _cpus_arg(cpus)]
    if memory is not None:
        if not str(memory).strip():
            raise ValueError("memory is empty")
        command += ["--memory", str(memory)]
    command.append(container)
    return command


def heap_mb(text: str) -> int:
    """the reference platform's ``-m`` runtime argument is megabytes. A bare integer is already megabytes.

    ``2g`` / ``512m`` use the Docker-style suffix and binary megabytes (1024), matching
    ``wrapper.java.maxmemory``.
    """
    raw = text.strip()
    if not raw:
        raise ValueError("heap is empty")
    if raw.isdigit():
        return _positive_mb(int(raw))
    number: list[str] = []
    unit: list[str] = []
    for ch in raw.lower():
        if ch.isdigit() or ch == ".":
            if unit:
                raise ValueError(raw)
            number.append(ch)
        else:
            unit.append(ch)
    if not number:
        raise ValueError(raw)
    suffix = "".join(unit)
    scale = {"m": 1, "mb": 1, "mib": 1, "g": 1024, "gb": 1024, "gib": 1024}
    if suffix not in scale:
        raise ValueError(raw)
    return _positive_mb(int(float("".join(number)) * scale[suffix]))


def with_reference_heap_arg(command: list[str] | None, megabytes: int) -> list[str]:
    """Set or replace the image's ``-m`` max-memory argument. Other arguments stay."""
    args = list(command or [])
    if "-m" in args:
        index = args.index("-m")
        if index + 1 >= len(args):
            return args + [str(megabytes)]
        args[index + 1] = str(megabytes)
        return args
    return args + ["-m", str(megabytes)]


def recreate_command(inspect: dict, *, cpus: float | None, memory: str | None, heap_mb_value: int) -> list[str]:
    """Build ``docker run`` for an existing container with a new JVM heap.

    Mounts, published ports, environment, and the current command are kept.
    ``-m`` is the official image's max-memory argument and is applied only at process start,
    so the container is replaced. The caller stops the previous container first.
    """
    if heap_mb_value < 1:
        raise ValueError("heap must be at least 1 MB")
    name = str(inspect.get("Name") or "").lstrip("/")
    if not name:
        raise ValueError("container inspect has no name")
    config = inspect.get("Config") or {}
    host = inspect.get("HostConfig") or {}
    image = config.get("Image")
    if not image:
        raise ValueError("container inspect has no image")
    command = ["docker", "run", "-d", "--name", name]
    nano = int(host.get("NanoCpus") or 0)
    existing_cpus = nano / 1_000_000_000 if nano else None
    use_cpus = cpus if cpus is not None else existing_cpus
    if use_cpus is not None:
        command += ["--cpus", _cpus_arg(use_cpus)]
    existing_memory = int(host.get("Memory") or 0)
    use_memory = memory if memory is not None else (str(existing_memory) if existing_memory else None)
    if use_memory is not None:
        command += ["--memory", str(use_memory)]
    network = host.get("NetworkMode")
    if network and network not in ("default", "bridge"):
        command += ["--network", str(network)]
    restart = (host.get("RestartPolicy") or {}).get("Name")
    if restart and restart != "no":
        command += ["--restart", str(restart)]
    if host.get("Privileged"):
        command.append("--privileged")
    user = config.get("User")
    if user:
        command += ["--user", str(user)]
    for extra in host.get("ExtraHosts") or []:
        command += ["--add-host", str(extra)]
    for bind in host.get("Binds") or []:
        command += ["-v", str(bind)]
    for published in _published_ports(host.get("PortBindings")):
        command += ["-p", published]
    for env in config.get("Env") or []:
        command += ["-e", str(env)]
    command.append(str(image))
    command.extend(with_reference_heap_arg(config.get("Cmd"), heap_mb_value))
    return command


def apply_reference_heap(name: str, run_command: list[str], run) -> None:
    """Replace ``name`` with ``run_command``. On failure, put the previous container back and start it.

    The previous container is renamed aside and stopped so its ports and volume are free.
    ``run`` is ``subprocess.check_call`` in production and a recorder in tests.
    """
    backup = f"{name}-bench-prev"
    run(["docker", "rm", "-f", backup])
    run(["docker", "rename", name, backup])
    run(["docker", "stop", backup])
    try:
        run(run_command)
    except Exception:
        _best_effort(run, ["docker", "rename", backup, name])
        _best_effort(run, ["docker", "start", name])
        raise
    run(["docker", "rm", "-f", backup])


def _best_effort(run, command: list[str]) -> None:
    try:
        run(command)
    except Exception:
        return


def _published_ports(bindings) -> list[str]:
    if not isinstance(bindings, dict):
        return []
    published = []
    for container_port, hosts in bindings.items():
        port = str(container_port).split("/")[0]
        for binding in hosts or []:
            if not isinstance(binding, dict):
                continue
            host_port = str(binding.get("HostPort") or "")
            host_ip = str(binding.get("HostIp") or "")
            if host_ip and host_ip not in ("0.0.0.0", "::"):
                published.append(f"{host_ip}:{host_port}:{port}")
            elif host_port:
                published.append(f"{host_port}:{port}")
            else:
                published.append(port)
    return published


def _cpus_arg(cpus: float) -> str:
    if cpus <= 0:
        raise ValueError("cpus must be > 0")
    if float(cpus).is_integer():
        return str(int(cpus))
    return str(cpus)


def _positive_mb(value: int) -> int:
    if value < 1:
        raise ValueError("heap must be at least 1 MB")
    return value


def stats_line(cpu_text: str, mem_usage: str, mem_percent: str, allocated: float) -> HostSample:
    """Parse one stats row. Used by tests without docker."""
    sample = HostSample(
        cpu_percent_of_quota=cpu_percent_of_quota(parse_percent(cpu_text), allocated),
        memory_percent=parse_percent(mem_percent),
    )
    if "/" in mem_usage:
        sample.rss_bytes = parse_bytes(mem_usage.split("/", 1)[0])
    return sample
