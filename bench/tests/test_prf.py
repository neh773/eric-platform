"""PRF-001 and PRF-002 harness tests: cutoffs, E(n), reports, and the dry-run.

No docker, no the reference platform, and no gateway process.
"""

from __future__ import annotations

import io
import json
import math
import tempfile
import unittest
from pathlib import Path

from bench.cli import main
from bench.headtohead import (
    COLLECT_MINUTES,
    PROCEDURE_CAP_MINUTES,
    RAMP_MINUTES,
    REFERENCE_SIZES,
    RESET_TRIAL_NOTE,
    SCALE_SIZE_IDS,
    SETUP_MINUTES,
    SOAK_MINUTES,
    STEADY_MINUTES,
    TRIAL_MINUTES,
    assemble_size_report,
    build_dry_run,
    confirm_reset_trial,
    holds_load,
    last_good_load,
    plan_trials,
    procedure_minutes,
    require_adr_ceilings,
    select_sizes,
    soak_with_ramp_minutes,
)
from bench.metrics import (
    CPU_CUTOFF_PERCENT,
    DRIFT_CUTOFF_PERCENT,
    MEMORY_CUTOFF_PERCENT,
    choose_cutoff,
    prf001_cutoff,
)
from bench.profile import Limits, PlanError, load_profile, profile_from_dict
from bench.report import render_markdown, write_report
from bench.scaling import (
    EFFICIENCY_MIN,
    POINTS_PER_SIM_VCPU,
    efficiency,
    efficiency_ratio,
    is_capacity_ramp,
    is_throughput_ramp,
    plan_sim_fleet,
    prepare_capacity_profile,
    series_exit_code,
    synthetic_saturated_report,
    tag_capacity_efficiency,
)
from bench.simfleet import load_sim_hosts, place_simulators, sim_container_command
from bench.targets import OipTarget


ROOT = Path(__file__).resolve().parents[1]


def _flat(cpu, memory, drift):
    return {
        "reads": 10,
        "qualityErrors": 0,
        "mismatches": 0,
        "cpuPercentOfQuota": {"max": cpu},
        "memoryPercent": {"max": memory},
        "driftPercent": drift,
    }


