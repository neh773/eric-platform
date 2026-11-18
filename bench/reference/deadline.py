"""Backstop for the trial window: remove the reference container before the procedure cap.

    python3 reference/deadline.py --margin-minutes 3 &

Waits for the start record (``state/start.json``), then sleeps until
``startedAt + procedureMinutes - margin`` and runs ``reference_env.py stop``
when the container still exists. It never starts anything, never touches the
trial route and never deletes the start record. The runner's own W91 normally
removes the container first; this only covers a runner that died or stalled.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / "config.json").read_text(encoding="utf-8"))
STATE = HERE / "state" / "start.json"


def deadline(started: datetime, margin_minutes: float) -> datetime:
    return started + timedelta(minutes=float(CONFIG["procedureMinutes"]) - margin_minutes)


def parse(text: str) -> datetime:
    value = datetime.fromisoformat(text.replace("Z", "+00:00"))
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--margin-minutes", type=float, default=3.0)
    parser.add_argument("--wait-for-start-seconds", type=float, default=3600)
    args = parser.parse_args(argv)
    waited = 0.0
    while not STATE.exists():
        if waited > args.wait_for_start_seconds:
            print("no start record appeared; nothing to guard")
            return 0
        time.sleep(5)
        waited += 5
    started = parse(json.loads(STATE.read_text(encoding="utf-8"))["startedAt"])
    when = deadline(started, args.margin_minutes)
    print(f"deadline {when.isoformat()} (start {started.isoformat()})", flush=True)
    while datetime.now(timezone.utc) < when:
        time.sleep(10)
    exists = subprocess.run(["docker", "inspect", "--format", "{{.Id}}", CONFIG["container"]],
                            capture_output=True, text=True).returncode == 0
    if not exists:
        print("container already removed")
        return 0
    proc = subprocess.run([sys.executable, str(HERE / "reference_env.py"), "stop"], capture_output=True, text=True)
    print(f"deadline reached: stop exit {proc.returncode} {proc.stdout.strip()}", flush=True)
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
