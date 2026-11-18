"""Scripted container for the reference product, for head-to-head runs (ADR 0010).

Black-box and documented use only: the vendor's public container image, started
with its documented environment variables. Nothing here reads, patches or copies
product files.

Subcommands: up, wait, status, guard, stop, harness-cmd.

Trial rules this script keeps:
* The trial window is 120 minutes from the container start. ``guard`` refuses a
  run that would not finish inside the 110 minute procedure cap.
* a manual license reset is a person's step. This script never calls the trial route with a
  write and never loops stop/up to get a new window. A second ``up`` after any
  earlier start needs ``--reset-confirmed``, passed by a person who has decided to
  open a new window.

The admin password lives in a gitignored file (generated at random on first use)
and reaches docker through a mode 0600 env file, never an argument.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets as _secrets
import shlex
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
BENCH = HERE.parent
REPO = BENCH.parent
sys.path.insert(0, str(BENCH))

from bench.dockerbox import heap_mb, remove_container_command  # noqa: E402
from bench.headtohead import REFERENCE_SIZES  # noqa: E402
from bench.secrets import SecretError, read_admin_password  # noqa: E402

CONFIG = HERE / "config.json"
STATE_DIR = HERE / "state"
STATE_FILE = STATE_DIR / "start.json"
GUARD_REFUSED = 3


class EnvError(Exception):
    pass


def load_config(path: Path = CONFIG) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def parse_time(text: str) -> datetime:
    value = text.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    head, sep, tail = value.partition(".")
    if sep:
        frac, plus, rest = tail.partition("+")
        if not plus:
            frac, minus, rest = tail.partition("-")
            plus = "-" if minus else ""
        value = f"{head}.{frac[:6]}{plus}{rest}"
    parsed = datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def size_limits(size_id: str) -> tuple[float, str]:
    for size in REFERENCE_SIZES:
        if size["id"] == size_id.upper():
            return float(size["vcpu"]), str(size["memory"])
    raise EnvError(f"unknown size {size_id!r}; use one of " + ", ".join(s["id"] for s in REFERENCE_SIZES))


def ensure_password_file(path: Path) -> None:
    """Create a random password file (mode 0600) when none exists. Never printed."""
    if path.exists():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(_secrets.token_urlsafe(24) + "\n")


def env_file_text(config: dict, password: str) -> str:
    """``docker run --env-file`` content: the image's documented commissioning variables."""
    for ch in ("\n", "\r", "\x00"):
        if ch in password:
            raise EnvError("password must be a single line")
    lines = [
        "ACCEPT_REFERENCE_EULA=Y",
        f"GATEWAY_ADMIN_USERNAME={config['adminUser']}",
        f"GATEWAY_ADMIN_PASSWORD={password}",
        f"REFERENCE_EDITION={config['edition']}",
        f"GATEWAY_HTTP_PORT={config['httpPort']}",
    ]
    return "\n".join(lines) + "\n"


def run_command(config: dict, *, cpus: float, memory: str, heap_megabytes: int, env_file: str) -> list[str]:
    """``docker run`` with the same cgroup limits the harness applies to our gateway.

    The image's ``-m`` runtime argument sets the JVM max memory in megabytes
    (the harness's ``--reference-heap``). No named volume: ``docker rm -v`` on stop
    removes the data volume.
    """
    if cpus <= 0:
        raise EnvError("cpus must be > 0")
    cpu_text = str(int(cpus)) if float(cpus).is_integer() else str(cpus)
    return [
        "docker", "run", "-d", "--name", config["container"],
        "--cpus", cpu_text, "--memory", memory,
        "-p", f"{config['httpPort']}:{config['httpPort']}",
        "-p", f"{config['opcuaPort']}:{config['opcuaPort']}",
        "--add-host", "host.docker.internal:host-gateway",
        "--env-file", env_file,
        config["image"],
        "-m", str(heap_megabytes),
    ]


def guard_check(start: datetime, now: datetime, config: dict, need_minutes: float | None = None) -> tuple[bool, str]:
    """True when a run needing ``need_minutes`` still fits inside the trial window.

    The window is ``trialMinutes`` from the container start. The default need is the
    procedure cap (110 min), which leaves ten minutes of the 2 hour window spare.
    """
    need = float(config["procedureMinutes"] if need_minutes is None else need_minutes)
    window = float(config["trialMinutes"])
    if need > float(config["procedureMinutes"]):
        return False, f"a run of {need:g} min exceeds the {config['procedureMinutes']} min procedure cap"
    elapsed = (now - start).total_seconds() / 60.0
    left = window - elapsed
    if left < need:
        return False, (
            f"{elapsed:.1f} min of the {window:g} min trial window have passed; {max(left, 0):.1f} min "
            f"left is less than the {need:g} min the run needs. A person restarts the trial (a manual license reset "
            "is manual); then pass --reset-confirmed to up."
        )
    return True, f"{left:.1f} min of the trial window left, run needs {need:g} min"