class CutoffTest(unittest.TestCase):
    def test_ceilings_are_strictly_above_fifty_and_outside_ten(self):
        limits = Limits()
        self.assertEqual(limits.cpu_percent, 50)
        self.assertEqual(limits.memory_percent, 50)
        self.assertEqual(limits.drift_percent, 10)
        self.assertIsNone(choose_cutoff(_flat(50, 50, 10), limits))
        self.assertIsNone(choose_cutoff(_flat(50, 50, -10), limits))
        self.assertEqual(choose_cutoff(_flat(50.1, 10, 0), limits), "cpu")
        self.assertEqual(choose_cutoff(_flat(10, 50.1, 0), limits), "memory")
        self.assertEqual(choose_cutoff(_flat(10, 10, 10.1), limits), "drift")
        self.assertEqual(choose_cutoff(_flat(10, 10, -10.1), limits), "drift")
        step = _flat(10, 10, 0)
        step["clockDriftPercent"] = 10
        self.assertIsNone(choose_cutoff(step, limits))
        step["clockDriftPercent"] = -10.1
        self.assertEqual(choose_cutoff(step, limits), "drift")

    def test_steady_window_uses_the_mean_and_any_sixty_second_mean(self):
        quiet = [{"cpuPercent": 50, "memoryPercent": 50, "driftPercent": 10} for _ in range(6)]
        self.assertIsNone(prf001_cutoff(quiet))
        self.assertEqual(prf001_cutoff([{"cpuPercent": 50.1, "memoryPercent": 10, "driftPercent": 0}]), "cpu")
        self.assertEqual(prf001_cutoff([{"cpuPercent": 10, "memoryPercent": 50.1, "driftPercent": 0}]), "memory")
        self.assertEqual(prf001_cutoff([{"cpuPercent": 10, "memoryPercent": 10, "driftPercent": -10.1}]), "drift")

        # Overall mean stays at or under 50. The first 60 s (six samples) does not.
        samples = [{"cpuPercent": 60, "memoryPercent": 10, "driftPercent": 0} for _ in range(6)]
        samples += [{"cpuPercent": 40, "memoryPercent": 10, "driftPercent": 0} for _ in range(12)]
        self.assertAlmostEqual(sum(s["cpuPercent"] for s in samples) / len(samples), 46.666, places=2)
        self.assertEqual(prf001_cutoff(samples), "cpu")

        # Five samples are not a 60 s window. The mean is what counts.
        short = [{"cpuPercent": 40, "memoryPercent": 10, "driftPercent": 0} for _ in range(5)]
        self.assertIsNone(prf001_cutoff(short))
        # Six samples are one 60 s window. The mean of that window is what fails it.
        window = short + [{"cpuPercent": 120, "memoryPercent": 10, "driftPercent": 0}]
        self.assertGreater(sum(sample["cpuPercent"] for sample in window) / len(window), 50)
        self.assertEqual(prf001_cutoff(window), "cpu")

    def test_any_error_or_data_loss_fails_the_window(self):
        self.assertEqual(prf001_cutoff([{"error": True, "cpuPercent": 1}]), "error")
        self.assertEqual(prf001_cutoff([{"missed": True, "cpuPercent": 1}]), "data-loss")
        self.assertEqual(prf001_cutoff([{"duplicate": True, "cpuPercent": 1}]), "data-loss")
        self.assertEqual(prf001_cutoff([{"dataLoss": True, "cpuPercent": 1}]), "data-loss")
        self.assertEqual(prf001_cutoff([{"quality": "Bad", "cpuPercent": 1}]), "data-loss")
        self.assertIsNone(prf001_cutoff([{"quality": "Good", "cpuPercent": 10, "memoryPercent": 10, "driftPercent": 0}]))
        self.assertIsNone(prf001_cutoff([]))

    def test_a_looser_ceiling_is_refused(self):
        with self.assertRaises(ValueError):
            prf001_cutoff([{"cpuPercent": 1}], cpu_limit=50.1)
        with self.assertRaises(ValueError):
            prf001_cutoff([{"cpuPercent": 1}], memory_limit=MEMORY_CUTOFF_PERCENT + 1)
        with self.assertRaises(ValueError):
            prf001_cutoff([{"cpuPercent": 1}], drift_limit=DRIFT_CUTOFF_PERCENT + 1)
        # A tighter ceiling is still allowed.
        self.assertEqual(
            prf001_cutoff([{"cpuPercent": 40, "memoryPercent": 1, "driftPercent": 0}], cpu_limit=30),
            "cpu",
        )
        self.assertEqual(CPU_CUTOFF_PERCENT, 50)
        self.assertEqual(MEMORY_CUTOFF_PERCENT, 50)
        self.assertEqual(DRIFT_CUTOFF_PERCENT, 10)


