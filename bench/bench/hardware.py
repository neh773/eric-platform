"""ADR 0010 hardware plans.

One command writes a report for our gateway and for the comparison VM.
``--dry-run`` drives the local in-process simulator and does not open the
comparison VM. ``--measure`` is the reference-host form and refuses to dial.

Dry-run output is not a gate result. P2-04, P2-05, and P4-01 stay todo.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

from bench.formula import BenchConfig, address, raw_at
from bench.headtohead import REFERENCE_SIZES, SCALE_SIZE_IDS, build_dry_run
from bench.profile import PlanError
from bench.report import write_report
from bench.scaling import (
    EFFICIENCY_MIN,
    FORMULA,
    Size,
    synthetic_saturated_report,
    tag_capacity_efficiency,
    efficiency,
)

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
REQUIREMENTS = REPO / "test-infra" / "perf" / "perf-requirements.json"
CONFIG_DIR = ROOT / "profiles" / "hardware"
PLANS = ("P2-04", "P2-05", "P3-04", "P4-01", "P4-14", "P4-15")
TARGETS = ("oip", "comparison")


def hardware_main(args) -> int:
    if args.measure and args.dry_run:
        print("error: pass either --dry-run or --measure", file=sys.stderr)
        return 2
    plans = _selected(args)
    if isinstance(plans, int):
        return plans
    if args.measure or not args.dry_run:
        print(_measure_refusal(plans), file=sys.stderr)
        return 2
    try:
        requirements = load_requirements()
        probe = probe_local_simulator()
    except (OSError, RuntimeError, PlanError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    out_root = args.out or (ROOT / "out" / "hardware")
    for plan in plans:
        report = build_report(plan, requirements, probe)
        directory = out_root / plan if len(plans) > 1 or args.out is None else out_root
        if len(plans) == 1 and args.out is not None:
            directory = args.out
        json_path, md_path = write_report(report, directory)
        print(f"dry-run: {plan} is not a gate result")
        print(f"wrote {json_path}")
        print(f"wrote {md_path}")
    return 0


def _selected(args) -> tuple[str, ...] | int:
    if args.all and args.plan:
        print("error: pass a plan or --all, not both", file=sys.stderr)
        return 2
    if args.all:
        return PLANS
    if not args.plan:
        print("error: pass a plan id or --all", file=sys.stderr)
        return 2
    if args.plan not in PLANS:
        print(f"error: unknown plan {args.plan}", file=sys.stderr)
        return 2
    return (args.plan,)


def _measure_refusal(plans: tuple[str, ...]) -> str:
    listed = ", ".join(plans)
    return (
        f"refusing to contact the comparison VM for {listed}. "
        "Start the comparison gateway yourself, perform a manual license reset in its UI, "
        "and pass --reset-confirmed only after that. "
        "This process does not start the VM and does not POST the trial route. "
        "A dry-run is: python3 -m bench hardware <PLAN> --dry-run"
    )


def load_requirements() -> dict:
    return json.loads(REQUIREMENTS.read_text(encoding="utf-8"))


def load_config(plan: str) -> dict:
    path = CONFIG_DIR / f"{plan.lower()}.json"
    if not path.is_file():
        raise PlanError(f"missing hardware config {path}")
    config = json.loads(path.read_text(encoding="utf-8"))
    if config.get("plan") != plan:
        raise PlanError(f"{path} plan is {config.get('plan')}, expected {plan}")
    if config.get("targets") != list(TARGETS):
        raise PlanError(f"{plan} must name both targets: oip and comparison")
    if config.get("dryRunIsGate") is not False:
        raise PlanError(f"{plan} dry-run must not be a gate")
    return config


def load_operator() -> dict:
    path = CONFIG_DIR / "operator.json"
    operator = json.loads(path.read_text(encoding="utf-8"))
    for key in ("hardware", "trialWindow", "stepsNickStarts"):
        rows = operator.get(key)
        if not isinstance(rows, list) or not rows or not all(isinstance(row, str) and row for row in rows):
            raise PlanError(f"operator.json {key} must be a non-empty list of strings")
    return operator


def check_operator_against_adr(operator: dict, requirements: dict) -> None:
    """The operator list Nick reads has to name the ADR sizes and windows."""
    text = "\n".join(operator["hardware"] + operator["trialWindow"] + operator["stepsNickStarts"])
    for item in requirements["hardware"]:
        if item["id"] not in text:
            raise PlanError(f"operator list missing hardware {item['id']}")
        needle = f"{item['vcpu']} dedicated vCPU"
        if needle not in text:
            raise PlanError(f"operator list missing {needle} for {item['id']}")
        if f"{item['ramGb']} GB RAM" not in text:
            raise PlanError(f"operator list missing {item['ramGb']} GB RAM for {item['id']}")
    trial = requirements["referencePlatform"]["trialMinutes"]
    cap = requirements["referencePlatform"]["procedureCapMinutes"]
    if f"{trial} minutes" not in text or f"{cap} minutes" not in text:
        raise PlanError("operator list missing the trial window or the procedure cap")
    if "a manual license reset" not in text or "does not POST" not in text:
        raise PlanError("operator list must keep a manual license reset manual")
    prf003 = _scenario(requirements, "PRF-003")
    prf008 = _scenario(requirements, "PRF-008")
    prf011 = _scenario(requirements, "PRF-011")
    if str(prf003["durationMinutes"]) not in text or str(prf008["durationMinutes"]) not in text:
        raise PlanError("operator list missing the PRF-003 or PRF-008 duration")
    if str(prf011["sessions"]) not in text or str(prf011["tagsPerSession"]) not in text:
        raise PlanError("operator list missing the PRF-011 session farm")
    if "three consecutive green main run" not in text:
        raise PlanError("operator list missing the gate-4 green runs")
    if "own host" not in text:
        raise PlanError("operator list missing the historian host")


def probe_local_simulator() -> dict:
    """Modbus, OPC UA, and Sparkplug on ephemeral loopback ports. Not the comparison VM."""
    server = _sim_server()
    cfg = BenchConfig(
        points=30,
        percent_changing=10,
        period_ms=1000,
        epoch_ms=0,
        frozen=True,
        now_ms=5000,
        registers_per_unit=10,
        metrics_per_device=10,
    )
    running = server.serve(
        cfg,
        bind="127.0.0.1",
        modbus_ports=[0],
        opcua_port=0,
        mqtt_port=0,
        http_port=0,
        browse_page=4,
    )
    try:
        server._wait_births(running)
        changing = raw_at(0, cfg.now_ms, cfg)
        steady = raw_at(10, cfg.now_ms, cfg)
        if changing == steady:
            raise RuntimeError("10% changing rule did not move index 0")
        server._modbus_check(running.modbus.ports[0], 1, 0, changing)
        located = address(10, cfg)
        server._modbus_check(running.modbus.ports[0], located.unit_id, located.pdu, steady)
        opc = server._opcua_read_point(running.opcua.port, cfg, index=0)
        if opc != changing:
            raise RuntimeError(f"opcua value {opc} != {changing}")
        spark = server._sparkplug_metric(running.broker.port, address(0, cfg).metric)
        if spark != changing:
            raise RuntimeError(f"sparkplug value {spark} != {changing}")
        return {
            "bind": "127.0.0.1",
            "comparisonVmContacted": False,
            "points": cfg.points,
            "percentChanging": cfg.percent_changing,
            "periodMs": cfg.period_ms,
            "nowMs": cfg.now_ms,
            "protocols": ["modbus", "opcua", "sparkplug"],
            "formulaMatch": True,
            "changingIndex": 0,
            "changingValue": changing,
            "steadyIndex": 10,
            "steadyValue": steady,
            "rule": "(index % 100) < percentChanging",
        }
    finally:
        running.stop()


def build_report(plan: str, requirements: dict, probe: dict) -> dict:
    config = load_config(plan)
    operator = load_operator()
    check_operator_against_adr(operator, requirements)
    _check_config(plan, config, requirements)
    report = {
        "kind": "adr-0010",
        "adr": requirements.get("adr", "0010"),
        "plan": plan,
        "scenarios": list(config.get("scenarios") or []),
        "dryRun": True,
        "measured": False,
        "gatePassed": False,
        "comparisonVmContacted": False,
        "targets": list(TARGETS),
        "comparisonVersion": requirements["referencePlatform"]["version"],
        "cutoffs": requirements["cutoffs"],
        "regressionFailPercentGreaterThan": requirements["regressionFailPercentGreaterThan"],
        "trial": {
            "trialMinutes": requirements["referencePlatform"]["trialMinutes"],
            "procedureCapMinutes": requirements["referencePlatform"]["procedureCapMinutes"],
            "resetTrial": "manual",
            "oneRunPerTrial": True,
            "harnessPostsTrialRoute": False,
        },
        "hardware": requirements["hardware"],
        "simulator": probe,
        "operator": operator,
        "notes": [
            "Dry-run against the local simulator. Not a reference-hardware result.",
            "The comparison VM was not contacted. a manual license reset was not called.",
            "Do not copy this file over the CI-size reports. It is not a baseline.",
        ],
    }
    report.update(_plan_body(plan, config, requirements))
    return report


def _plan_body(plan: str, config: dict, requirements: dict) -> dict:
    if plan == "P2-04":
        if config.get("sizes") != [item["id"] for item in requirements["hardware"]]:
            raise PlanError("P2-04 sizes drifted from perf-requirements.json")
        return _p2_04(requirements)
    if plan == "P2-05":
        if config.get("sizes") != list(SCALE_SIZE_IDS) or config.get("edgeIncluded") is not False:
            raise PlanError("P2-05 must be S2, S4, S8, S16 and must leave EDGE out")
        if config.get("efficiencyMin") != requirements["scaling"]["efficiencyMin"]:
            raise PlanError("P2-05 efficiency minimum drifted")
        if config.get("clientsStayAtS2") != 10:
            raise PlanError("P2-05 keeps the S2 client count on every size")
        return _p2_05(requirements)
    if plan == "P3-04":
        return _p3_04(config, requirements)
    if plan == "P4-01":
        return _p4_01(requirements)
    if plan == "P4-14":
        return _p4_14(config)
    if plan == "P4-15":
        return _p4_15(config)
    raise PlanError(f"unknown plan {plan}")


def _p2_04(requirements: dict) -> dict:
    sizes = [dict(size) for size in REFERENCE_SIZES]
    wanted = {item["id"]: item for item in requirements["hardware"]}
    if [size["id"] for size in sizes] != [item["id"] for item in requirements["hardware"]]:
        raise PlanError("P2-04 sizes drifted from perf-requirements.json")
    for size in sizes:
        item = wanted[size["id"]]
        if size["vcpu"] != item["vcpu"] or size["ramGb"] != item["ramGb"]:
            raise PlanError(f"{size['id']} drifted from perf-requirements.json")
    built = build_dry_run(sizes)
    rows = []
    for size_report in built:
        load = size_report.get("lastGoodLoad") or {}
        rows.append({
            "id": size_report["size"]["id"],
            "vcpu": size_report["size"]["vcpu"],
            "ramGb": size_report["size"]["ramGb"],
            "lastGoodPoints": load.get("points"),
            "held": (size_report.get("comparison") or {}).get("held"),
            "dryRun": True,
            "measured": False,
        })
    return {
        "sections": [_table(
            "Head-to-head sizes",
            ["Size", "vCPU", "RAM GB", "Last good points", "Held", "Measured"],
            [[
                row["id"], row["vcpu"], row["ramGb"],
                _cell(row["lastGoodPoints"]),
                "yes" if row["held"] else "no",
                "no",
            ] for row in rows],
            "Scripted cutoffs on both targets. The held flag is the dry-run shape, not a pass.",
        )],
        "sizes": rows,
        "bothTargets": True,
    }


def _p2_05(requirements: dict) -> dict:
    minimum = requirements["scaling"]["efficiencyMin"]
    if minimum != EFFICIENCY_MIN:
        raise PlanError("efficiency minimum drifted from the harness constant")
    if requirements["scaling"]["formula"] != FORMULA:
        raise PlanError("efficiency formula drifted")
    hardware = [item for item in requirements["hardware"] if item["id"] in SCALE_SIZE_IDS]
    if [item["id"] for item in hardware] != list(SCALE_SIZE_IDS):
        raise PlanError("P2-05 series must be S2, S4, S8, S16")
    if any(item["id"] == "EDGE" for item in hardware):
        raise PlanError("EDGE is not a scaling point")
    sizes = [Size(float(item["vcpu"]), f"{item['ramGb']}g") for item in hardware]
    cpus = [size.cpus for size in sizes]
    tables = {}
    for target in TARGETS:
        throughput_reports = [
            synthetic_saturated_report(cpu, kind="throughput", efficiency_target=1.0) for cpu in cpus
        ]
        capacity_reports = [
            synthetic_saturated_report(cpu, kind="capacity", efficiency_target=1.0) for cpu in cpus
        ]
        throughput = efficiency(throughput_reports, cpus, minimum=minimum)
        capacity = tag_capacity_efficiency(capacity_reports, cpus, minimum=minimum)
        for row, size in zip(throughput, sizes):
            row["memory"] = size.memory
            row["target"] = target
            row["dryRun"] = True
        for row, size in zip(capacity, sizes):
            row["memory"] = size.memory
            row["target"] = target
            row["dryRun"] = True
        tables[target] = {"throughput": throughput, "tagCapacity": capacity}
    section_rows = []
    for target, table in tables.items():
        for through, cap in zip(table["throughput"], table["tagCapacity"]):
            section_rows.append([
                target,
                through["cpus"],
                through.get("memory") or "",
                _cell(through.get("efficiency")),
                _cell(cap.get("efficiency")),
                "yes" if through.get("meetsMinimum") and cap.get("meetsMinimum") else "no",
                "no",
            ])
    return {
        "sections": [_table(
            "Efficiency",
            ["Target", "vCPU", "Memory", "E throughput", "E tag capacity", "Meets 0.70", "Measured"],
            section_rows,
            "Scripted at E(n) = 1 so both targets render. Not a PRF-002 measurement. EDGE is omitted.",
        )],
        "formula": FORMULA,
        "efficiencyMin": minimum,
        "clientsStayAtS2": 10,
        "targetsDetail": tables,
        "edgeIncluded": False,
    }


def _p3_04(config: dict, requirements: dict) -> dict:
    prf003 = _scenario(requirements, "PRF-003")
    prf008 = _scenario(requirements, "PRF-008")
    return {
        "sections": [
            _table(
                "PRF-003 full load",
                ["Field", "ADR", "Our gateway", "Comparison VM"],
                [
                    ["hardware", prf003["hardware"], "unmeasured", "unmeasured"],
                    ["durationMinutes", prf003["durationMinutes"], "unmeasured", "unmeasured"],
                    ["threshold changes/s", prf003["threshold"], "unmeasured", "unmeasured"],
                    ["clients", prf003["clients"], "unmeasured", "unmeasured"],
                    ["historian host", "own host", "unset", "unset"],
                    ["notification pipelines", "off", "off", "off"],
                    ["paired", "only when setup + 60 min + teardown fit in 110 min", "no", "no"],
                ],
                "The hour is its own trial. This dry-run does not soak.",
            ),
            _table(
                "PRF-008 historian",
                ["Field", "ADR", "Our gateway", "Comparison VM"],
                [
                    ["durationMinutes", prf008["durationMinutes"], "unmeasured", "unpaired"],
                    ["threshold changes/s", prf008["threshold"], "unmeasured", "unpaired"],
                    ["stretch changes/s", prf008["stretch"], "recorded, does not gate", "unpaired"],
                    ["stretchGates", prf008["stretchGates"], "false", "unpaired"],
                    ["sample loss", "none", "unmeasured", "unpaired"],
                ],
                "The 3 h soak is our gateway only. A paired sample uses the shorter trial window.",
            ),
            _table(
                "parity-3",
                ["Check", "Our gateway", "Comparison VM", "Same trial window"],
                [
                    ["alarm-sequence", "unobserved", "unobserved", "yes"],
                    ["historian-result", "unobserved", "unobserved", "yes"],
                ],
                "Both products inside one trial window. Observations stay empty until Nick runs it.",
            ),
        ],
        "historianOnOwnHost": True,
        "historianHost": None,
        "notificationPipelines": False,
        "parity": config.get("parity"),
        "prf003Paired": False,
        "prf008Paired": False,
    }


def _p4_01(requirements: dict) -> dict:
    prf = _scenario(requirements, "PRF-011")
    ceiling = prf["thresholdMsP95"]
    return {
        "sections": [_table(
            "HMI latency",
            ["Target", "Sessions", "Tags per session", "p95 ms", "Ceiling ms", "Measured"],
            [[
                target,
                prf["sessions"],
                prf["tagsPerSession"],
                "",
                ceiling,
                "no",
            ] for target in TARGETS],
            "p95 is empty. The CI Playwright spec is not this hardware gate.",
        )],
        "sessions": prf["sessions"],
        "tagsPerSession": prf["tagsPerSession"],
        "thresholdMsP95": ceiling,
        "ciSpecIsHardwareGate": False,
        "latencyP95Ms": {"oip": None, "comparison": None},
    }


def _p4_14(config: dict) -> dict:
    checks = list(config.get("checks") or [])
    return {
        "sections": [_table(
            "parity-4",
            ["Check", "Our gateway", "Comparison VM", "Same project", "Same trial window"],
            [[check, "unobserved", "unobserved", "yes", "yes"] for check in checks],
            "Navigation, bindings, popup, and dock. Not a pixel comparison.",
        )],
        "checks": [
            {"id": check, "oip": None, "comparison": None, "observed": False}
            for check in checks
        ],
        "oneTrialWindow": True,
    }


def _p4_15(config: dict) -> dict:
    required = int(config.get("greenMainRunsRequired") or 0)
    return {
        "sections": [_table(
            "gate-4",
            ["Prerequisite", "Met"],
            [
                ["P4-01", "no"],
                ["P4-14", "no"],
                [f"{required} consecutive green main runs", "no"],
            ],
            "A dry-run cannot pass this gate. Nick supplies the run identifiers.",
        )],
        "verdict": "not-passed",
        "greenMainRuns": [],
        "greenMainRunsRequired": required,
        "cannotPassFromDryRun": True,
    }


def _check_config(plan: str, config: dict, requirements: dict) -> None:
    trial = requirements["referencePlatform"]["trialMinutes"]
    cap = requirements["referencePlatform"]["procedureCapMinutes"]
    if config.get("trialMinutes") != trial or config.get("procedureCapMinutes") != cap:
        raise PlanError(f"{plan} trial window drifted from perf-requirements.json")
    if config.get("resetTrial") != "manual":
        raise PlanError(f"{plan} must keep a manual license reset manual")
    if plan == "P3-04":
        for sid, block in (("PRF-003", "prf003"), ("PRF-008", "prf008")):
            scenario = _scenario(requirements, sid)
            declared = config.get(block) or {}
            for key, value in declared.items():
                if scenario.get(key) != value:
                    raise PlanError(f"{plan} {sid} {key} drifted")
        if config.get("historianOnOwnHost") is not True:
            raise PlanError("P3-04 historian database stays on its own host")
        if config.get("notificationPipelines") is not False:
            raise PlanError("P3-04 notification pipelines stay off")
    if plan == "P4-01":
        scenario = _scenario(requirements, "PRF-011")
        for key in ("sessions", "tagsPerSession", "thresholdMsP95"):
            if config.get(key) != scenario[key]:
                raise PlanError(f"P4-01 {key} drifted")
        if config.get("ciSpecIsHardwareGate") is not False:
            raise PlanError("P4-01 CI spec is not the hardware gate")
    if plan == "P4-14":
        expected = ["page-navigation", "view-navigation", "binding-behaviour", "popup", "dock"]
        if config.get("checks") != expected:
            raise PlanError("P4-14 checks drifted")
        if config.get("oneTrialWindow") is not True:
            raise PlanError("P4-14 stays inside one trial window")
    if plan == "P4-15":
        if config.get("requires") != ["P4-01", "P4-14"]:
            raise PlanError("P4-15 requires P4-01 and P4-14")
        if config.get("greenMainRunsRequired") != 3:
            raise PlanError("P4-15 needs three green main runs")
        if config.get("cannotPassFromDryRun") is not True:
            raise PlanError("P4-15 dry-run must not pass the gate")


def _scenario(requirements: dict, sid: str) -> dict:
    for scenario in requirements["scenarios"]:
        if scenario["id"] == sid:
            return scenario
    raise PlanError(f"missing scenario {sid}")


def _table(title: str, columns: list[str], rows: list[list], note: str) -> dict:
    return {"title": title, "columns": columns, "rows": rows, "note": note}


def _cell(value) -> str:
    if value is None:
        return ""
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def _sim_server():
    sim_dir = ROOT / "sim"
    if str(sim_dir) not in sys.path:
        sys.path.insert(0, str(sim_dir))
    path = sim_dir / "server.py"
    spec = importlib.util.spec_from_file_location("oip_bench_sim_server", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module
