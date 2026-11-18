"""Trial-window runner: one plan, one command, one window.

    cd bench
    python3 -m bench window --plan plans/window-1.json --list
    python3 -m bench window --plan plans/window-1.json --target ours          # dry run, our gateway only
    python3 -m bench window --plan plans/window-1.json --target reference     # the real window
    python3 -m bench window --plan plans/window-1.json --target ours --phase after

The plan is data (``plans/window-1.json``): setup, measurements, parity checks,
collect, teardown and slack, each with a minute budget, an output file and what
it closes. The runner times every step against the window clock, skips an
optional step that no longer fits, and goes straight to collect and teardown
when a required step would push the window past its budget.

Rules this module keeps:

* ``--target ours`` never starts, contacts or pulls the reference product. Its
  steps run against our gateway (a host process from ``installDist``) and the
  local simulator. That is the dry run.
* ``--target reference`` starts the reference container only through
  ``reference/reference_env.py up`` (which refuses a second start), and reads the
  trial route with GET only. Nothing here sends a write to a trial route, clicks a
  trial action, or loops stop and up.
* Parity probes record status codes and field names. A probe may also record
  named field values (a connection status), and the plan check refuses a name
  that looks secret, so a response that carries a secret does not land in an
  output file. Secrets come from files
  and reach a process through its environment or a request body, never argv.
* Every result is labelled container-size, not reference-size.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets as _secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from bench.profile import PlanError, load_profile
from bench.report import write_report
from bench.targets import HostSample, HttpTransport

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
REFERENCE_DIR = ROOT / "reference"
LABEL = "container-size, not reference-size"
KINDS = {
    "preflight", "db-up", "sim-up", "up", "manual", "check", "ramp", "ramp-collect",
    "parity", "collect", "teardown", "slack", "hold",
}
TAIL_PHASES = ("collect", "teardown", "slack")
TARGETS = ("ours", "reference")
PROMPT_END = "."
# A probe may record named field values (a connection status), never one whose name looks secret.
SECRET_WORDS = ("password", "secret", "token", "key", "passphrase", "credential")


# ---------------------------------------------------------------------------
# Plan


def load_plan(path: Path) -> dict:
    """Read and check a window plan. A plan that does not fit its budget is refused."""
    try:
        plan = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PlanError(f"could not read window plan {path}: {exc}") from exc
    check_plan(plan)
    return plan


def check_plan(plan: dict) -> None:
    budget = plan.get("budgetMinutes")
    trial = plan.get("trialMinutes")
    if not isinstance(budget, (int, float)) or budget <= 0:
        raise PlanError("budgetMinutes must be a positive number")
    if not isinstance(trial, (int, float)) or budget > trial:
        raise PlanError("budgetMinutes must not exceed trialMinutes")
    if plan.get("label") != LABEL:
        raise PlanError(f"label must be {LABEL!r}")
    seen: set[str] = set()
    for group in ("before", "steps", "after"):
        for step in plan.get(group) or []:
            sid = step.get("id")
            if not sid or sid in seen:
                raise PlanError(f"step id {sid!r} is missing or repeated")
            seen.add(sid)
            if step.get("kind") not in KINDS:
                raise PlanError(f"{sid}: unknown kind {step.get('kind')!r}")
            minutes = step.get("minutes")
            if not isinstance(minutes, (int, float)) or minutes <= 0:
                raise PlanError(f"{sid}: minutes must be positive")
            if not isinstance(step.get("closes"), list):
                raise PlanError(f"{sid}: closes must be a list")
            for target in TARGETS:
                for probe in _all_probes(step, target):
                    for name in probe.get("record") or []:
                        if any(word in str(name).lower() for word in SECRET_WORDS):
                            raise PlanError(f"{sid}: field {name!r} looks secret and is not recorded")
            for probe in _all_probes(step, "reference"):
                if probe.get("discardBody"):
                    raise PlanError(f"{sid}: discardBody is for our gateway's session probes only")
                if "trial" in probe.get("path", "").lower() and probe.get("method", "GET").upper() != "GET":
                    raise PlanError(f"{sid}: a trial route may only be read with GET")
    steps = plan.get("steps") or []
    if not steps:
        raise PlanError("a window plan needs steps")
    total = sum(float(step["minutes"]) for step in steps)
    if total > budget + 1e-9:
        raise PlanError(f"steps add up to {total:g} min, over the {budget:g} min budget")
    phases = [step.get("phase") for step in steps]
    if phases[-3:] != list(TAIL_PHASES):
        raise PlanError("a window plan ends with collect, teardown and slack")
    if not any(step.get("kind") == "teardown" for step in steps):
        raise PlanError("a window plan needs a teardown step")


def _all_probes(step: dict, target: str) -> list[dict]:
    probes = list(step.get(target) or [])
    for key in ("afterPrompt", "afterFault"):
        probes.extend((step.get(key) or {}).get(target) or [])
    return probes


def schedule(steps: list[dict]) -> list[dict]:
    """Planned start and end minute of each step, from the start of the window."""
    rows = []
    at = 0.0
    for step in steps:
        end = at + float(step["minutes"])
        rows.append({"id": step["id"], "start": at, "end": end, "step": step})
        at = end
    return rows


def reserve_after(steps: list[dict], index: int) -> float:
    """Minutes the later required steps need. Slack is not reserved: it absorbs overruns."""
    total = 0.0
    for step in steps[index + 1:]:
        if step.get("phase") == "slack":
            continue
        if step.get("phase") in TAIL_PHASES or int(step.get("priority", 1)) == 1:
            total += float(step["minutes"])
    return total


def decide(steps: list[dict], index: int, elapsed_minutes: float, budget: float) -> str:
    """``run``, ``skip`` (an optional step no longer fits) or ``stop`` (go to collect and teardown)."""
    step = steps[index]
    if step.get("phase") in TAIL_PHASES:
        return "run"
    if elapsed_minutes + float(step["minutes"]) + reserve_after(steps, index) <= budget + 1e-9:
        return "run"
    if int(step.get("priority", 1)) > 1:
        return "skip"
    return "stop"


def ramp_cap_seconds(steps: list[dict], index: int, elapsed_minutes: float, budget: float, margin: float = 120.0) -> float:
    """Wall cap for a ramp: its own slot, and never into the time later required steps need."""
    slot = float(steps[index]["minutes"])
    room = budget - elapsed_minutes - reserve_after(steps, index)
    return max(0.0, min(slot, room) * 60.0 - margin)


def render_runbook(plan: dict, timings: dict | None = None) -> str:
    """Markdown table of the window. ``timings`` maps a step id to dry-run seconds."""
    lines = [
        "| T+ (min) | Step | Min | What | Kind | Output | Closes |" + (" Dry run |" if timings else ""),
        "|---|---|---:|---|---|---|---|" + ("---:|" if timings else ""),
    ]
    for row in schedule(plan["steps"]):
        step = row["step"]
        closes = ", ".join(step.get("closes") or []) or "-"
        optional = " (optional)" if int(step.get("priority", 1)) > 1 else ""
        cells = [
            f"{row['start']:g}-{row['end']:g}", step["id"], f"{step['minutes']:g}",
            step["title"] + optional, step["kind"], f"`{step['output']}`" if step.get("output") else "-", closes,
        ]
        if timings:
            seconds = timings.get(step["id"])
            cells.append("-" if seconds is None else f"{seconds / 60:.1f} min")
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Small helpers


def shape(payload) -> dict:
    """Field names and list lengths, never values."""
    if isinstance(payload, dict):
        out = {"type": "object", "keys": sorted(str(k) for k in payload.keys())}
        lists = {}
        for key, value in payload.items():
            if isinstance(value, list):
                first = value[0] if value else None
                lists[str(key)] = {
                    "length": len(value),
                    "itemKeys": sorted(str(k) for k in first.keys()) if isinstance(first, dict) else None,
                }
        if lists:
            out["lists"] = lists
        return out
    if isinstance(payload, list):
        first = payload[0] if payload else None
        return {
            "type": "array",
            "length": len(payload),
            "itemKeys": sorted(str(k) for k in first.keys()) if isinstance(first, dict) else None,
        }
    return {"type": type(payload).__name__}


def fill_secrets(body, read_secret):
    """Replace ``{secret:name}`` strings in a request body. The result is never written out."""
    if isinstance(body, dict):
        return {key: fill_secrets(value, read_secret) for key, value in body.items()}
    if isinstance(body, list):
        return [fill_secrets(value, read_secret) for value in body]
    if isinstance(body, str) and body.startswith("{secret:") and body.endswith("}"):
        return read_secret(body[len("{secret:"):-1])
    return body


def new_secret_file(path: Path, value: str | None = None) -> bool:
    """Create a mode 0600 secret file when none exists. Returns True when one was made."""
    if path.exists():
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write((value if value is not None else policy_password()) + "\n")
    return True


def policy_password() -> str:
    """Random, and meets our default password policy: 3 of 4 classes, no run of 4."""
    while True:
        candidate = _secrets.token_hex(18) + "aA1!"
        if not any(candidate[i] == candidate[i + 1] == candidate[i + 2] == candidate[i + 3] for i in range(len(candidate) - 3)):
            return candidate


def read_one_line(path: Path) -> str:
    text = path.read_text(encoding="utf-8").strip()
    if not text or "\n" in text:
        raise PlanError(f"{path} must hold one line")
    return text


class ProcCpu:
    """CPU percent of one or more host processes from /proc, between two calls."""

    def __init__(self, pids: list[int], cpus: float, clock=time.monotonic) -> None:
        self.pids = list(pids)
        self.cpus = float(cpus)
        self.clock = clock
        self.tick = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
        self.last = None

    def _ticks(self, pid: int) -> int | None:
        try:
            fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
            return int(fields[11]) + int(fields[12])
        except (OSError, IndexError, ValueError):
            return None

    def sample(self) -> list[float | None]:
        now = self.clock()
        ticks = [self._ticks(pid) for pid in self.pids]
        out: list[float | None] = []
        if self.last is not None:
            then, previous = self.last
            wall = max(now - then, 1e-6)
            for before, after in zip(previous, ticks):
                if before is None or after is None:
                    out.append(None)
                else:
                    out.append(100.0 * (after - before) / self.tick / wall / self.cpus)
        self.last = (now, ticks)
        return out


def rss_bytes(pid: int) -> int | None:
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def http_ok(url: str, timeout: float = 2.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError, ValueError):
        return False


def tcp_open(host: str, port: int, timeout: float = 2.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def session_raw(transport, method: str, path: str, body) -> tuple[int, dict]:
    """A session request whose body is not JSON (a backup archive). Keeps type and size, drops the bytes."""
    data = None if body is None else json.dumps(body).encode()
    headers = {"accept": "*/*"}
    if data is not None:
        headers["content-type"] = "application/json"
    if transport.cookie:
        headers["cookie"] = transport.cookie
        if transport.csrf and method.upper() not in ("GET", "HEAD"):
            headers["X-CSRF-Token"] = transport.csrf
    req = urllib.request.Request(transport.base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read()
            return resp.status, {"contentType": resp.headers.get("content-type"), "bytes": len(raw)}
    except urllib.error.HTTPError as exc:
        return exc.code, {"contentType": exc.headers.get("content-type") if exc.headers else None, "bytes": len(exc.read())}


def bogus_bearer_status(base: str, path: str) -> int:
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer not-a-key", "accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except (urllib.error.URLError, OSError):
        return 0


# ---------------------------------------------------------------------------
# Runner


@dataclass
class Result:
    id: str
    status: str
    seconds: float = 0.0
    startedMinute: float | None = None
    notes: list[str] = field(default_factory=list)
    data: dict = field(default_factory=dict)


class Runner:
    def __init__(self, plan: dict, target: str, out: Path, args, *, stdin=None, stdout=None,
                 clock=time.monotonic, docker=None) -> None:
        if target not in TARGETS:
            raise PlanError(f"target must be one of {', '.join(TARGETS)}")
        if getattr(args, "auto", False):
            # Automated operator steps take seconds, not a person's minutes: use their autoMinutes.
            plan = json.loads(json.dumps(plan))
            for step in plan.get("steps") or []:
                if "autoMinutes" in step:
                    step["minutes"] = step["autoMinutes"]
            check_plan(plan)
        self.plan = plan
        self.target = target
        self.dry_run = target == "ours"
        self.out = Path(out)
        self.args = args
        self.stdin = stdin if stdin is not None else sys.stdin
        self.stdout = stdout if stdout is not None else sys.stdout
        self.clock = clock
        self.docker = docker or _docker
        self.window_start: float | None = None
        self.results: list[Result] = []
        self.procs: dict[str, subprocess.Popen] = {}
        self.sim_procs: list[subprocess.Popen] = []
        self.sim_urls: list[str] = []
        self.fanout = None
        self.oip_target = None
        self.ref_transport = None
        self.ramp_report: dict | None = None
        self.data_dir: Path | None = None
        self.resuming = False
        self._watch_result = None

    # -- clock -------------------------------------------------------------

    def elapsed_minutes(self) -> float:
        if self.target == "reference":
            started = self._reference_start()
            if started is not None:
                return (datetime.now(timezone.utc) - started).total_seconds() / 60.0
        if self.window_start is None:
            return 0.0
        return (self.clock() - self.window_start) / 60.0

    def _reference_start(self) -> datetime | None:
        state = REFERENCE_DIR / "state" / "start.json"
        if not state.exists():
            return None
        record = json.loads(state.read_text(encoding="utf-8"))
        text = str(record.get("startedAt") or "")
        if not text:
            return None
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)

    def say(self, text: str) -> None:
        self.stdout.write(text + "\n")
        self.stdout.flush()

    # -- phases ------------------------------------------------------------

    def run(self, phase: str = "window", only: str | None = None, start_at: str | None = None) -> int:
        """``window`` runs the before steps, then the window steps (from ``start_at`` when resuming).

        ``before`` runs only the preflight steps and stops what they started. ``after`` runs the
        post-window steps, which use our gateway only.
        """
        self.out.mkdir(parents=True, exist_ok=True)
        self.resuming = start_at is not None
        try:
            if phase in ("window", "before"):
                for step in self.plan.get("before") or []:
                    if only and step["id"] != only and phase == "before":
                        continue
                    self._run_step(step)
                    if self.results[-1].status == "failed":
                        self.say(f"{step['id']} failed; the window is not started.")
                        return 1
            if phase == "window":
                self._run_window(only, start_at)
            if phase == "after":
                for step in self.plan.get("after") or []:
                    if only and step["id"] != only:
                        continue
                    self._run_step(step)
            return 0 if all(r.status != "failed" for r in self.results) else 1
        finally:
            self._write_summary(phase)
            self._stop_everything(final=True)

    def _run_window(self, only: str | None, start_at: str | None) -> int:
        steps = self.plan["steps"]
        budget = float(self.plan["budgetMinutes"])
        started = start_at is None
        stopping = False
        for index, step in enumerate(steps):
            if not started:
                started = step["id"] == start_at
                if not started:
                    continue
            if only and step["id"] != only:
                continue
            if step["kind"] == "slack":
                self.results.append(Result(step["id"], "slack", startedMinute=self.elapsed_minutes()))
                continue
            if stopping and step.get("phase") not in TAIL_PHASES:
                self.results.append(Result(step["id"], "skipped", notes=["window budget: went to collect and teardown"]))
                continue
            verdict = decide(steps, index, self.elapsed_minutes(), budget)
            if verdict == "skip":
                self.results.append(Result(step["id"], "skipped", notes=["optional step no longer fits the window"]))
                self.say(f"{step['id']}: skipped, it no longer fits the window")
                continue
            if verdict == "stop":
                stopping = True
                self.results.append(Result(step["id"], "skipped", notes=["required step would overrun; went to collect and teardown"]))
                self.say(f"{step['id']}: would overrun the window; going to collect and teardown")
                continue
            self._run_step(step, steps=steps, index=index)
            if step["kind"] == "up" and self.results[-1].status == "failed":
                stopping = True
            if step["kind"] == "check" and self.results[-1].status == "failed":
                # The container keeps running and its clock keeps going: fix the cause, then resume.
                self.say(f"{step['id']} failed. Fix it and resume with --from {step['id']}; the container is left running.")
                return 1
        return 0

    def _run_step(self, step: dict, steps=None, index=None) -> None:
        handler = getattr(self, "_do_" + step["kind"].replace("-", "_"))
        started_minute = self.elapsed_minutes() if self.window_start is not None or self.target == "reference" else None
        self.say(f"== {step['id']} {step['title']} (budget {step['minutes']:g} min)")
        begin = self.clock()
        result = Result(step["id"], "ok", startedMinute=started_minute)
        try:
            data = handler(step, steps, index) or {}
            result.data = data
            if data.get("status"):
                result.status = data.pop("status")
        except (PlanError, OSError, subprocess.SubprocessError, ValueError, RuntimeError) as exc:
            result.status = "failed"
            result.notes.append(str(exc))
            self.say(f"{step['id']} failed: {exc}")
        result.seconds = self.clock() - begin
        self.results.append(result)
        if step.get("output") and step["kind"] not in ("ramp", "hold", "collect"):
            self._write_json(step["output"], {"step": step["id"], "target": self.target, "label": LABEL,
                                              "dryRun": self.dry_run, **asdict_result(result)})
        self.say(f"   {result.status} in {result.seconds:.1f} s")

    def _write_json(self, relative: str, payload: dict) -> Path:
        path = self.out / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, default=str) + "\n", encoding="utf-8")
        return path

    # -- secrets -----------------------------------------------------------

    def secret_path(self, key: str) -> Path:
        rel = (self.plan.get("secrets") or {}).get(key)
        if not rel:
            raise PlanError(f"no secret file named {key!r} in the plan")
        return REPO / rel

    def read_secret(self, key: str) -> str:
        path = self.secret_path(key)
        if not path.exists():
            raise PlanError(f"secret file {path.relative_to(REPO)} is missing")
        return read_one_line(path)

    # -- steps: before ----------------------------------------------------

    def _do_preflight(self, step, steps, index) -> dict:
        checks: dict[str, object] = {}
        failed: list[str] = []
        db_image = self.plan["db"]["image"]
        checks["dbImageCached"] = self.docker(["image", "inspect", db_image]).returncode == 0
        if self.target == "reference":
            config = json.loads((REFERENCE_DIR / "config.json").read_text(encoding="utf-8"))
            checks["referenceImageCached"] = self.docker(["image", "inspect", config["image"]]).returncode == 0
            checks["startRecordAbsent"] = not (REFERENCE_DIR / "state" / "start.json").exists()
            checks["port8088Free"] = not tcp_open("127.0.0.1", int(config["httpPort"]))
            checks["port62541Free"] = not tcp_open("127.0.0.1", int(config["opcuaPort"]))
            required = ["referenceImageCached"]
            if getattr(self, "resuming", False):
                checks["resuming"] = True
            else:
                required += ["startRecordAbsent", "port8088Free", "port62541Free"]
            for key in required:
                if not checks[key]:
                    failed.append(key)
            try:
                import asyncua  # noqa: F401
                checks["opcUaClient"] = True
            except ImportError:
                checks["opcUaClient"] = False
                failed.append("opcUaClient")
        else:
            install = self._oip_install()
            checks["oipInstall"] = str(install.relative_to(REPO)) if install.is_relative_to(REPO) else str(install)
            checks["oipInstallPresent"] = (install / "bin" / "server").exists()
            checks["port8090Free"] = not tcp_open("127.0.0.1", int(self.plan["ours"]["httpPort"]))
            for key in ("oipInstallPresent", "port8090Free"):
                if not checks[key]:
                    failed.append(key)
            checks["referenceContacted"] = False
        if not checks["dbImageCached"]:
            failed.append("dbImageCached")
        sim = self.plan["sim"]
        for i in range(int(sim["instances"])):
            if tcp_open("127.0.0.1", int(sim["httpBase"]) + i):
                failed.append(f"simHttpPort{int(sim['httpBase']) + i}Busy")
        checks["taskset"] = shutil.which("taskset") is not None
        checks["noPullAttempted"] = True
        out = {"checks": checks, "failed": failed}
        if failed:
            out["status"] = "failed"
        return out

    def _oip_install(self) -> Path:
        given = getattr(self.args, "oip_install", None)
        return Path(given).resolve() if given else REPO / "gateway" / "server" / "build" / "install" / "server"

    def _do_db_up(self, step, steps, index) -> dict:
        db = self.plan["db"]
        made = new_secret_file(self.secret_path("db"))
        self.docker(["rm", "-f", "-v", db["container"]])
        env_path = self.out / ".db.env"
        fd = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(f"POSTGRES_PASSWORD={self.read_secret('db')}\n")
        try:
            proc = self.docker([
                "run", "-d", "--name", db["container"], "--cpus", str(db["cpus"]), "--memory", str(db["memory"]),
                "-p", f"{db['port']}:5432", "--env-file", str(env_path), db["image"],
            ])
        finally:
            env_path.unlink(missing_ok=True)
        if proc.returncode != 0:
            raise PlanError("database container did not start: " + (proc.stderr or "").strip()[-200:])
        ready = self._wait(lambda: self.docker(["exec", db["container"], "pg_isready", "-U", db["user"]]).returncode == 0, 90)
        return {"container": db["container"], "port": db["port"], "secretCreated": made, "ready": ready,
                "status": "ok" if ready else "failed"}

    def _do_sim_up(self, step, steps, index, product: str | None = None) -> dict:
        sim = self.plan["sim"]
        profile = self._ramp_profile()
        points = int(sim.get("points") or max(profile.points))
        reference = (product or self.target) == "reference"
        rows = []
        for i in range(int(sim["instances"])):
            modbus = int(sim["modbusBase"]) + (1000 if reference else 0) + i
            http = int(sim["httpBase"]) + i
            command = [
                sys.executable, str(ROOT / "sim" / "server.py"), "--points", str(points), "--bind", "0.0.0.0",
                "--percent", str(profile.percent_changing), "--period-ms", str(profile.period_ms[0]),
                "--registers-per-unit", str(profile.registers_per_unit),
                "--metrics-per-device", str(profile.metrics_per_device),
                "--modbus-port", str(modbus), "--modbus-extra-ports", "0",
                "--opcua-port", str(int(sim["opcuaBase"]) + i), "--mqtt-port", str(int(sim["mqttBase"]) + i),
                "--http-port", str(http), "--no-sparkplug",
            ]
            if sim.get("cpuList") and shutil.which("taskset"):
                command = ["taskset", "-c", str(sim["cpuList"]), *command]
            log = open(self.out / f"sim-{i}.log", "w", encoding="utf-8")
            self.sim_procs.append(subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, cwd=str(ROOT)))
            self.sim_urls.append(f"http://127.0.0.1:{http}")
            rows.append({"modbusPort": modbus, "httpPort": http})
        healthy = all(self._wait(lambda u=url: http_ok(u + "/health"), 60) for url in self.sim_urls)
        if reference and healthy:
            from bench.simfleet import ConnectionFanout
            backends = [("127.0.0.1", row["modbusPort"]) for row in rows]
            self.fanout = ConnectionFanout("0.0.0.0", int(sim["modbusBase"]), backends)
            self.fanout.start()
        return {"instances": rows, "points": points, "healthy": healthy, "fanout": bool(self.fanout),
                "status": "ok" if healthy else "failed"}

    # -- steps: window ----------------------------------------------------

    def _do_up(self, step, steps, index) -> dict:
        if self.target == "reference":
            self.window_start = self.clock()
            proc = subprocess.run(
                [sys.executable, str(REFERENCE_DIR / "reference_env.py"), "up", "--size", self.plan["size"]],
                text=True, capture_output=True, cwd=str(REPO),
            )
            out = {"exit": proc.returncode, "stdout": proc.stdout.strip()[-400:], "stderr": proc.stderr.strip()[-400:]}
            if proc.returncode != 0:
                out["status"] = "failed"
            return out
        self.window_start = self.clock()
        return self._start_oip()

    def _start_oip(self) -> dict:
        ours = self.plan["ours"]
        install = self._oip_install()
        server = install / "bin" / "server"
        if not server.exists():
            raise PlanError(f"our gateway install is missing at {server}; run ./gradlew :gateway:server:installDist")
        made = new_secret_file(self.secret_path("oip"))
        self.data_dir = self.out / "oip-data"
        shutil.rmtree(self.data_dir, ignore_errors=True)
        self.data_dir.mkdir(parents=True)
        # Our own deployment settings, written before the first start (the documented gateway.yaml keys).
        # Ports move into the bench range so another gateway on 8060 or 4840 does not collide.
        (self.data_dir / "gateway.yaml").write_text(
            "profile: standard\n"
            f"gatewayNetwork:\n  port: {int(ours.get('gatewayNetworkPort', 28060))}\n"
            f"opcua:\n  port: {int(ours.get('opcuaPort', 24860))}\n",
            encoding="utf-8",
        )
        env = os.environ.copy()
        env.update({
            "OIP_DATA_DIR": str(self.data_dir),
            "OIP_HTTP_PORT": str(ours["httpPort"]),
            "OIP_HTTP_HOST": "127.0.0.1",
            "OIP_ADMIN_PASSWORD": self.read_secret("oip"),
            "JAVA_OPTS": f"-Xmx{int(ours['heapMb'])}m",
        })
        command = [str(server)]
        if ours.get("cpuList") and shutil.which("taskset"):
            command = ["taskset", "-c", str(ours["cpuList"]), *command]
        log = open(self.out / "oip-gateway.log", "w", encoding="utf-8")
        self.procs["oip"] = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env, cwd=str(self.out))
        base = f"http://127.0.0.1:{ours['httpPort']}"
        proc = self.procs["oip"]
        healthy = self._wait(lambda: http_ok(base + "/api/v1/health") or proc.poll() is not None, 180)
        healthy = healthy and proc.poll() is None
        if not healthy:
            tail = (self.out / "oip-gateway.log").read_text(encoding="utf-8", errors="replace").strip().splitlines()[-3:]
            raise PlanError("our gateway did not answer /api/v1/health: " + " | ".join(tail)[-300:])
        return {"gateway": base, "pid": self.procs["oip"].pid, "secretCreated": made, "healthy": healthy,
                "cpuList": ours.get("cpuList"), "heapMb": ours["heapMb"], "status": "ok" if healthy else "failed"}

    def _do_manual(self, step, steps, index) -> dict:
        if self.target == "ours":
            new_secret_file(self.secret_path("backup"), _secrets.token_urlsafe(18))
            return {"prompt": step.get("prompt") or [], "answer": None,
                    "note": "dry run: the prompt is shown for the reference product only; nothing to do on our gateway"}
        if self._automated(step):
            auto = self._auto(step["id"])
            out = {"prompt": step.get("prompt") or [], "answer": None, "auto": auto}
            if auto.get("verdict") == "failed":
                # A setup step whose automation failed did not set anything up: fail it, so the next
                # check step stops the run with the container left running.
                out["status"] = "failed"
            return out
        return {"prompt": step.get("prompt") or [], "answer": self._ask(step.get("prompt") or [])}

    def _automated(self, step: dict) -> bool:
        return self.target == "reference" and bool(getattr(self.args, "auto", False)) and bool(step.get("auto"))

    def _auto(self, step_id: str, timeout: float = 900.0) -> dict:
        """Run the step's automation (``reference/refauto.py step <id>``) and keep its result.

        The script signs in through the reference product's web login and uses its documented
        API; it writes evidence under ``bench/results`` and secrets only to the gitignored files.
        """
        command = [sys.executable, str(REFERENCE_DIR / "refauto.py"), "step", step_id]
        try:
            proc = subprocess.run(command, text=True, capture_output=True, cwd=str(REPO), timeout=timeout)
        except subprocess.TimeoutExpired:
            return {"verdict": "failed", "error": f"automation passed its {timeout:g} s limit"}
        lines = [line for line in proc.stdout.strip().splitlines() if line.startswith("{")]
        if proc.returncode != 0 or not lines:
            return {"verdict": "failed", "exit": proc.returncode, "error": proc.stderr.strip()[-600:]}
        result = json.loads(lines[-1])
        self.say(f"   automation: {result.get('verdict')} ({result.get('evidenceDir')})")
        return result

    def _ask(self, lines: list[str]) -> list[str] | None:
        for line in lines:
            self.say("   - " + line)
        if self.stdin is None or not self.stdin.isatty():
            self.say("   (stdin is not a terminal: no operator answer recorded)")
            return None
        self.say(f"   Type what you saw. End with a line holding only '{PROMPT_END}'.")
        answer = []
        while True:
            line = self.stdin.readline()
            if not line or line.strip() == PROMPT_END:
                break
            answer.append(line.rstrip("\n"))
        return answer

    def _do_check(self, step, steps, index) -> dict:
        out: dict[str, object] = {"simHealthy": all(http_ok(u + "/health") for u in self.sim_urls)}
        budget = float(self.plan["budgetMinutes"])
        elapsed = self.elapsed_minutes()
        need = float(step["minutes"]) + reserve_after(steps, index) if steps else 0.0
        out["elapsedMinutes"] = round(elapsed, 2)
        out["minutesNeeded"] = need
        out["windowFits"] = elapsed + need <= budget + 1e-9
        if self.target == "reference":
            transport = self._reference_transport()
            status, payload = transport.request("GET", "/data/api/v1/trial")
            out["trialReadStatus"] = status
            if isinstance(payload, dict):
                out["licenseMode"] = payload.get("licenseMode")
                out["trialSecondsLeft"] = payload.get("trialSecondsLeft")
            out["tokenAccepted"] = status < 400
            out["opcUaEndpointOpen"] = tcp_open("127.0.0.1", 62541)
            ok = out["tokenAccepted"] and out["opcUaEndpointOpen"] and out["simHealthy"] and out["windowFits"]
        else:
            target = self._oip_target()
            target._login()
            out["loginAccepted"] = True
            ok = out["simHealthy"] and out["windowFits"]
        if not ok:
            out["status"] = "failed"
        return out

    def _ramp_profile(self):
        return load_profile(ROOT / self.plan["rampProfile"])

    def _size(self) -> dict:
        from bench.headtohead import reference_size
        return reference_size(self.plan["size"])

    def _reference_transport(self, token_key: str = "token") -> HttpTransport:
        config = json.loads((REFERENCE_DIR / "config.json").read_text(encoding="utf-8"))
        token = "not-a-key" if token_key == "bogus" else self.read_secret(token_key)
        return HttpTransport(f"http://127.0.0.1:{config['httpPort']}", token=token)

    def _oip_target(self):
        if self.oip_target is None:
            from bench.targets import OipTarget
            ours = self.plan["ours"]
            sim = self.plan["sim"]
            profile = self._ramp_profile()
            ports = [int(sim["modbusBase"]) + i for i in range(int(sim["instances"]))]
            self.oip_target = OipTarget(
                HttpTransport(f"http://127.0.0.1:{ours['httpPort']}"), "127.0.0.1", ports, int(sim["opcuaBase"]),
                require_devices=profile.require_devices, username="admin", password=self.read_secret("oip"),
                tag_batch=profile.tag_batch,
            )
        return self.oip_target

    def _sampler(self, target, product: str | None = None):
        size = self._size()
        sim_cpu = ProcCpu([p.pid for p in self.sim_procs], 1.0)
        if (product or self.target) == "reference":
            from bench.dockerbox import sample_container
            config = json.loads((REFERENCE_DIR / "config.json").read_text(encoding="utf-8"))

            def gateway_sample() -> HostSample:
                return sample_container(config["container"], float(size["vcpu"]))
        else:
            ours = self.plan["ours"]
            pid = self.procs["oip"].pid
            gw_cpu = ProcCpu([pid], float(ours["cpus"]))

            def gateway_sample() -> HostSample:
                cpu = gw_cpu.sample()
                rss = rss_bytes(pid)
                sample = HostSample(cpu_percent_of_quota=cpu[0] if cpu else None, rss_bytes=rss)
                if rss is not None:
                    sample.memory_percent = 100.0 * rss / float(ours["memoryBytes"])
                return sample

        def sample() -> HostSample:
            host = gateway_sample()
            values = [v for v in sim_cpu.sample() if v is not None]
            if values:
                host.sim_cpu_percent = max(values)
            extra = target.sample_host()
            for name in ("heap_bytes", "heap_max_bytes", "drift_gauge_ms", "clock_drift_percent"):
                if getattr(host, name) is None:
                    setattr(host, name, getattr(extra, name))
            if host.cpu_percent_of_quota is None:
                host.cpu_percent_of_quota = extra.cpu_percent_of_quota
            return host

        gateway_sample()
        sim_cpu.sample()
        return sample

    def _ramp_target(self, profile):
        if self.target == "reference":
            from bench.dockerbox import container_gateway_ip
            from bench.targets import referenceTarget
            config = json.loads((REFERENCE_DIR / "config.json").read_text(encoding="utf-8"))
            sim_host = container_gateway_ip(config["container"]) or "172.17.0.1"
            target = referenceTarget(
                self._reference_transport(), sim_host, int(self.plan["sim"]["modbusBase"]),
                int(self.plan["sim"]["opcuaBase"]), provider=str(profile.reference.get("provider") or "default"),
                batch=profile.tag_batch,
                opcua_read_endpoint=str(profile.reference.get("opcuaReadEndpoint") or "opc.tcp://127.0.0.1:62541"),
                delete_tags=False,
            )
        else:
            target = self._oip_target()
        target.docker = None
        return target

    def _do_ramp(self, step, steps, index) -> dict:
        from bench.cli import _Sim
        from bench.ramp import run_ramp
        profile = self._ramp_profile()
        cap = ramp_cap_seconds(steps, index, self.elapsed_minutes(), float(self.plan["budgetMinutes"]))
        target = self._ramp_target(profile)
        report = run_ramp(profile, target, sampler=self._sampler(target), sim=_Sim(self.sim_urls, profile),
                          wall_seconds=cap, stop_when_sim_saturated=True)
        report.update(self._labels())
        report["wallCapSeconds"] = cap
        self.ramp_report = report
        directory = (self.out / step["output"]).parent
        write_report(report, directory)
        from bench.headtohead import last_good_load
        load = last_good_load(report)
        return {"stoppedBecause": report.get("stoppedBecause"), "lastGoodLoad": load,
                "steps": len(report.get("steps") or []), "wallCapSeconds": cap,
                "report": str((directory / "report.json").relative_to(self.out)),
                "status": "failed" if report.get("stoppedBecause") == "error" else "ok"}

    def _labels(self, product: str | None = None) -> dict:
        size = self._size()
        product = product or self.target
        return {
            "label": LABEL,
            "dryRun": self.dry_run,
            "window": self.plan["name"],
            "sizeLimits": {"id": size["id"], "vcpu": size["vcpu"], "memory": size["memory"],
                           "note": "S2 limits on a shared 4 vCPU container; not dedicated hardware"},
            "productUnderTest": "our gateway" if product == "ours" else "reference product",
        }

    def _do_ramp_collect(self, step, steps, index) -> dict:
        out: dict[str, object] = {"simHealthy": all(http_ok(u + "/health") for u in self.sim_urls)}
        report = self.ramp_report or {}
        out["stoppedBecause"] = report.get("stoppedBecause")
        out["notes"] = list(report.get("notes") or [])[-5:]
        if self.target == "reference":
            config = json.loads((REFERENCE_DIR / "config.json").read_text(encoding="utf-8"))
            proc = self.docker(["stats", "--no-stream", "--format", "{{.CPUPerc}} {{.MemUsage}}", config["container"]])
            out["containerStats"] = proc.stdout.strip() if proc.returncode == 0 else None
        return out

    def _do_parity(self, step, steps, index) -> dict:
        out: dict[str, object] = {"gate": step.get("gate"), "closes": step.get("closes"), "prompt": step.get("prompt") or []}
        out["probes"] = self._probes(step.get(self.target) or [])
        if self._automated(step):
            out["answer"] = None
            out["auto"] = self._auto(step["id"])
        elif self.target == "reference":
            out["answer"] = self._ask(step.get("prompt") or [])
        else:
            out["answer"] = None
            out["note"] = "dry run: UI questions are for the reference product; our side is the probes"
        after = (step.get("afterPrompt") or {}).get(self.target)
        if after:
            out["probesAfterPrompt"] = self._probes(after)
        if step.get("dbFault"):
            self.say(f"   stopping the database for {float(getattr(self.args, 'db_outage_seconds', 30)):g} s: watch its status now")
            out["dbFault"] = self._db_fault((step.get("afterFault") or {}).get(self.target) or [])
            if self._automated(step):
                out["faultAuto"] = self._watch_result
            elif self.target == "reference":
                out["faultAnswer"] = self._ask(step.get("faultPrompt") or [])
        return out

    def _probes(self, probes: list[dict]) -> list[dict]:
        rows = []
        for probe in probes:
            method = probe.get("method", "GET").upper()
            path = probe["path"]
            row: dict[str, object] = {"method": method, "path": path}
            body = probe.get("body")
            if body is not None:
                body = fill_secrets(body, self.read_secret)
            started = self.clock()
            if self.target == "reference":
                if "trial" in path.lower() and method != "GET":
                    raise PlanError("a trial route may only be read with GET")
                transport = self._reference_transport(probe.get("token", "token"))
                row["token"] = probe.get("token", "token")
                status, payload = transport.request(method, path, body,
                                                    content_type=probe.get("contentType", "application/json"))
            else:
                auth = probe.get("auth", "session")
                row["auth"] = auth
                base = f"http://127.0.0.1:{self.plan['ours']['httpPort']}"
                if auth == "bogus-bearer":
                    status, payload = bogus_bearer_status(base, path), None
                elif auth == "none":
                    status, payload = HttpTransport(base).request(method, path, body)
                else:
                    target = self._oip_target()
                    if target.transport.cookie is None:
                        target._login()
                    if probe.get("discardBody"):
                        status, payload = session_raw(target.transport, method, path, body)
                    else:
                        status, payload = target._request(method, path, body)
            row["status"] = status
            row["ms"] = round((self.clock() - started) * 1000.0, 1)
            if payload is not None and probe.get("discardBody"):
                row["response"] = {k: payload.get(k) for k in ("contentType", "bytes")} if isinstance(payload, dict) else None
            elif payload is not None:
                row["shape"] = shape(payload)
                if probe.get("record") and isinstance(payload, dict):
                    row["fields"] = {name: payload.get(name) for name in probe["record"]}
            rows.append(row)
        return rows

    def _db_fault(self, probes: list[dict]) -> dict:
        db = self.plan["db"]
        out: dict[str, object] = {}
        outage = float(getattr(self.args, "db_outage_seconds", 30))
        watcher = None
        self._watch_result = None
        if self.target == "reference" and getattr(self.args, "auto", False):
            # Poll the reference connection status through the outage and the recovery.
            watcher = subprocess.Popen(
                [sys.executable, str(REFERENCE_DIR / "refauto.py"), "watch-db", "--seconds", str(outage + 75)],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, cwd=str(REPO))
            time.sleep(4)
        begin = self.clock()
        self.docker(["stop", "-t", "5", db["container"]])
        out["stoppedAfterSeconds"] = round(self.clock() - begin, 1)
        time.sleep(float(getattr(self.args, "db_outage_seconds", 30)))
        out["duringOutage"] = self._probes(probes)
        self.docker(["start", db["container"]])
        ready = self._wait(lambda: self.docker(["exec", db["container"], "pg_isready", "-U", db["user"]]).returncode == 0, 60)
        out["databaseReadySeconds"] = round(self.clock() - begin, 1)
        out["databaseReady"] = ready
        time.sleep(5)
        out["afterRecovery"] = self._probes(probes)
        if watcher is not None:
            try:
                text, _ = watcher.communicate(timeout=outage + 120)
                lines = [line for line in (text or "").splitlines() if line.startswith("{")]
                self._watch_result = json.loads(lines[-1]) if lines else {"verdict": "failed"}
            except subprocess.TimeoutExpired:
                watcher.kill()
                self._watch_result = {"verdict": "failed", "error": "watcher timed out"}
        return out

    def _do_collect(self, step, steps, index) -> dict:
        out: dict[str, object] = {"elapsedMinutes": round(self.elapsed_minutes(), 2)}
        if self.target == "reference":
            try:
                status, payload = self._reference_transport().request("GET", "/data/api/v1/trial")
                out["trialReadStatus"] = status
                if isinstance(payload, dict):
                    out["trialSecondsLeft"] = payload.get("trialSecondsLeft")
            except (PlanError, OSError) as exc:
                out["trialRead"] = str(exc)
        return out

    def _do_teardown(self, step, steps, index) -> dict:
        out = self._stop_everything(final=False)
        return out

    def _do_slack(self, step, steps, index) -> dict:
        return {}

    def _do_hold(self, step, steps, index) -> dict:
        """PRF-001 our half, outside any trial window: our gateway at the reference last good load."""
        from bench.cli import _Sim
        from bench.headtohead import assemble_size_report, hold_profile, last_good_load
        from bench.ramp import run_ramp
        source = getattr(self.args, "reference_report", None)
        ramp_step = next(s for s in self.plan["steps"] if s["kind"] == "ramp")
        path = Path(source) if source else (self.out / ramp_step["output"])
        if not path.exists():
            raise PlanError(f"no reference ramp report at {path}; run the window first or pass --reference-report")
        reference = json.loads(path.read_text(encoding="utf-8"))
        load = last_good_load(reference)
        if not load or not load.get("points"):
            raise PlanError("the reference ramp has no last good load to hold")
        profile = self._ramp_profile()
        steady = float(getattr(self.args, "steady_seconds", None) or profile.raw.get("steadySeconds") or 2700)
        if not self.sim_urls:
            self._do_sim_up(step, steps, index, product="ours")
        if "oip" not in self.procs:
            started = self._start_oip()
            if started.get("status") == "failed":
                raise PlanError("our gateway did not start for the hold")
        target = self._oip_target()
        target.docker = None
        hold = hold_profile(profile, int(load["points"]), sample_seconds=steady, sample_interval=10)
        ours = run_ramp(hold, target, sampler=self._sampler(target, "ours"), sim=_Sim(self.sim_urls, hold),
                        collect_samples=True, stop_when_sim_saturated=True)
        pause = {"size": self.plan["size"], "automated": False, "postedTrialRoute": False, "method": "manual",
                 "mode": "window-runner", "confirmed": True}
        report = assemble_size_report(self._size(), reference, ours, pause, dry_run=self.dry_run or bool(reference.get("dryRun")))
        report.update(self._labels("ours"))
        report["steadySeconds"] = steady
        report["referenceReport"] = str(path)
        directory = (self.out / step["output"]).parent
        write_report(report, directory)
        self._stop_everything(final=True)
        return {"held": (report.get("comparison") or {}).get("held"), "lastGoodLoad": load,
                "steadySeconds": steady, "oursStopped": ours.get("stoppedBecause")}

    # -- teardown ----------------------------------------------------------

    def _stop_everything(self, final: bool) -> dict:
        out: dict[str, object] = {}
        if self.target == "reference" and not final:
            proc = subprocess.run([sys.executable, str(REFERENCE_DIR / "reference_env.py"), "stop"],
                                  text=True, capture_output=True, cwd=str(REPO))
            out["referenceRemoved"] = proc.returncode == 0
        proc = self.procs.pop("oip", None)
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
            out["oipStopped"] = True
            self.oip_target = None
        if self.fanout is not None:
            self.fanout.stop()
            self.fanout = None
        for sim in self.sim_procs:
            sim.terminate()
            try:
                sim.wait(timeout=10)
            except subprocess.TimeoutExpired:
                sim.kill()
        if self.sim_procs:
            out["simStopped"] = len(self.sim_procs)
        self.sim_procs = []
        self.sim_urls = []
        db = self.plan["db"]["container"]
        if self.docker(["inspect", "--format", "{{.Id}}", db]).returncode == 0:
            self.docker(["rm", "-f", "-v", db])
            out["dbRemoved"] = True
        return out

    # -- summary -----------------------------------------------------------

    def _write_summary(self, phase: str) -> None:
        timings = {r.id: round(r.seconds, 1) for r in self.results}
        summary = {
            "kind": "window",
            "plan": self.plan["name"],
            "target": self.target,
            "dryRun": self.dry_run,
            "label": LABEL,
            "phase": phase,
            "budgetMinutes": self.plan["budgetMinutes"],
            "elapsedMinutes": round(self.elapsed_minutes(), 2),
            "results": [asdict_result(r) for r in self.results],
            "timingsSeconds": timings,
            "writtenAt": datetime.now(timezone.utc).isoformat(),
        }
        name = "summary.json" if phase == "window" else f"summary-{phase}.json"
        self._write_json(name, summary)
        lines = [f"# {self.plan['name']} ({self.target}, {LABEL})", "",
                 "| Step | Status | Seconds | Notes |", "|---|---|---:|---|"]
        for r in self.results:
            lines.append(f"| {r.id} | {r.status} | {r.seconds:.1f} | {'; '.join(r.notes)[:120]} |")
        (self.out / name.replace(".json", ".md")).write_text("\n".join(lines) + "\n", encoding="utf-8")

    def _wait(self, check, timeout: float, interval: float = 1.0) -> bool:
        deadline = self.clock() + timeout
        while True:
            if check():
                return True
            if self.clock() >= deadline:
                return False
            time.sleep(interval)


def asdict_result(result: Result) -> dict:
    return {"id": result.id, "status": result.status, "seconds": round(result.seconds, 1),
            "startedMinute": None if result.startedMinute is None else round(result.startedMinute, 2),
            "notes": result.notes, "data": result.data}


def _docker(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], text=True, capture_output=True)


def add_parser(sub) -> None:
    win = sub.add_parser("window", help="Run one trial-window plan: setup, measurements, parity checks, teardown")
    win.add_argument("--plan", type=Path, default=ROOT / "plans" / "window-1.json")
    win.add_argument("--target", choices=TARGETS, default=None,
                     help="ours: dry run against our gateway only. reference: the real window")
    win.add_argument("--phase", choices=("window", "before", "after"), default="window",
                     help="window: the before steps, then the window steps. before: preflight only. after: post-window steps")
    win.add_argument("--only", default=None, help="Run one step id")
    win.add_argument("--from", dest="start_at", default=None, help="Start the window steps at this id")
    win.add_argument("--list", action="store_true", help="Print the runbook table and exit")
    win.add_argument("--out", type=Path, default=None)
    win.add_argument("--oip-install", default=None, help="Our gateway installDist directory")
    win.add_argument("--reference-report", default=None, help="after: the reference ramp report to hold against")
    win.add_argument("--steady-seconds", type=float, default=None, help="after: steady window, default the profile's 2700 s")
    win.add_argument("--db-outage-seconds", type=float, default=30.0)
    win.add_argument("--auto", action="store_true",
                     help="reference: run each step's automation (reference/refauto.py) instead of a terminal prompt")


def window_main(args) -> int:
    plan = load_plan(args.plan)
    if args.list:
        sys.stdout.write(render_runbook(plan))
        return 0
    if args.target is None:
        print("error: pass --target ours (dry run) or --target reference (the real window)", file=sys.stderr)
        return 2
    out = args.out or (ROOT / "out" / plan["name"] / args.target)
    runner = Runner(plan, args.target, out, args)
    import signal

    def _stop(signum, frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, _stop)
    code = runner.run(args.phase, only=args.only, start_at=args.start_at)
    print(f"wrote {out}")
    return code