class EfficiencyMathTest(unittest.TestCase):
    def test_formula_and_the_zero_point_seven_boundary(self):
        self.assertEqual(EFFICIENCY_MIN, 0.70)
        # E(4) = (1400/1000) / (4/2) = 0.70 exactly, which passes.
        self.assertAlmostEqual(efficiency_ratio(1400, 1000, 4, 2), 0.70)
        self.assertAlmostEqual(efficiency_ratio(2800, 1000, 8, 2), 0.70)
        self.assertAlmostEqual(efficiency_ratio(5600, 1000, 16, 2), 0.70)
        rows = efficiency(
            [
                synthetic_saturated_report(2, kind="throughput"),
                synthetic_saturated_report(4, kind="throughput", efficiency_target=0.70),
                synthetic_saturated_report(8, kind="throughput", efficiency_target=0.70),
                synthetic_saturated_report(16, kind="throughput", efficiency_target=0.70),
            ],
            [2, 4, 8, 16],
        )
        self.assertEqual(rows[0]["efficiency"], 1)
        self.assertIsNone(rows[0]["meetsMinimum"])
        for row in rows[1:]:
            self.assertAlmostEqual(row["efficiency"], 0.70)
            self.assertTrue(row["meetsMinimum"])
        under = efficiency(
            [
                synthetic_saturated_report(2, kind="throughput"),
                synthetic_saturated_report(4, kind="throughput", efficiency_target=0.699),
            ],
            [2, 4],
        )
        self.assertLess(under[1]["efficiency"], 0.70)
        self.assertFalse(under[1]["meetsMinimum"])
        self.assertEqual(series_exit_code(rows), 0)
        self.assertEqual(series_exit_code(under), 1)

    def test_tag_capacity_uses_the_same_formula_on_points(self):
        reports = [
            synthetic_saturated_report(cpu, kind="capacity", efficiency_target=0.70 if cpu > 2 else 1)
            for cpu in (2, 4, 8, 16)
        ]
        rows = tag_capacity_efficiency(reports, [2, 4, 8, 16])
        self.assertEqual(rows[0]["metric"], "tag-capacity")
        self.assertEqual(rows[0]["points"], 10_000)
        self.assertEqual(rows[1]["points"], 14_000)
        self.assertAlmostEqual(rows[1]["efficiency"], efficiency_ratio(14_000, 10_000, 4, 2))
        self.assertTrue(rows[1]["meetsMinimum"])
        self.assertAlmostEqual(rows[2]["efficiency"], 0.70)
        self.assertAlmostEqual(rows[3]["efficiency"], 0.70)
        missing = [{
            "cpus": 4,
            "baselineCpus": 2,
            "meetsMinimum": None,
        }]
        self.assertEqual(series_exit_code(missing), 2)