def read_state() -> dict | None:
    if not STATE_FILE.exists():
        return None
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def write_state(state: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def status_ok(url: str, timeout: float = 3.0) -> bool:
    """GET the documented status endpoint; healthy means HTTP 200 reporting RUNNING."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read(4096).decode("utf-8", "replace")
            return resp.status == 200 and "RUNNING" in body
    except (urllib.error.URLError, OSError, ValueError):
        return False


def wait_healthy(config: dict, timeout: float, sleep=time.sleep, probe=status_ok, clock=time.monotonic) -> bool:
    url = f"http://127.0.0.1:{config['httpPort']}{config['statusPath']}"
    deadline = clock() + timeout
    while True:
        if probe(url):
            return True
        if clock() >= deadline:
            return False
        sleep(5)


def _docker(args: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], text=True, capture_output=True, **kw)


def container_exists(name: str) -> bool:
    return _docker(["inspect", "--format", "{{.Id}}", name]).returncode == 0


def container_started_at(name: str) -> datetime:
    out = _docker(["inspect", "--format", "{{.State.StartedAt}}", name])
    if out.returncode != 0:
        raise EnvError(f"container {name} not found")
    return parse_time(out.stdout)


def cmd_up(args, config: dict) -> int:
    name = config["container"]
    if container_exists(name):
        raise EnvError(f"container {name} exists; run stop first. This script does not recreate a running trial.")
    if read_state() is not None and not args.reset_confirmed:
        raise EnvError(
            "an earlier start is on record. A new container is a new trial window, so a person opens it: "
            "perform the manual restart decision, then pass --reset-confirmed."
        )
    cpus, memory = size_limits(args.size) if args.size else (args.cpus, args.memory)
    if cpus is None or memory is None:
        cpus, memory = size_limits(config["defaultSize"])
    heap = heap_mb(args.heap) if args.heap else max(1, heap_mb(memory) // 2)
    password_path = REPO / config["passwordFile"]
    ensure_password_file(password_path)
    try:
        password = read_admin_password(password_path.read_text(encoding="utf-8"), str(password_path))
    except SecretError as exc:
        raise EnvError(str(exc)) from exc
    fd, env_path = tempfile.mkstemp(prefix="ign-env-")
    try:
        os.fchmod(fd, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(env_file_text(config, password))
        command = run_command(config, cpus=cpus, memory=memory, heap_megabytes=heap, env_file=env_path)
        proc = _docker(command[1:])
    finally:
        os.unlink(env_path)
    if proc.returncode != 0:
        lines = proc.stderr.strip().splitlines()
        raise EnvError("docker run failed" + (": " + lines[-1] if lines else ""))
    started = container_started_at(name)
    write_state({
        "container": name, "startedAt": started.isoformat(), "cpus": cpus, "memory": memory,
        "heapMb": heap, "trialMinutes": config["trialMinutes"],
    })
    print(f"started {name} at {started.isoformat()} cpus={cpus:g} memory={memory} heapMb={heap}")
    if args.no_wait:
        return 0
    return cmd_wait(args, config)


def cmd_wait(args, config: dict) -> int:
    timeout = float(getattr(args, "timeout", None) or config["healthTimeoutSeconds"])
    if wait_healthy(config, timeout):
        print(f"gateway answers on port {config['httpPort']}{config['statusPath']} (RUNNING)")
        return 0
    print(f"gateway did not answer within {timeout:g} s", file=sys.stderr)
    return 1


def cmd_status(args, config: dict) -> int:
    state = read_state()
    url = f"http://127.0.0.1:{config['httpPort']}{config['statusPath']}"
    print(json.dumps({"container": container_exists(config["container"]), "healthy": status_ok(url), "state": state}, indent=2))
    return 0


def cmd_guard(args, config: dict) -> int:
    state = read_state()
    if state is None or not container_exists(config["container"]):
        print("no running container on record; start one with up", file=sys.stderr)
        return GUARD_REFUSED
    start = container_started_at(config["container"])
    ok, message = guard_check(start, now_utc(), config, args.need_minutes)
    print(("ok: " if ok else "refused: ") + message, file=None if ok else sys.stderr)
    return 0 if ok else GUARD_REFUSED


def cmd_stop(args, config: dict) -> int:
    proc = _docker(remove_container_command(config["container"])[1:])
    if proc.returncode != 0 and container_exists(config["container"]):
        print(proc.stderr.strip(), file=sys.stderr)
        return 1
    print(f"removed {config['container']} and its volumes (start record kept)")
    return 0


def harness_command(config: dict, size_id: str, profile: str) -> list[str]:
    cpus, memory = size_limits(size_id)
    return [
        "python3", "-m", "bench", "ramp",
        "--profile", profile, "--target", "reference",
        "--gateway", f"http://127.0.0.1:{config['httpPort']}",
        "--token-file", "../secrets/reference-api-token.txt",
        "--container", config["container"],
        "--cpus", f"{cpus:g}", "--memory", memory,
        "--reference-heap", str(max(1, heap_mb(memory) // 2)),
        "--start-sim",
    ]


def cmd_harness(args, config: dict) -> int:
    print(shlex.join(harness_command(config, args.size or config["defaultSize"], args.profile)))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="reference_env", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    up = sub.add_parser("up", help="start the container and wait for the status endpoint")
    up.add_argument("--size", help="S2, S4, S8, S16 or EDGE (cgroup limits as the harness)")
    up.add_argument("--cpus", type=float)
    up.add_argument("--memory")
    up.add_argument("--heap", help="JVM max memory, MB or g/m suffix. Default half the memory")
    up.add_argument("--reset-confirmed", action="store_true", help="a person has opened a new trial window")
    up.add_argument("--no-wait", action="store_true")
    up.add_argument("--timeout", type=float)
    wait = sub.add_parser("wait", help="wait for the status endpoint")
    wait.add_argument("--timeout", type=float)
    sub.add_parser("status")
    guard = sub.add_parser("guard", help="exit 3 when a run would not finish inside the trial window")
    guard.add_argument("--need-minutes", type=float, default=None)
    sub.add_parser("stop", help="remove the container and its volumes")
    harness = sub.add_parser("harness-cmd", help="print the ramp command for this container")
    harness.add_argument("--size")
    harness.add_argument("--profile", default="profiles/reference-trial.json")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    config = load_config()
    handler = {"up": cmd_up, "wait": cmd_wait, "status": cmd_status, "guard": cmd_guard,
               "stop": cmd_stop, "harness-cmd": cmd_harness}[args.cmd]
    try:
        return handler(args, config)
    except EnvError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
