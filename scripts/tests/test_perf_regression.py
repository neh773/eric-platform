"""Tests for scripts/perf_regression.py (PRF-013). No gateway, no network."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import perf_regression as pr  # noqa: E402


def entry(value, hardware="ci", lower=False, scenario="PRF-009", metric="m"):
    return {"scenario": scenario, "hardware": hardware, "metric": metric, "lowerIsBetter": lower, "value": value}


def status(result, baseline):
    return pr.compare([result], [baseline])[0]["status"]


class BoundaryTest(unittest.TestCase):
    def test_exactly_15_percent_worse_passes_higher_is_better(self):
        self.assertEqual(status(entry(85.0), entry(100.0)), "ok")

    def test_exactly_15_percent_worse_passes_lower_is_better(self):
        self.assertEqual(status(entry(115.0, lower=True), entry(100.0, lower=True)), "ok")

    def test_15_01_percent_worse_fails_higher_is_better(self):
        self.assertEqual(status(entry(84.99), entry(100.0)), "FAIL")

    def test_15_01_percent_worse_fails_lower_is_better(self):
        self.assertEqual(status(entry(115.01, lower=True), entry(100.0, lower=True)), "FAIL")

    def test_improvement_passes_in_both_directions(self):
        self.assertEqual(status(entry(150.0), entry(100.0)), "ok")
        self.assertEqual(status(entry(50.0, lower=True), entry(100.0, lower=True)), "ok")

    def test_direction_matters(self):
        # a rise of 20% is an improvement for throughput and a regression for latency
        self.assertEqual(status(entry(120.0), entry(100.0)), "ok")
        self.assertEqual(status(entry(120.0, lower=True), entry(100.0, lower=True)), "FAIL")


class MatchingTest(unittest.TestCase):
    def test_size_mismatch_is_never_compared(self):
        rows = pr.compare([entry(1.0, hardware="S8")], [entry(100.0, hardware="S2")])
        self.assertEqual(rows[0]["status"], "no baseline")
        self.assertIsNone(rows[0]["baseline"])

    def test_scenario_and_metric_mismatch_are_not_compared(self):
        self.assertEqual(pr.compare([entry(1.0)], [entry(100.0, scenario="PRF-010")])[0]["status"], "no baseline")
        self.assertEqual(pr.compare([entry(1.0)], [entry(100.0, metric="other")])[0]["status"], "no baseline")

    def test_missing_baseline_is_reported_and_exit_is_zero(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d, "r.json")
            f.write_text(json.dumps({"results": [entry(1.0, scenario="PRF-999")]}))
            self.assertEqual(pr.main(["--result", str(f)]), 0)
        rows = pr.compare([entry(1.0)], [])
        self.assertIn("no baseline", pr.render(rows))

    def test_each_size_uses_its_own_baseline(self):
        base = [entry(100.0, hardware="S2"), entry(1000.0, hardware="S8")]
        rows = pr.compare([entry(90.0, hardware="S2"), entry(90.0, hardware="S8")], base)
        self.assertEqual([r["status"] for r in rows], ["ok", "FAIL"])


class FilesTest(unittest.TestCase):
    def run_main(self, results, baselines):
        with tempfile.TemporaryDirectory() as d:
            r, b = pathlib.Path(d, "r.json"), pathlib.Path(d, "b.json")
            r.write_text(json.dumps(results))
            b.write_text(json.dumps(baselines))
            return pr.main(["--result", str(r), "--baseline", str(b)])

    def test_exit_codes(self):
        base = {"results": [entry(100.0, scenario="X1")]}
        self.assertEqual(self.run_main({"results": [entry(85.0, scenario="X1")]}, base), 0)
        self.assertEqual(self.run_main({"results": [entry(84.0, scenario="X1")]}, base), 1)
        self.assertEqual(self.run_main({"bad": 1}, base), 2)

    def test_load_soak_report_is_read_by_size(self):
        report = {"scenario": "PRF-008", "size": "ci", "steady": {"changesPerSecond": 12000.0}}
        got = pr.parse_results(report, "r")
        self.assertEqual(got, [entry(12000.0, scenario="PRF-008", metric="steadyChangesPerSecond")])

    def test_committed_baselines_carry_direction_and_size(self):
        base = {(b["scenario"], b["hardware"]): b for b in pr.requirements_baselines()}
        self.assertFalse(base[("PRF-009", "ci")]["lowerIsBetter"])
        self.assertTrue(base[("PRF-007", "ci")]["lowerIsBetter"])
        self.assertNotIn(("PRF-009", "S8"), base)


if __name__ == "__main__":
    unittest.main()
