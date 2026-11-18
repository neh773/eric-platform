"""Command line for the load ramp and the scaling sweep.

    cd bench
    python3 -m bench self-test
    python3 -m bench ramp --profile profiles/dev.json --target reference \\
        --gateway http://127.0.0.1:8088 --token-file ../secrets/reference-api-token.txt \\
        --container reference --sim-url http://127.0.0.1:28080
    python3 -m bench ramp --profile profiles/dev.json --target oip \\
        --gateway http://127.0.0.1:8090 --container oip-gateway --sim-url http://127.0.0.1:28080
    python3 -m bench scale --profile profiles/scale-dev.json --target oip \\
        --image oip-gateway:local --sim-url http://127.0.0.1:28080
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from bench.dockerbox import (
    allocated_cpus,
    apply_reference_heap,
    container_gateway_ip,
    docker_available,
    heap_mb,
    oip_gateway_command,
    recreate_command,
    sample_container,
    sample_cpu_many,
    update_limits_command,
)
from bench.formula import BenchConfig
from bench.profile import PlanError, load_profile
from bench.ramp import run_ramp
from bench.report import write_report
from bench.headtohead import (
    RESET_TRIAL_NOTE,
    build_dry_run,
    confirm_reset_trial,
    head_to_head_exit,
    hold_profile,
    require_adr_ceilings,
    select_sizes as select_h2h_sizes,
    series_report,
)
from bench.scaling import (
    EFFICIENCY_MIN,
    FORMULA,
    POINTS_PER_SIM_VCPU,
    SimFleet,
    combined_efficiency,
    efficiency,
    heap_for_memory,
    plan_sim_fleet,
    prepare_capacity_profile,
    prepare_scale_profile,
    resolve_sizes,
    select_sizes,
    series_exit_code,
    synthetic_saturated_report,
    tag_capacity_efficiency,
)
from bench.secrets import SecretError, password_env_text, read_admin_password
from bench.simfleet import (
    SIM_IMAGE,
    ConnectionFanout,
    fleet_layout,
    load_sim_hosts,
    place_simulators,
    sim_container_command,
)
from bench.targets import HostSample, HttpTransport, referenceTarget, OipTarget


ROOT = Path(__file__).resolve().parents[1]
# compose.yaml sets `name: oip-bench` and service `points`, so the container is oip-bench-points-1.
DEFAULT_SIM_CONTAINER = "oip-bench-points-1"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="bench", description="High-point gateway load ramp")
    sub = parser.add_subparsers(dest="cmd", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--profile", type=Path, required=True)
    common.add_argument("--target", choices=("reference", "oip"), required=True)
    common.add_argument("--gateway", default=None, help="Gateway base URL. the reference platform defaults to http://127.0.0.1:8088, ours to http://127.0.0.1:8090")
    common.add_argument("--token-file", type=Path, default=None, help="the reference platform API token. Not the admin password.")
    common.add_argument("--container", default=None, help="Docker name of the gateway under test, for CPU and RSS")
    common.add_argument(
        "--sim-container",
        default=None,
        help=f"Simulator container for CPU. Default is `docker compose ps`, or {DEFAULT_SIM_CONTAINER}",
    )
    common.add_argument("--sim-url", default="http://127.0.0.1:28080")
    common.add_argument("--sim-host", default="auto", help="Hostname the gateway uses to reach the simulator. auto reads the container's bridge gateway")
    common.add_argument("--modbus-port", type=int, default=25020)
    common.add_argument("--opcua-port", type=int, default=24840)
    common.add_argument("--opcua-read-endpoint", default=None, help="OPC UA endpoint for tag reads. the reference platform default is opc.tcp://127.0.0.1:62541")
    common.add_argument("--read-via-opcua", action="store_true", help="Read our gateway through its OPC UA server (M2.4). Off until that server exists")
    common.add_argument("--trial-seconds-left", type=float, default=None, help="Remaining the reference platform trial seconds, used when GET /data/api/v1/trial does not answer")
    common.add_argument("--delete-tags", action="store_true", help="the reference platform only. Delete and recreate the tag provider. This removes every tag in it")
    common.add_argument("--cpus", type=float, default=None, help="Cgroup CPU quota applied to the gateway container before the ramp")
    common.add_argument("--memory", default=None, help="Cgroup memory limit applied to the gateway container, for example 4g")
    common.add_argument("--reference-heap", default=None, help="the reference platform JVM max memory, the image -m argument, in MB or with a g/m suffix. Recreates the container")
    common.add_argument("--oip-user", default=None, help="Our gateway: user for POST /api/v1/auth/login and the OPC UA username token. Defaults to admin when a password file is set")
    common.add_argument("--oip-password-file", type=Path, default=None, help="Our gateway: password file or env file with OIP_ADMIN_PASSWORD. Never printed and never passed as a process argument")
    common.add_argument("--oip-heap", default=None, help="scale: pin -Xmx for our gateway instead of scaling it with the tier memory")
    common.add_argument("--out", type=Path, default=None)
    common.add_argument("--start-sim", action="store_true")
    common.add_argument("--no-cleanup-sim", action="store_true")

    ramp = sub.add_parser("ramp", parents=[common])
    scale = sub.add_parser("scale", parents=[common])
    scale.add_argument("--image", default="oip-gateway:local")
    scale.add_argument("--sizes", default=None, help="Override profile sizes, for example 2:768m,4:1024m")
    scale.add_argument("--publish", default="8090:8088")
    scale.add_argument("--dry-run", action="store_true", help="Write the efficiency table from a scripted series. No docker and no gateway")
    scale.add_argument(
        "--metric",
        choices=("throughput", "tag-capacity", "both"),
        default="throughput",
        help="PRF-002 metric. both writes E(n) for throughput and for tag capacity",
    )
    scale.add_argument("--capacity-profile", type=Path, default=None, help="Tag-capacity profile. Default is profiles/scale-capacity.json")
    scale.add_argument("--sim-hosts", type=Path, default=None, help="JSON hosts for the simulator fleet, including a second machine")
    scale.add_argument("--sim-headroom", type=float, default=None, help="Fleet size multiplier. Default 2 so the simulator is not the cutoff")
    h2h = sub.add_parser("head-to-head", help="PRF-001: one the reference platform trial per size, then our gateway at the last good load")
    h2h.add_argument("--profile", type=Path, default=None)
    h2h.add_argument("--size", default="all", help="S2,S4,S8,S16,EDGE. Default is all five, one trial each")
    h2h.add_argument("--dry-run", action="store_true", help="Write a report per size without docker, the reference platform, or a pause on stdin")
    h2h.add_argument("--out", type=Path, default=None)
    h2h.add_argument("--reset-confirmed", action="store_true", help="Operator already performed a manual license reset. The harness still does not call it")
    h2h.add_argument("--gateway", default=None, help="the reference platform base URL. Default http://127.0.0.1:8088")
    h2h.add_argument("--oip-gateway", default=None, help="Our gateway base URL. Default http://127.0.0.1:8090")
    h2h.add_argument("--container", default=None, help="the reference platform container name")
    h2h.add_argument("--token-file", type=Path, default=None)
    h2h.add_argument("--oip-password-file", type=Path, default=None)
    h2h.add_argument("--oip-user", default=None)
    h2h.add_argument("--image", default="oip-gateway:local")
    h2h.add_argument("--publish", default="8090:8088")
    h2h.add_argument("--start-sim", action="store_true")
    h2h.add_argument("--sim-url", default="http://127.0.0.1:28080")
    h2h.add_argument("--sim-host", default="auto")
    h2h.add_argument("--sim-container", default=None)
    h2h.add_argument("--sim-hosts", type=Path, default=None)
    h2h.add_argument("--sim-headroom", type=float, default=None)
    h2h.add_argument("--modbus-port", type=int, default=25020)
    h2h.add_argument("--opcua-port", type=int, default=24840)
    h2h.add_argument("--opcua-read-endpoint", default=None)
    h2h.add_argument("--read-via-opcua", action="store_true")
    h2h.add_argument("--trial-seconds-left", type=float, default=None)
    h2h.add_argument("--delete-tags", action="store_true")
    h2h.add_argument("--no-cleanup-sim", action="store_true")
    sub.add_parser("self-test")
    prf = sub.add_parser(
        "prf012",
        help="Check the high-point simulator on a non-loopback address (PRF-012)",
    )
    prf.add_argument("--points", type=int, default=None, help="Address-space size. Default is fullPoints from test-infra/perf/prf-012.json")
    prf.add_argument("--ci", action="store_true", help="Use ciPoints from that config (the Simulator stack smoke size)")
    up = sub.add_parser("sim-up")
    up.add_argument("--points", default=None)
    sub.add_parser("sim-down")
    hw = sub.add_parser(
        "hardware",
        help="ADR 0010 hardware plan. One command, our gateway and the comparison VM. Dry-run stays on the local simulator.",
    )
    hw.add_argument("plan", nargs="?", choices=("P2-04", "P2-05", "P3-04", "P4-01", "P4-14", "P4-15"))
    hw.add_argument("--all", action="store_true", help="Prepare every hardware plan")
    hw.add_argument("--dry-run", action="store_true", help="Local simulator only. No comparison VM.")
    hw.add_argument("--measure", action="store_true", help="Reference-host form. Refuses to dial the comparison VM.")
    hw.add_argument("--out", type=Path, default=None)
    from bench.window import add_parser as add_window_parser
    add_window_parser(sub)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.cmd == "self-test":
            return _self_test()
        if args.cmd == "prf012":
            from bench.prf012 import main as prf012_main
            return prf012_main(args)
        if args.cmd == "sim-up":
            return _compose("up", args.points)
        if args.cmd == "sim-down":
            return _compose("down", None)
        if args.cmd == "ramp":
            return _ramp(args)
        if args.cmd == "scale":
            return _scale(args)
        if args.cmd == "head-to-head":
            return _head_to_head(args)
        if args.cmd == "hardware":
            from bench.hardware import hardware_main
            return hardware_main(args)
        if args.cmd == "window":
            from bench.window import window_main
            return window_main(args)
    except PlanError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 2


def _self_test() -> int:
    sim = ROOT / "sim" / "server.py"
    return subprocess.call([sys.executable, str(sim), "--self-test"], cwd=ROOT)


def _compose(action: str, points: str | None) -> int:
    env = os.environ.copy()
    if points:
        env["BENCH_POINTS"] = points
    cmd = ["docker", "compose", "-f", str(ROOT / "compose.yaml")]
    if action == "up":
        cmd += ["up", "-d", "--build", "--wait"]
    else:
        cmd += ["down", "-v"]
    return subprocess.call(cmd, env=env)


def _ramp(args) -> int:
    profile = load_profile(args.profile)
    _bind_sim_container(args)
    out = args.out or (ROOT / "out" / profile.name / args.target)
    sim_proc = None
    if args.start_sim:
        if _compose("up", str(max(profile.points))) != 0:
            return 1
        _wait_sim(args.sim_url)
    try:
        limit_note = _apply_limits(args)
        sim_host = _sim_host(args)
        target = _target(args, profile, sim_host)
        if limit_note:
            target.notes.append(limit_note)
        trial = _trial_seconds(args, target)
        if (
            args.target == "reference"
            and args.trial_seconds_left is None
            and trial is None
            and not getattr(target, "trial_licensed", False)
        ):
            target.notes.append(
                "GET /data/api/v1/trial did not return trialSecondsLeft. "
                "Pass --trial-seconds-left to keep the run inside the remaining trial. "
                "a manual license reset is not called."
            )
        report = run_ramp(profile, target, sim=_Sim(args.sim_url, profile), wall_seconds=trial)
        report["simHost"] = sim_host
        report["gateway"] = _gateway_url(args)
        json_path, md_path = write_report(report, out)
        print(f"stopped: {report['stoppedBecause']}")
        print(f"wrote {json_path}")
        print(f"wrote {md_path}")
        return 0 if report["stoppedBecause"] != "error" else 1
    finally:
        if args.start_sim and not args.no_cleanup_sim:
            _compose("down", None)
        del sim_proc


def _scale(args) -> int:
    if args.target == "oip" and args.reference_heap is not None:
        raise PlanError("--reference-heap is the reference platform image -m argument. Our gateway uses --oip-heap, or a heap taken from the tier memory.")
    profile = load_profile(args.profile)
    require_adr_ceilings(profile)
    metric = getattr(args, "metric", "throughput") or "throughput"
    try:
        sizes = select_sizes(resolve_sizes(args.sizes, profile.raw.get("sizes")), args.cpus, args.memory)
    except ValueError as exc:
        raise PlanError(str(exc)) from exc
    for size in sizes:
        if not size.memory:
            raise PlanError(f"size {size.cpus} vCPU has no memory limit. Use cpus:memory, for example 2:4g.")
    if metric == "tag-capacity":
        workload = prepare_capacity_profile(profile)
        capacity_profile = None
    else:
        workload = prepare_scale_profile(profile)
        capacity_profile = _capacity_profile(args) if metric == "both" else None
    if capacity_profile is not None:
        require_adr_ceilings(capacity_profile)
    if getattr(args, "dry_run", False):
        return _scale_dry(args, profile, sizes, workload, capacity_profile)
    _bind_sim_container(args)
    out = args.out or (ROOT / "out" / "scale" / profile.name)
    hosts, fleet_meta = _load_hosts(args)
    point_cap = max(workload.points)
    if capacity_profile is not None:
        point_cap = max(point_cap, max(capacity_profile.points))
    fleet = plan_sim_fleet(
        max(size.cpus for size in sizes),
        point_cap,
        headroom=fleet_meta["headroom"],
        points_per_vcpu=fleet_meta["pointsPerVcpu"],
    )
    fanout = None
    sim_names: list = []
    sim_urls = [args.sim_url]
    password = _oip_password(args)
    env_file = _write_password_env(password) if password is not None else None
    try:
        placements = None
        if args.start_sim:
            sim_names, sim_urls, fanout, layout = _start_sim_fleet(args, fleet, point_cap, hosts)
            placements = layout if hosts else None
        reports, ran, notes, trial_left = _scale_series(args, sizes, workload, fleet, sim_names, sim_urls, env_file, placements)
        if trial_left is not None:
            args.trial_seconds_left = trial_left
        rows = efficiency(reports, [size.cpus for size in ran])
        for row, size in zip(rows, ran):
            row["memory"] = size.memory
            row["heap"] = _tier_heap(args, size)
        capacity_rows = None
        combined = None
        if capacity_profile is not None:
            cap_reports, cap_ran, cap_notes, _trial_left = _scale_series(
                args, sizes, capacity_profile, fleet, sim_names, sim_urls, env_file, placements,
            )
            notes.extend(cap_notes)
            capacity_rows = tag_capacity_efficiency(cap_reports, [size.cpus for size in cap_ran])
            for row, size in zip(capacity_rows, cap_ran):
                row["memory"] = size.memory
                row["heap"] = _tier_heap(args, size)
            combined = combined_efficiency(rows, capacity_rows)
            for row, size in zip(combined, ran):
                row["memory"] = size.memory
        summary = _scale_summary(args, profile, workload, fleet, rows, reports, notes, capacity_profile, capacity_rows, combined)
        json_path, md_path = write_report(summary, out)
        print(f"wrote {json_path}")
        print(f"wrote {md_path}")
        code = series_exit_code(rows)
        if capacity_rows is not None:
            code = _worse_exit(code, series_exit_code(capacity_rows))
        if code == 1:
            print("efficiency below 0.70", file=sys.stderr)
        elif code == 2:
            print("efficiency was not measured (no cutoff, or the simulator saturated)", file=sys.stderr)
        return code
    finally:
        if fanout is not None:
            fanout.stop()
        for entry in sim_names:
            _stop_sim(entry)
        _remove_container("oip-bench-gw")
        if env_file is not None:
            try:
                env_file.unlink()
            except OSError:
                pass


def _scale_series(args, sizes, workload, fleet, sim_names, sim_urls, env_file, placements):
    reports = []
    ran: list = []
    notes: list[str] = []
    trial_left = args.trial_seconds_left
    for size in sizes:
        if trial_left is not None and trial_left <= 0:
            notes.append(
                "Stopped before the next tier because the reference platform trial window is exhausted. "
                "a manual license reset is manual and is not called."
            )
            break
        heap = _tier_heap(args, size)
        container = _start_tier(args, size, heap, env_file)
        hold = argparse.Namespace(**vars(args))
        hold.container = container
        hold.target = args.target
        if args.start_sim and args.target == "oip":
            if placements:
                hold.modbus_endpoints = [(row["gatewayAddress"], row["modbusPort"]) for row in placements]
                hold.modbus_ports = [row["modbusPort"] for row in placements]
            else:
                hold.modbus_ports = [row["modbusPort"] for row in fleet_layout(fleet.instances, modbus_base=args.modbus_port)]
        sim_host = _sim_host(hold)
        target = _target(hold, workload, sim_host)
        if trial_left is None and args.target == "reference":
            trial_left = _trial_seconds(hold, target)
        if trial_left is not None and trial_left <= 0:
            notes.append(
                "Stopped before the next tier because the reference platform trial window is exhausted. "
                "a manual license reset is manual and is not called."
            )
            break
        sim_for_sample = sim_names or args.sim_container
        report = run_ramp(
            workload,
            target,
            sim=_Sim(sim_urls, workload),
            sampler=_sampler(container, sim_for_sample, size.cpus, target),
            wall_seconds=trial_left,
            stop_when_sim_saturated=True,
        )
        report["size"] = {"cpus": size.cpus, "memory": size.memory, "heap": heap}
        reports.append(report)
        ran.append(size)
        if args.target == "oip":
            _remove_container("oip-bench-gw")
        if trial_left is not None:
            trial_left = max(0.0, float(trial_left) - float(report.get("elapsedSeconds") or 0))
        if report.get("stoppedBecause") == "error":
            notes.append("Stopped the series because a tier ended in an error.")
            break
    return reports, ran, notes, trial_left


def _scale_summary(args, profile, workload, fleet, rows, reports, notes, capacity_profile, capacity_rows, combined) -> dict:
    summary = {
        "kind": "scale",
        "target": args.target,
        "profile": profile.name,
        "formula": FORMULA,
        "efficiencyMin": EFFICIENCY_MIN,
        "baselineCpus": rows[0]["baselineCpus"] if rows else None,
        "metric": getattr(args, "metric", "throughput"),
        "dryRun": False,
        "workload": {
            "percentChanging": workload.percent_changing,
            "periodMs": workload.period_ms[0],
            "points": workload.points,
        },
        "simulatorFleet": {
            "instances": fleet.instances,
            "cpusEach": fleet.cpus_each,
            "memory": fleet.memory,
            "pointsPerVcpu": fleet.points_per_vcpu,
            "started": bool(args.start_sim),
        },
        "sizes": rows,
        "runs": reports,
        "notes": list(notes),
    }
    if capacity_profile is not None:
        summary["capacityProfile"] = capacity_profile.name
        summary["tagCapacity"] = capacity_rows
        summary["combined"] = combined
    for row in rows:
        if row.get("meetsMinimum") is False:
            summary["notes"].append(
                f"{row['cpus']} vCPU throughput E(n) {row['efficiency']:.3f} is below {EFFICIENCY_MIN:.2f}."
            )
    if capacity_rows:
        for row in capacity_rows:
            if row.get("meetsMinimum") is False:
                summary["notes"].append(
                    f"{row['cpus']} vCPU tag-capacity E(n) {row['efficiency']:.3f} is below {EFFICIENCY_MIN:.2f}."
                )
    return summary


def _scale_dry(args, profile, sizes, workload, capacity_profile) -> int:
    """Scripted S2/S4/S8/S16-shaped tiers. CI can run this without docker."""
    kind = "capacity" if getattr(args, "metric", "throughput") == "tag-capacity" else "throughput"
    cpus = [size.cpus for size in sizes]
    reports = [synthetic_saturated_report(cpu, kind=kind, efficiency_target=1.0) for cpu in cpus]
    if kind == "capacity":
        rows = tag_capacity_efficiency(reports, cpus)
    else:
        rows = efficiency(reports, cpus)
    for row, size in zip(rows, sizes):
        row["memory"] = size.memory
    capacity_rows = None
    combined = None
    if capacity_profile is not None:
        cap_reports = [synthetic_saturated_report(cpu, kind="capacity", efficiency_target=1.0) for cpu in cpus]
        capacity_rows = tag_capacity_efficiency(cap_reports, cpus)
        for row, size in zip(capacity_rows, sizes):
            row["memory"] = size.memory
        combined = combined_efficiency(rows, capacity_rows)
        for row, size in zip(combined, sizes):
            row["memory"] = size.memory
    summary = {
        "kind": "scale",
        "target": args.target,
        "profile": profile.name,
        "formula": FORMULA,
        "efficiencyMin": EFFICIENCY_MIN,
        "baselineCpus": rows[0]["baselineCpus"] if rows else None,
        "metric": getattr(args, "metric", "throughput"),
        "dryRun": True,
        "measured": False,
        "workload": {
            "percentChanging": workload.percent_changing,
            "periodMs": workload.period_ms[0],
            "points": list(workload.points),
        },
        "sizes": rows,
        "notes": [
            "Dry-run efficiency is scripted at E(n) = 1 so the table and the 0.70 check run in CI. "
            "It is not a PRF-002 measurement.",
        ],
    }
    if capacity_profile is not None:
        summary["capacityProfile"] = capacity_profile.name
        summary["tagCapacity"] = capacity_rows
        summary["combined"] = combined
        summary["notes"].append(
            "Tag capacity uses the same E(n) on the last good point count of a 10% changing, 1 s ramp."
        )
    out = args.out or (ROOT / "out" / "scale" / f"{profile.name}-dry")
    json_path, md_path = write_report(summary, out)
    print(f"wrote {json_path}")
    print(f"wrote {md_path}")
    judged = combined if combined is not None else rows
    return series_exit_code(judged)


def _worse_exit(left: int, right: int) -> int:
    """A miss of 0.70 outranks an unmeasured tier, which outranks a pass."""
    if 1 in (left, right):
        return 1
    if 2 in (left, right):
        return 2
    return 0


def _capacity_profile(args):
    path = getattr(args, "capacity_profile", None) or (ROOT / "profiles" / "scale-capacity.json")
    loaded = load_profile(path)
    return prepare_capacity_profile(loaded)


def _load_hosts(args):
    headroom = 2.0
    points_per = POINTS_PER_SIM_VCPU
    override = getattr(args, "sim_headroom", None)
    if override is not None:
        if override < 1:
            raise PlanError("sim headroom must be >= 1")
        headroom = float(override)
    path = getattr(args, "sim_hosts", None)
    if not path:
        return None, {"headroom": headroom, "pointsPerVcpu": points_per}
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise PlanError(f"could not read sim hosts {path}: {exc}") from exc
    try:
        hosts, meta = load_sim_hosts(data)
    except ValueError as exc:
        raise PlanError(str(exc)) from exc
    if override is not None:
        meta["headroom"] = headroom
    return hosts, meta


def _head_to_head(args) -> int:
    try:
        sizes = select_h2h_sizes(args.size)
    except PlanError:
        raise
    if args.dry_run:
        reports = build_dry_run(sizes, stdin=sys.stdin, stdout=sys.stdout)
        return _write_head_reports(args, reports, dry_run=True)
    return _head_to_head_live(args, sizes)


def _write_head_reports(args, reports, *, dry_run: bool) -> int:
    out = args.out or (ROOT / "out" / "head-to-head")
    index = series_report(reports, dry_run=dry_run)
    write_report(index, out)
    for report in reports:
        write_report(report, out / report["size"]["id"])
    print(f"wrote {out / 'report.json'}")
    for report in reports:
        load = report.get("lastGoodLoad") or {}
        ours = report.get("ours") or {}
        print(
            f"{report['size']['id']}: last-good {load.get('points')} points "
            f"at {_fmt(load.get('valuesPerSec'))} values/s; "
            f"ours {ours.get('points')} points at {_fmt(ours.get('valuesPerSec'))} values/s"
        )
    return head_to_head_exit(reports, dry_run=dry_run)


def _fmt(value) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def _head_to_head_live(args, sizes) -> int:
    """Ramp the reference platform to a cutoff, then hold our gateway at that load. One trial per size."""
    profile_path = args.profile or (ROOT / "profiles" / "h2h.json")
    profile = load_profile(profile_path)
    require_adr_ceilings(profile)
    if not args.container:
        raise PlanError("head-to-head needs --container, the reference platform container")
    # the reference platform is one device per host IP, so the fleet publishes the offset ports
    # and a local fanout. Our gateway then dials those same ports directly.
    args.target = "reference"
    _bind_sim_container(args)
    hosts, fleet_meta = _load_hosts(args)
    fleet = plan_sim_fleet(
        max(size["vcpu"] for size in sizes),
        max(profile.points),
        headroom=fleet_meta["headroom"],
        points_per_vcpu=fleet_meta["pointsPerVcpu"],
    )
    fanout = None
    sim_names: list = []
    sim_urls = [args.sim_url]
    password = _oip_password(args)
    env_file = _write_password_env(password) if password is not None else None
    reports = []
    try:
        layout = None
        if args.start_sim:
            sim_names, sim_urls, fanout, layout = _start_sim_fleet(args, fleet, max(profile.points), hosts)
        for size in sizes:
            pause = confirm_reset_trial(
                size["id"],
                stdin=sys.stdin,
                stdout=sys.stdout,
                dry_run=False,
                preconfirmed=bool(args.reset_confirmed),
            )
            reference_args = _phase_args(args, "reference", size, args.container, args.gateway)
            _apply_limits(reference_args)
            sim_host = _sim_host(reference_args)
            target = _target(reference_args, profile, sim_host)
            trial = _trial_seconds(reference_args, target)
            steady_seconds = float(profile.raw.get("steadySeconds") or (45 * 60))
            needed = profile.planned_seconds() + steady_seconds
            if trial is not None and trial < needed:
                raise PlanError(
                    f"the reference platform size {size['id']} needs about {needed:.0f}s and the trial has {trial:.0f}s left. "
                    f"{RESET_TRIAL_NOTE}"
                )
            ramp = run_ramp(
                profile,
                target,
                sim=_Sim(sim_urls, profile),
                sampler=_sampler(reference_args.container, sim_names or args.sim_container, size["vcpu"], target),
                wall_seconds=trial,
                stop_when_sim_saturated=True,
            )
            from bench.headtohead import assemble_size_report, last_good_load
            load = last_good_load(ramp)
            oip_report = None
            if load and load.get("points"):
                oip_container = _start_tier(
                    _phase_args(args, "oip", size, "oip-bench-gw", args.oip_gateway),
                    _size_obj(size),
                    heap_for_memory(size["memory"]),
                    env_file,
                )
                oip_args = _phase_args(args, "oip", size, oip_container, args.oip_gateway)
                if layout:
                    oip_args.modbus_ports = [row["modbusPort"] for row in layout]
                    if hosts:
                        oip_args.modbus_endpoints = [
                            (row["gatewayAddress"], row["modbusPort"]) for row in layout
                        ]
                oip_target = _target(oip_args, profile, _sim_host(oip_args))
                hold = hold_profile(
                    profile,
                    int(load["points"]),
                    sample_seconds=steady_seconds,
                    sample_interval=10,
                )
                oip_report = run_ramp(
                    hold,
                    oip_target,
                    sim=_Sim(sim_urls, hold),
                    sampler=_sampler(oip_container, sim_names or args.sim_container, size["vcpu"], oip_target),
                    collect_samples=True,
                    stop_when_sim_saturated=True,
                )
                _remove_container("oip-bench-gw")
            reports.append(assemble_size_report(size, ramp, oip_report, pause, dry_run=False))
        return _write_head_reports(args, reports, dry_run=False)
    finally:
        if fanout is not None:
            fanout.stop()
        for entry in sim_names:
            _stop_sim(entry)
        _remove_container("oip-bench-gw")
        if env_file is not None:
            try:
                env_file.unlink()
            except OSError:
                pass


def _phase_args(args, target: str, size: dict, container: str, gateway: str | None):
    """Namespace the existing ramp helpers already understand, aimed at one product and one size."""
    phase = argparse.Namespace(**vars(args))
    phase.target = target
    phase.container = container
    phase.cpus = size["vcpu"]
    phase.memory = size["memory"]
    phase.gateway = gateway
    phase.reference_heap = heap_for_memory(size["memory"]) if target == "reference" else None
    phase.oip_heap = None
    if not hasattr(phase, "sim_container"):
        phase.sim_container = None
    return phase


def _size_obj(size: dict):
    from bench.scaling import Size
    return Size(float(size["vcpu"]), str(size["memory"]))


class _Sim:
    def __init__(self, url, profile) -> None:
        urls = url if isinstance(url, (list, tuple)) else [url]
        self.urls = [item.rstrip("/") for item in urls]
        self.profile = profile

    def update(self, cfg: BenchConfig) -> None:
        body = json.dumps({
            "points": cfg.points,
            "percentChanging": cfg.percent_changing,
            "periodMs": cfg.period_ms,
            "epochMs": cfg.epoch_ms,
            "registersPerUnit": cfg.registers_per_unit,
            "metricsPerDevice": cfg.metrics_per_device,
            "frozen": False,
        }).encode()
        for url in self.urls:
            req = urllib.request.Request(
                url + "/control", data=body, headers={"content-type": "application/json"}, method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    resp.read()
            except Exception as exc:
                raise PlanError(f"simulator control {url}/control failed: {exc}") from exc


def _sampler(gateway: str | None, sim: str | None, cpus: float | None, target):
    def sample() -> HostSample:
        host = HostSample()
        if gateway and docker_available():
            try:
                host = sample_container(gateway, cpus)
            except (subprocess.CalledProcessError, ValueError, IndexError):
                host = HostSample()
        # Each simulator process is capped at one core. 100% of that quota is one busy core.
        sim_cpu = _sim_cpu_percent(sim)
        if sim_cpu is not None:
            host.sim_cpu_percent = sim_cpu
        extra = target.sample_host()
        if host.heap_bytes is None:
            host.heap_bytes = extra.heap_bytes
        if host.heap_max_bytes is None:
            host.heap_max_bytes = extra.heap_max_bytes
        if host.drift_gauge_ms is None:
            host.drift_gauge_ms = extra.drift_gauge_ms
        if host.cpu_percent_of_quota is None:
            host.cpu_percent_of_quota = extra.cpu_percent_of_quota
        if host.clock_drift_percent is None:
            host.clock_drift_percent = extra.clock_drift_percent
        return host
    return sample


def _target(args, profile, sim_host: str):
    url = _gateway_url(args)
    token = _token(args)
    transport = HttpTransport(url, token=token)

    class _Merged:
        def __init__(self, inner):
            self.inner = inner
            self.notes = inner.notes
            self.name = args.target

        def setup(self, cfg):
            self.inner.setup(cfg)

        def apply_step(self, points, period_ms, protocol, cfg):
            self.inner.apply_step(points, period_ms, protocol, cfg)

        def read_values(self, indexes, now_ms):
            return self.inner.read_values(indexes, now_ms)

        def sample_host(self):
            return sampler_with(self.inner)

        def cleanup(self):
            self.inner.cleanup()

        def step_metrics(self):
            query = getattr(self.inner, "step_metrics", None)
            return query() if query is not None else {}

        def trial_seconds_left(self):
            query = getattr(self.inner, "trial_seconds_left", None)
            if query is None:
                return None
            return query()

        @property
        def trial_licensed(self):
            return bool(getattr(self.inner, "trial_licensed", False))

    def sampler_with(inner) -> HostSample:
        host = HostSample()
        if args.container and docker_available():
            try:
                host = sample_container(args.container, allocated_cpus(args.container) if args.container else None)
            except (subprocess.CalledProcessError, ValueError, IndexError):
                host = HostSample()
        sim_cpu = _sim_cpu_percent(args.sim_container)
        if sim_cpu is not None:
            host.sim_cpu_percent = sim_cpu
        extra = inner.sample_host()
        if host.heap_bytes is None:
            host.heap_bytes = extra.heap_bytes
        if host.heap_max_bytes is None:
            host.heap_max_bytes = extra.heap_max_bytes
        if host.drift_gauge_ms is None:
            host.drift_gauge_ms = extra.drift_gauge_ms
        if host.cpu_percent_of_quota is None:
            host.cpu_percent_of_quota = extra.cpu_percent_of_quota
        if host.memory_percent is None and extra.memory_percent is not None:
            host.memory_percent = extra.memory_percent
        if host.clock_drift_percent is None:
            host.clock_drift_percent = extra.clock_drift_percent
        return host

    if args.target == "reference":
        endpoint = args.opcua_read_endpoint or str(
            profile.reference.get("opcuaReadEndpoint") or "opc.tcp://127.0.0.1:62541"
        )
        inner = referenceTarget(
            transport, sim_host, args.modbus_port, args.opcua_port,
            provider=str(profile.reference.get("provider") or "default"),
            batch=profile.tag_batch,
            opcua_read_endpoint=endpoint,
            delete_tags=bool(args.delete_tags or profile.reference.get("deleteTags")),
        )
    else:
        ports = list(getattr(args, "modbus_ports", None) or [])
        if not ports:
            ports = [args.modbus_port + i for i in range(profile.modbus_extra_ports + 1)]
        endpoint = args.opcua_read_endpoint or profile.oip.get("opcuaReadEndpoint")
        password = _oip_password(args)
        username = args.oip_user
        if password is not None and not username:
            username = "admin"
        inner = OipTarget(
            transport, sim_host, ports, args.opcua_port,
            require_devices=profile.require_devices,
            read_via_opcua=bool(args.read_via_opcua or profile.oip.get("readViaOpcUa")),
            opcua_read_endpoint=None if endpoint is None else str(endpoint),
            opcua_node_template=str(profile.oip.get("opcuaNodeId") or "ns=2;s=Tags/{provider}/{path}"),
            username=username,
            password=password,
            tag_batch=profile.tag_batch,
            modbus_endpoints=getattr(args, "modbus_endpoints", None),
            history=_history_option(profile.oip.get("history")),
        )
    # Avoid the target also scraping docker inside sample_host.
    inner.docker = None
    return _Merged(inner)


def _history_option(value) -> dict | None:
    """``oip.history`` in a profile: true or an object of HistorySettings spec defaults. Off when absent."""
    if value is None or value is False:
        return None
    if value is True:
        return {}
    if isinstance(value, dict):
        return dict(value)
    raise PlanError("oip.history must be true, false, or an object of HistorySettings spec fields")


def resolve_sim_container(explicit: str | None, compose_names: list[str] | None = None) -> str:
    """Use an explicit name, else the running compose service, else the name compose would assign."""
    if explicit:
        return explicit
    for name in compose_names or []:
        if name.strip():
            return name.strip()
    return DEFAULT_SIM_CONTAINER


def _bind_sim_container(args) -> None:
    if args.sim_container:
        return
    args.sim_container = resolve_sim_container(None, _compose_container_names())


def _compose_container_names() -> list[str]:
    if not docker_available():
        return []
    proc = subprocess.run(
        ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "ps", "--format", "{{.Name}}"],
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def _trial_seconds(args, target) -> float | None:
    if args.trial_seconds_left is not None:
        if args.trial_seconds_left < 0:
            raise PlanError("--trial-seconds-left must be >= 0")
        return float(args.trial_seconds_left)
    query = getattr(target, "trial_seconds_left", None)
    if args.target != "reference" or query is None:
        return None
    return query()


def _apply_limits(args) -> str | None:
    if args.cpus is None and args.memory is None and args.reference_heap is None:
        return None
    if not args.container:
        raise PlanError("--cpus, --memory, and --reference-heap need --container, the gateway container to limit")
    if args.reference_heap is not None and args.target != "reference":
        raise PlanError(
            "--reference-heap sets the reference platform image -m runtime argument. "
            "The oip target takes the same --cpus and --memory cgroup limit; its JVM follows that limit."
        )
    if not docker_available():
        raise PlanError("docker is required to apply container limits")
    if args.reference_heap is None:
        _check(update_limits_command(args.container, args.cpus, args.memory))
        return _limits_note(args, None)
    megabytes = heap_mb(args.reference_heap)
    try:
        raw = subprocess.check_output(["docker", "inspect", args.container], text=True, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError as exc:
        raise PlanError(f"docker inspect {args.container} failed with status {exc.returncode}") from exc
    payload = json.loads(raw)
    if not isinstance(payload, list) or not payload:
        raise PlanError(f"docker inspect {args.container} returned no container")
    command = recreate_command(payload[0], cpus=args.cpus, memory=args.memory, heap_mb_value=megabytes)
    try:
        apply_reference_heap(args.container, command, _check)
    except PlanError:
        raise
    except subprocess.CalledProcessError as exc:
        raise PlanError(f"replacing {args.container} with a new heap failed with status {exc.returncode}") from exc
    _wait_gateway(_gateway_url(args))
    return _limits_note(args, megabytes)


def _limits_note(args, megabytes: int | None) -> str:
    parts = [f"cpus={args.cpus}", f"memory={args.memory}"]
    if megabytes is not None:
        parts.append(f"referenceHeapMb={megabytes}")
    return f"Applied container limits {', '.join(parts)} to {args.container}."


def _check(command: list[str]) -> None:
    try:
        subprocess.check_call(command)
    except subprocess.CalledProcessError as exc:
        verb = command[1] if len(command) > 1 else command[0]
        raise PlanError(f"docker {verb} failed with status {exc.returncode}") from exc


def _gateway_url(args) -> str:
    if args.gateway:
        return args.gateway.rstrip("/")
    if args.target == "reference":
        return "http://127.0.0.1:8088"
    return "http://127.0.0.1:8090"


def _token(args) -> str | None:
    if args.target != "reference":
        return None
    path = args.token_file
    if path is None:
        default = ROOT.parent / "secrets" / "reference-api-token.txt"
        path = default if default.exists() else None
    if path is None:
        env = os.environ.get("REFERENCE_API_TOKEN")
        if env:
            return env.strip()
        raise PlanError(
            "the reference platform needs an API token (Platform > Security > API Keys). "
            "Pass --token-file or set REFERENCE_API_TOKEN. Do not use the admin password. "
            "If the key requires secure connections, use an https:// gateway URL."
        )
    return path.read_text().strip()


def _sim_host(args) -> str:
    if args.sim_host and args.sim_host != "auto":
        return args.sim_host
    if args.container:
        found = container_gateway_ip(args.container)
        if found:
            return found
    return "127.0.0.1"


def _wait_sim(url: str) -> None:
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url + "/health", timeout=2) as resp:
                if resp.status == 200:
                    return
        except Exception:
            time.sleep(0.5)
    raise PlanError(f"simulator did not become healthy at {url}")


def _wait_gateway(url: str) -> None:
    deadline = time.time() + 90
    while time.time() < deadline:
        for path in ("/api/v1/health", "/StatusPing"):
            try:
                with urllib.request.urlopen(url + path, timeout=2) as resp:
                    if resp.status == 200:
                        return
            except Exception:
                pass
        time.sleep(0.5)
    raise PlanError(f"gateway did not become healthy at {url}")


def _run_gateway(image: str, cpus: float, memory: str, publish: str, heap: str, env_file: Path | None) -> None:
    if not docker_available():
        raise PlanError("docker is not available; scaling mode runs one limited gateway container at a time")
    _check(oip_gateway_command(
        image=image,
        cpus=cpus,
        memory=memory,
        publish=publish,
        heap=heap,
        env_file=None if env_file is None else str(env_file),
    ))


def _remove_container(name: str, docker_host: str | None = None) -> None:
    if not docker_available():
        return
    command = ["docker"]
    if docker_host:
        command += ["-H", docker_host]
    command += ["rm", "-v", "-f", name]
    subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _stop_sim(entry) -> None:
    if isinstance(entry, dict):
        _remove_container(entry["name"], entry.get("dockerHost"))
    else:
        _remove_container(entry)


def _ensure_remote_image(image: str, docker_host: str) -> None:
    """Copy the local simulator image to a second docker host. The image is ours."""
    save = subprocess.Popen(["docker", "save", image], stdout=subprocess.PIPE)
    load = subprocess.Popen(["docker", "-H", docker_host, "load"], stdin=save.stdout)
    if save.stdout is not None:
        save.stdout.close()
    load_rc = load.wait()
    save_rc = save.wait()
    if load_rc != 0 or save_rc != 0:
        raise PlanError(f"copying {image} to {docker_host} failed")


def _sim_groups(sim) -> list[tuple[str | None, list[str]]]:
    if not sim:
        return []
    if isinstance(sim, str):
        return [(None, [sim])]
    if isinstance(sim, dict):
        return [(sim.get("dockerHost"), [sim["name"]])]
    if sim and isinstance(sim[0], dict):
        groups: dict[str | None, list[str]] = {}
        for row in sim:
            groups.setdefault(row.get("dockerHost"), []).append(row["name"])
        return list(groups.items())
    return [(None, [name for name in sim if isinstance(name, str) and name])]


def _sim_cpu_percent(sim) -> float | None:
    if not sim or not docker_available():
        return None
    values: list[float] = []
    for docker_host, names in _sim_groups(sim):
        if not names:
            continue
        if docker_host:
            values.extend(sample_cpu_many(names, 1.0, docker_host=docker_host))
        else:
            values.extend(sample_cpu_many(names, 1.0))
    if not values:
        return None
    return max(values)


def _tier_heap(args, size) -> str:
    """Heap grows with the tier. An explicit flag pins every tier to one size."""
    if args.target == "reference" and args.reference_heap:
        return str(args.reference_heap)
    if args.target == "oip" and args.oip_heap:
        return str(args.oip_heap)
    return heap_for_memory(size.memory)


def _start_tier(args, size, heap: str, env_file: Path | None) -> str:
    if args.target == "oip":
        _remove_container("oip-bench-gw")
        _run_gateway(args.image, size.cpus, size.memory, args.publish, heap, env_file)
        _wait_gateway(_gateway_url(args))
        return "oip-bench-gw"
    if not args.container:
        raise PlanError("the reference platform scale needs --container, the gateway container to limit")
    _apply_reference_tier(args, size, heap)
    return args.container


def _apply_reference_tier(args, size, heap: str) -> None:
    """Same cgroup and ``-m`` path as a ramp, once per tier, with the heap taken from that tier's memory."""
    if not docker_available():
        raise PlanError("docker is required to apply the reference platform tier limits")
    megabytes = heap_mb(heap)
    try:
        raw = subprocess.check_output(["docker", "inspect", args.container], text=True, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError as exc:
        raise PlanError(f"docker inspect {args.container} failed with status {exc.returncode}") from exc
    payload = json.loads(raw)
    if not isinstance(payload, list) or not payload:
        raise PlanError(f"docker inspect {args.container} returned no container")
    command = recreate_command(payload[0], cpus=size.cpus, memory=size.memory, heap_mb_value=megabytes)
    try:
        apply_reference_heap(args.container, command, _check)
    except PlanError:
        raise
    except subprocess.CalledProcessError as exc:
        raise PlanError(f"replacing {args.container} for the {size.cpus} vCPU tier failed with status {exc.returncode}") from exc
    _wait_gateway(_gateway_url(args))


def _start_sim_fleet(args, fleet: SimFleet, points: int, hosts=None):
    if not docker_available():
        raise PlanError("docker is required to start the simulator fleet")
    _check(["docker", "build", "-t", SIM_IMAGE, "-f", str(ROOT / "sim" / "Dockerfile"), str(ROOT)])
    reference = args.target == "reference"
    if hosts:
        try:
            layout = place_simulators(
                fleet.instances, hosts, modbus_base=args.modbus_port, reference=reference,
            )
        except ValueError as exc:
            raise PlanError(str(exc)) from exc
    else:
        layout = fleet_layout(fleet.instances, modbus_base=args.modbus_port, reference=reference)
    sent_images: set[str] = set()
    names = []
    urls = []
    for row in layout:
        docker_host = row.get("dockerHost")
        if docker_host and docker_host not in sent_images:
            _ensure_remote_image(SIM_IMAGE, docker_host)
            sent_images.add(docker_host)
        _remove_container(row["name"], docker_host)
        _check(sim_container_command(
            name=row["name"],
            image=SIM_IMAGE,
            cpus=fleet.cpus_each,
            memory=fleet.memory,
            host_modbus_port=row["modbusPort"],
            host_http_port=row["httpPort"],
            points=points,
            docker_host=docker_host,
        ))
        if hosts:
            names.append(row)
        else:
            names.append(row["name"])
        address = row.get("address") or "127.0.0.1"
        urls.append(f"http://{address}:{row['httpPort']}")
    for url in urls:
        _wait_sim(url)
    fanout = None
    if reference:
        backends = []
        for row in layout:
            address = row.get("address") or "127.0.0.1"
            backends.append((address, row["modbusPort"]))
        fanout = ConnectionFanout("0.0.0.0", args.modbus_port, backends)
        fanout.start()
    return names, urls, fanout, layout


def _oip_password(args) -> str | None:
    if getattr(args, "_oip_password_loaded", False):
        return getattr(args, "_oip_password", None)
    args._oip_password_loaded = True
    path = args.oip_password_file
    if path is None:
        args._oip_password = None
        return None
    try:
        text = path.read_text()
    except OSError as exc:
        raise PlanError(f"could not read {path}") from exc
    try:
        password = read_admin_password(text, source=str(path))
    except SecretError as exc:
        raise PlanError(str(exc)) from exc
    args._oip_password = password
    return password


def _write_password_env(password: str) -> Path:
    """Mode 0600 env file for ``docker run --env-file``. The password is not a ``docker`` argument."""
    import tempfile
    handle = tempfile.NamedTemporaryFile(prefix="oip-bench-", suffix=".env", delete=False)
    path = Path(handle.name)
    handle.close()
    path.write_text(password_env_text(password))
    os.chmod(path, 0o600)
    return path


def _sim_names(sim) -> list[str]:
    if not sim:
        return []
    if isinstance(sim, str):
        return [sim]
    return [name for name in sim if name]