class ReportAndTrialTest(unittest.TestCase):
    def test_one_trial_per_size_and_the_procedure_budget(self):
        self.assertEqual(procedure_minutes(), 110)
        self.assertEqual(procedure_minutes(), PROCEDURE_CAP_MINUTES)
        self.assertLessEqual(PROCEDURE_CAP_MINUTES, TRIAL_MINUTES)
        self.assertEqual(
            SETUP_MINUTES + RAMP_MINUTES + STEADY_MINUTES + COLLECT_MINUTES,
            110,
        )
        self.assertGreater(soak_with_ramp_minutes(), TRIAL_MINUTES)
        self.assertEqual(soak_with_ramp_minutes(), SETUP_MINUTES + RAMP_MINUTES + SOAK_MINUTES + COLLECT_MINUTES)
        plans = plan_trials(select_sizes("S2,S8"))
        self.assertEqual([plan["size"] for plan in plans], ["S2", "S8"])
        self.assertEqual([plan["trial"] for plan in plans], [1, 2])
        self.assertTrue(all(plan["stacked"] is False for plan in plans))
        self.assertEqual([size["id"] for size in select_sizes(None)], ["S2", "S4", "S8", "S16", "EDGE"])
        self.assertNotIn("EDGE", SCALE_SIZE_IDS)

    def test_reset_trial_pauses_and_is_not_automated(self):
        sink = io.StringIO()

        class Boom:
            def isatty(self):
                return True

            def readline(self):
                raise AssertionError("dry-run must not read stdin")

        record = confirm_reset_trial("S4", stdin=Boom(), stdout=sink, dry_run=True)
        self.assertFalse(record["automated"])
        self.assertFalse(record["postedTrialRoute"])
        self.assertTrue(record["paused"])
        self.assertIn("a manual license reset", record["note"])
        self.assertIn("does not POST", record["note"])
        self.assertIn("S4", sink.getvalue())

        paused_in = io.StringIO("\n")
        paused_in.isatty = lambda: True
        paused = confirm_reset_trial("EDGE", stdin=paused_in, stdout=io.StringIO(), dry_run=False)
        self.assertEqual(paused["mode"], "pause")
        self.assertTrue(paused["confirmed"])
        self.assertFalse(paused["automated"])

        with self.assertRaises(PlanError) as caught:
            confirm_reset_trial("S2", stdin=io.StringIO(), stdout=io.StringIO(), dry_run=False)
        self.assertIn("a manual license reset", str(caught.exception))
        self.assertIn("manual", str(caught.exception).lower())

    def test_report_per_size_has_the_last_good_load_and_our_result(self):
        reports = build_dry_run(select_sizes("all"), stdout=io.StringIO())
        self.assertEqual([report["size"]["id"] for report in reports], ["S2", "S4", "S8", "S16", "EDGE"])
        for report in reports:
            self.assertEqual(report["referenceVersion"], "8.3.10")
            self.assertTrue(report["dryRun"])
            self.assertFalse(report["measured"])
            load = report["lastGoodLoad"]
            self.assertIsNotNone(load)
            self.assertGreater(load["points"], 0)
            self.assertGreater(load["valuesPerSec"], 0)
            self.assertEqual(report["ours"]["points"], load["points"])
            self.assertGreaterEqual(report["ours"]["valuesPerSec"], load["valuesPerSec"])
            self.assertTrue(report["comparison"]["held"])
            self.assertFalse(report["resetTrial"]["postedTrialRoute"])
            text = render_markdown(report)
            self.assertIn("Last good load", text)
            self.assertIn("Our result at that load", text)
            self.assertIn(report["size"]["id"], text)
            self.assertIn("a manual license reset", text)
        index = {
            "kind": "head-to-head-series",
            "scenario": "PRF-001",
            "referenceVersion": "8.3.10",
            "dryRun": True,
            "sizes": [
                {
                    "id": report["size"]["id"],
                    "vcpu": report["size"]["vcpu"],
                    "ramGb": report["size"]["ramGb"],
                    "lastGoodLoad": report["lastGoodLoad"],
                    "ours": report["ours"],
                    "held": True,
                }
                for report in reports
            ],
            "notes": [RESET_TRIAL_NOTE],
        }
        text = render_markdown(index)
        self.assertIn("S16", text)
        self.assertIn("EDGE", text)
        self.assertIn("a manual license reset", text)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            write_report(reports[0], directory / "S2")
            self.assertTrue((directory / "S2" / "report.json").is_file())
            self.assertTrue((directory / "S2" / "report.md").is_file())

    def test_a_short_result_does_not_hold_the_load(self):
        load = {"points": 100, "valuesPerSec": 80, "goodQualityTags": 100}
        ours = {"points": 100, "valuesPerSec": 79, "stoppedBecause": "completed", "steadyCutoff": None}
        comparison = holds_load(load, ours, steady_cutoff=None)
        self.assertFalse(comparison["held"])
        self.assertFalse(comparison["throughputAtLeast"])
        lost = holds_load(load, ours, steady_cutoff="data-loss")
        self.assertFalse(lost["withinCutoffs"])
        self.assertIsNone(last_good_load({"steps": [{"points": 10, "valuesPerSec": 1, "cutoff": "cpu"}]}))

    def test_packaged_profiles_keep_the_ceilings_and_the_sizes(self):
        h2h = load_profile(ROOT / "profiles" / "h2h.json")
        require_adr_ceilings(h2h)
        self.assertLessEqual(h2h.planned_seconds(), 6600)
        self.assertEqual(h2h.limits.cpu_percent, 50)
        self.assertEqual(h2h.limits.memory_percent, 50)
        self.assertEqual(h2h.limits.drift_percent, 10)
        self.assertEqual(h2h.sample_interval_seconds, 10)
        ids = [row["id"] for row in h2h.raw["sizes"]]
        self.assertEqual(ids, [size["id"] for size in REFERENCE_SIZES])
        for raw, size in zip(h2h.raw["sizes"], REFERENCE_SIZES):
            self.assertEqual(raw["cpus"], size["vcpu"])
            self.assertEqual(raw["ramGb"], size["ramGb"])
        loose = profile_from_dict({
            "name": "loose",
            "points": [10],
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 30,
            "cutoffs": {"cpuPercent": 51},
        })
        with self.assertRaises(PlanError):
            require_adr_ceilings(loose)

        capacity = load_profile(ROOT / "profiles" / "scale-capacity.json")
        self.assertTrue(is_capacity_ramp(capacity))
        self.assertFalse(is_throughput_ramp(capacity))
        self.assertIs(prepare_capacity_profile(capacity), capacity)
        self.assertEqual([row["id"] for row in capacity.raw["sizes"]], list(SCALE_SIZE_IDS))
        self.assertNotIn("EDGE", [row["id"] for row in capacity.raw["sizes"]])
        full = load_profile(ROOT / "profiles" / "scale-full.json")
        self.assertTrue(is_throughput_ramp(full))
        self.assertFalse(is_capacity_ramp(full))
        self.assertEqual(full.limits.cpu_percent, 50)


