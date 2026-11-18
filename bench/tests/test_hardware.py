"""Dry-runs for the ADR 0010 hardware plans.

The comparison VM is never contacted. PLAN rows P2-04, P2-05, and P4-01 stay todo.
"""

from __future__ import annotations

import json
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT))

from bench.cli import main
from bench.hardware import PLANS, load_operator


REQUIREMENTS = REPO / "test-infra" / "perf" / "perf-requirements.json"


class HardwareDryRunTest(unittest.TestCase):
    def test_each_plan_writes_an_adr_report_from_the_local_simulator(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            code = main(["hardware", "--all", "--dry-run", "--out", str(out)])
            self.assertEqual(code, 0)
            for plan in PLANS:
                report = json.loads((out / plan / "report.json").read_text())
                markdown = (out / plan / "report.md").read_text()
                self.assertEqual(report["kind"], "adr-0010")
                self.assertEqual(report["adr"], "0010")
                self.assertEqual(report["plan"], plan)
                self.assertTrue(report["dryRun"])
                self.assertFalse(report["measured"])
                self.assertFalse(report["gatePassed"])
                self.assertFalse(report["comparisonVmContacted"])
                self.assertEqual(report["targets"], ["oip", "comparison"])
                self.assertEqual(report["trial"]["trialMinutes"], 120)
                self.assertEqual(report["trial"]["procedureCapMinutes"], 110)
                self.assertEqual(report["trial"]["resetTrial"], "manual")
                self.assertFalse(report["trial"]["harnessPostsTrialRoute"])
                self.assertTrue(report["simulator"]["formulaMatch"])
                self.assertEqual(report["simulator"]["protocols"], ["modbus", "opcua", "sparkplug"])
                self.assertNotEqual(report["simulator"]["changingValue"], report["simulator"]["steadyValue"])
                self.assertIn("not a gate result", markdown.lower())
                self.assertIn("Comparison VM contacted: no", markdown)
                for line in report["operator"]["hardware"]:
                    self.assertIn(line, markdown)
                for line in report["operator"]["trialWindow"]:
                    self.assertIn(line, markdown)
                for line in report["operator"]["stepsNickStarts"]:
                    self.assertIn(line, markdown)

    def test_plan_shapes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            self.assertEqual(main(["hardware", "--all", "--dry-run", "--out", str(out)]), 0)
            head = json.loads((out / "P2-04" / "report.json").read_text())
            self.assertEqual([row["id"] for row in head["sizes"]], ["S2", "S4", "S8", "S16", "EDGE"])
            scale = json.loads((out / "P2-05" / "report.json").read_text())
            self.assertFalse(scale["edgeIncluded"])
            self.assertEqual(scale["efficiencyMin"], 0.7)
            self.assertEqual(set(scale["targetsDetail"]), {"oip", "comparison"})
            for target in ("oip", "comparison"):
                cpus = [row["cpus"] for row in scale["targetsDetail"][target]["throughput"]]
                self.assertEqual(cpus, [2, 4, 8, 16])
                for row in scale["targetsDetail"][target]["throughput"]:
                    self.assertGreaterEqual(row["efficiency"], 0.7)
            full = json.loads((out / "P3-04" / "report.json").read_text())
            self.assertIsNone(full["historianHost"])
            self.assertFalse(full["notificationPipelines"])
            self.assertFalse(full["prf003Paired"])
            self.assertFalse(full["prf008Paired"])
            self.assertEqual(full["parity"], "parity-3")
            latency = json.loads((out / "P4-01" / "report.json").read_text())
            self.assertEqual(latency["sessions"], 50)
            self.assertEqual(latency["tagsPerSession"], 300)
            self.assertEqual(latency["thresholdMsP95"], 250)
            self.assertFalse(latency["ciSpecIsHardwareGate"])
            self.assertEqual(latency["latencyP95Ms"], {"oip": None, "comparison": None})
            parity = json.loads((out / "P4-14" / "report.json").read_text())
            self.assertEqual(
                [row["id"] for row in parity["checks"]],
                ["page-navigation", "view-navigation", "binding-behaviour", "popup", "dock"],
            )
            self.assertTrue(all(row["observed"] is False for row in parity["checks"]))
            gate = json.loads((out / "P4-15" / "report.json").read_text())
            self.assertEqual(gate["verdict"], "not-passed")
            self.assertEqual(gate["greenMainRuns"], [])
            self.assertTrue(gate["cannotPassFromDryRun"])

    def test_measure_does_not_dial(self):
        def fail(*_args, **_kwargs):
            raise AssertionError("hardware --measure opened a socket")

        with mock.patch("socket.create_connection", side_effect=fail):
            code = main(["hardware", "P2-04", "--measure"])
        self.assertEqual(code, 2)
        with mock.patch("socket.create_connection", side_effect=fail):
            both = main(["hardware", "P4-15", "--measure", "--dry-run"])
        self.assertEqual(both, 2)
        self.assertEqual(main(["hardware"]), 2)

    def test_statuses_and_implemented_flags_stay(self):
        requirements = json.loads(REQUIREMENTS.read_text(encoding="utf-8"))
        flags = {item["id"]: item["implemented"] for item in requirements["scenarios"]}
        for sid in ("PRF-001", "PRF-002", "PRF-003", "PRF-008"):
            self.assertFalse(flags[sid])
        operator = load_operator()
        self.assertIn("a manual license reset", "\n".join(operator["stepsNickStarts"]))

    def test_dry_run_socket_stays_on_loopback(self):
        seen = []
        real = socket.create_connection

        def spy(address, *args, **kwargs):
            host = address[0] if isinstance(address, tuple) else address
            seen.append(host)
            return real(address, *args, **kwargs)

        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch("socket.create_connection", side_effect=spy):
                code = main(["hardware", "P4-15", "--dry-run", "--out", str(tmp)])
        self.assertEqual(code, 0)
        self.assertTrue(seen)
        self.assertTrue(all(host in ("127.0.0.1", "localhost") for host in seen))


if __name__ == "__main__":
    unittest.main()