class FleetAndDryRunTest(unittest.TestCase):
    def test_second_host_receives_the_overflow(self):
        hosts, meta = load_sim_hosts(json.loads((ROOT / "sim-hosts.example.json").read_text()))
        self.assertEqual(meta["headroom"], 2)
        self.assertLessEqual(meta["pointsPerVcpu"], POINTS_PER_SIM_VCPU)
        fleet = plan_sim_fleet(16, 1_280_000, headroom=meta["headroom"], points_per_vcpu=meta["pointsPerVcpu"])
        self.assertGreaterEqual(fleet.instances, math.ceil(1_280_000 / POINTS_PER_SIM_VCPU) * 2)
        placed = place_simulators(fleet.instances, hosts)
        by_host: dict[str, int] = {}
        for row in placed:
            by_host[row["hostId"]] = by_host.get(row["hostId"], 0) + 1
        self.assertEqual(set(by_host), {"local", "sim-2"})
        self.assertLessEqual(by_host["local"], 16)
        self.assertGreater(by_host["sim-2"], 0)
        remote = [row for row in placed if row["hostId"] == "sim-2"]
        self.assertEqual(remote[0]["dockerHost"], "ssh://bench@10.1.0.20")
        self.assertEqual(remote[0]["gatewayAddress"], "10.1.0.20")
        command = sim_container_command(
            name="oip-bench-sim-0", image="oip-bench-points:local", cpus=1, memory="512m",
            host_modbus_port=25020, host_http_port=28080, points=1000,
            docker_host="ssh://bench@10.1.0.20",
        )
        self.assertEqual(command[:3], ["docker", "-H", "ssh://bench@10.1.0.20"])
        local = sim_container_command(
            name="oip-bench-sim-0", image="oip-bench-points:local", cpus=1, memory="512m",
            host_modbus_port=25020, host_http_port=28080, points=1000,
        )
        self.assertNotIn("-H", local)
        self.assertGreater(
            plan_sim_fleet(2, 1_280_000, headroom=2).instances,
            plan_sim_fleet(2, 1_280_000, headroom=1).instances,
        )
        with self.assertRaises(ValueError):
            place_simulators(40, hosts)
        with self.assertRaises(ValueError):
            load_sim_hosts({"headroom": 2, "pointsPerVcpu": POINTS_PER_SIM_VCPU + 1, "hosts": [
                {"id": "only", "address": "127.0.0.1", "slots": 4},
            ]})

    def test_oip_clock_drift_is_the_ratio_as_a_percent(self):
        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                if path.endswith("/diagnostics/performance"):
                    return 200, {"clockDrift": 0.10}
                return 404, {}

        sample = OipTarget(Transport(), "127.0.0.1", [25020], 24840).sample_host()
        self.assertAlmostEqual(sample.clock_drift_percent, 10.0)
        late = {"reads": 8, "qualityErrors": 0, "mismatches": 0,
                "cpuPercentOfQuota": {"max": 1}, "memoryPercent": {"max": 1},
                "driftPercent": 0, "clockDriftPercent": 10.0}
        self.assertIsNone(choose_cutoff(late, Limits()))
        late["clockDriftPercent"] = 10.1
        self.assertEqual(choose_cutoff(late, Limits()), "drift")

    def test_oip_devices_follow_the_second_host(self):
        seen = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                if method == "PUT":
                    seen.append(body["spec"]["host"])
                    return 201, {}
                return 500, {}

        target = OipTarget(
            Transport(), "127.0.0.1", [25020], 24840,
            modbus_endpoints=[("172.17.0.1", 25020), ("10.1.0.20", 25021)],
        )
        from bench.formula import BenchConfig
        target._ensure_devices(1000, "modbus", BenchConfig(points=1, period_ms=1000))
        self.assertEqual(seen, ["172.17.0.1", "10.1.0.20"])

    def test_dry_run_commands_exit_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            head = Path(tmp) / "head"
            scale = Path(tmp) / "scale"
            err = io.StringIO()
            out = io.StringIO()
            import contextlib
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                head_code = main(["head-to-head", "--dry-run", "--out", str(head)])
                scale_code = main([
                    "scale", "--dry-run", "--metric", "both", "--target", "oip",
                    "--profile", str(ROOT / "profiles" / "scale-full.json"),
                    "--capacity-profile", str(ROOT / "profiles" / "scale-capacity.json"),
                    "--out", str(scale),
                ])
            self.assertEqual(head_code, 0, err.getvalue())
            self.assertEqual(scale_code, 0, err.getvalue())
            for size_id in ("S2", "S4", "S8", "S16", "EDGE"):
                payload = json.loads((head / size_id / "report.json").read_text())
                self.assertIn("lastGoodLoad", payload)
                self.assertIn("ours", payload)
                self.assertTrue(payload["dryRun"])
                self.assertFalse(payload["resetTrial"]["automated"])
            summary = json.loads((scale / "report.json").read_text())
        self.assertEqual(summary["efficiencyMin"], 0.70)
        self.assertIn("E(n)", summary["formula"])
        self.assertEqual(len(summary["combined"]), 4)
        text = render_markdown(summary)
        self.assertIn("0.70", text)
        self.assertIn("E capacity", text)
        self.assertNotIn("EDGE", [row.get("id") for row in summary.get("sizes") or []])
        below = tag_capacity_efficiency(
            [
                synthetic_saturated_report(2, kind="capacity"),
                synthetic_saturated_report(4, kind="capacity", efficiency_target=0.5),
            ],
            [2, 4],
        )
        self.assertEqual(series_exit_code(below), 1)
        # The assembled comparison is what the size report stores.
        assembled = assemble_size_report(
            select_sizes("S2")[0],
            {"stoppedBecause": "cpu", "steps": [
                {"points": 20, "valuesPerSec": 10, "periodMs": 1000, "percentChanging": 100, "protocol": "modbus"},
                {"points": 40, "valuesPerSec": 20, "cutoff": "cpu"},
            ]},
            {"stoppedBecause": "completed", "steps": [
                {"points": 20, "valuesPerSec": 10, "samples": [
                    {"cpuPercent": 20, "memoryPercent": 10, "driftPercent": 0, "quality": "Good"},
                ]},
            ]},
            {"automated": False, "postedTrialRoute": False, "note": RESET_TRIAL_NOTE},
            dry_run=True,
        )
        self.assertEqual(assembled["lastGoodLoad"]["points"], 20)
        self.assertEqual(assembled["ours"]["points"], 20)
        self.assertTrue(assembled["comparison"]["held"])


if __name__ == "__main__":
    unittest.main()
