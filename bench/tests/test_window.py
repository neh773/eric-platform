"""Window-plan runner. No docker, no network, no reference product."""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT))

from bench import window  # noqa: E402
from bench.cli import main  # noqa: E402
from bench.profile import PlanError  # noqa: E402

PLAN_PATH = ROOT / "plans" / "window-1.json"

def plan() -> dict:
    return json.loads(PLAN_PATH.read_text())


class PlanChecks(unittest.TestCase):
    def test_window_one_fits_its_110_minute_budget(self):
        loaded = window.load_plan(PLAN_PATH)
        rows = window.schedule(loaded["steps"])
        self.assertEqual(loaded["budgetMinutes"], 110)
        self.assertEqual(rows[-1]["end"], 110)
        self.assertLessEqual(loaded["budgetMinutes"], loaded["trialMinutes"])

    def test_prf001_ramp_comes_first_and_parity_follows(self):
        steps = plan()["steps"]
        kinds = [s["kind"] for s in steps]
        self.assertLess(kinds.index("ramp"), kinds.index("parity"))
        ramp = steps[kinds.index("ramp")]
        self.assertTrue(any("PRF-001" in c for c in ramp["closes"]))
        gates = {s.get("gate") for s in steps if s["kind"] == "parity"}
        self.assertEqual(gates, {"parity-1", "parity-5", "parity-6"})

    def test_plan_uses_the_harness_s2_limits_and_h2h_profile(self):
        loaded = plan()
        self.assertEqual(loaded["size"], "S2")
        self.assertEqual(loaded["rampProfile"], "profiles/h2h.json")
        self.assertEqual(loaded["label"], "container-size, not reference-size")

    def test_over_budget_plan_is_refused(self):
        bad = plan()
        bad["steps"][3]["minutes"] = 60
        with self.assertRaises(PlanError):
            window.check_plan(bad)

    def test_trial_route_write_is_refused(self):
        bad = plan()
        bad["steps"][6]["reference"] = [{"method": "POST", "path": "/data/api/v1/trial"}]
        with self.assertRaises(PlanError):
            window.check_plan(bad)

    def test_plan_without_the_label_is_refused(self):
        bad = plan()
        bad["label"] = "reference-size"
        with self.assertRaises(PlanError):
            window.check_plan(bad)

    def test_plan_must_end_with_collect_teardown_slack(self):
        bad = plan()
        bad["steps"] = [s for s in bad["steps"] if s["phase"] != "slack"]
        with self.assertRaises(PlanError):
            window.check_plan(bad)

    def test_no_reference_probe_writes_a_trial_route(self):
        for step in plan()["steps"]:
            for probe in window._all_probes(step, "reference"):
                if "trial" in probe["path"]:
                    self.assertEqual(probe.get("method", "GET"), "GET")

    def test_secret_values_are_file_references_only(self):
        text = PLAN_PATH.read_text()
        for key, rel in plan()["secrets"].items():
            self.assertTrue(rel.startswith("secrets/"), key)
        self.assertNotIn("password\": \"", text.replace("\"password\": \"{secret:db}\"", ""))


class RecordedFields(unittest.TestCase):
    def test_secret_looking_field_is_refused(self):
        bad = plan()
        bad["steps"][6]["ours"] = [{"method": "GET", "path": "/x", "record": ["apiKey"]}]
        with self.assertRaises(PlanError):
            window.check_plan(bad)

    def test_recorded_field_value_is_kept_and_others_are_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = SimpleNamespace(oip_install=None, reference_report=None, steady_seconds=None, db_outage_seconds=0)
            runner = window.Runner(plan(), "ours", Path(tmp), args, stdin=io.StringIO(""), stdout=io.StringIO())
            fake = SimpleNamespace(transport=SimpleNamespace(cookie="c"),
                                   _request=lambda m, p, b=None: (200, {"status": "Faulted", "url": "jdbc:x", "username": "u"}))
            runner.oip_target = fake
            rows = runner._probes([{"method": "GET", "path": "/api/v1/db/connections/window-db", "record": ["status"]}])
        self.assertEqual(rows[0]["fields"], {"status": "Faulted"})
        self.assertNotIn("jdbc:x", json.dumps(rows))


class Decisions(unittest.TestCase):
    def setUp(self):
        self.steps = plan()["steps"]
        self.index = {s["id"]: i for i, s in enumerate(self.steps)}

    def test_on_schedule_runs(self):
        i = self.index["P1-01"]
        start = window.schedule(self.steps)[i]["start"]
        self.assertEqual(window.decide(self.steps, i, start, 110), "run")

    def test_slack_absorbs_a_four_minute_overrun(self):
        i = self.index["P1-01"]
        start = window.schedule(self.steps)[i]["start"]
        self.assertEqual(window.decide(self.steps, i, start + 4, 110), "run")

    def test_optional_step_is_skipped_when_behind(self):
        i = self.index["P1-08"]
        start = window.schedule(self.steps)[i]["start"]
        self.assertEqual(window.decide(self.steps, i, start + 7, 110), "skip")

    def test_required_step_that_overruns_goes_to_teardown(self):
        i = self.index["P1-07"]
        start = window.schedule(self.steps)[i]["start"]
        self.assertEqual(window.decide(self.steps, i, start + 10, 110), "stop")

    def test_tail_always_runs(self):
        i = self.index["W91"]
        self.assertEqual(window.decide(self.steps, i, 200, 110), "run")

    def test_ramp_cap_never_reaches_into_later_required_steps(self):
        i = self.index["W04"]
        on_time = window.ramp_cap_seconds(self.steps, i, 14, 110)
        self.assertEqual(on_time, 30 * 60 - 120)
        late = window.ramp_cap_seconds(self.steps, i, 30, 110)
        reserve = window.reserve_after(self.steps, i)
        self.assertLessEqual(30 + late / 60.0 + reserve, 110)

    def test_reserve_excludes_optional_and_slack(self):
        i = self.index["P1-12"]
        reserve = window.reserve_after(self.steps, i)
        # P1-07 (4) + collect (3) + teardown (2); the two optional steps and slack are not reserved.
        self.assertEqual(reserve, 9)


class Helpers(unittest.TestCase):
    def test_shape_keeps_names_not_values(self):
        out = window.shape({"token": "s3cret", "items": [{"name": "a", "key": "zzz"}]})
        self.assertEqual(out["keys"], ["items", "token"])
        self.assertEqual(out["lists"]["items"], {"length": 1, "itemKeys": ["key", "name"]})
        self.assertNotIn("s3cret", json.dumps(out))
        self.assertNotIn("zzz", json.dumps(out))

    def test_fill_secrets_reads_files_by_name(self):
        body = {"a": "{secret:db}", "b": [{"c": "{secret:backup}"}], "d": "plain"}
        filled = window.fill_secrets(body, lambda name: name.upper())
        self.assertEqual(filled, {"a": "DB", "b": [{"c": "BACKUP"}], "d": "plain"})

    def test_new_secret_file_is_0600_and_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s" / "x.txt"
            self.assertTrue(window.new_secret_file(path))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            first = path.read_text()
            self.assertFalse(window.new_secret_file(path))
            self.assertEqual(path.read_text(), first)

    def test_policy_password_has_classes_and_no_run_of_four(self):
        value = window.policy_password()
        self.assertTrue(any(c.islower() for c in value))
        self.assertTrue(any(c.isupper() for c in value))
        self.assertTrue(any(c.isdigit() for c in value))
        self.assertFalse(any(value[i] * 4 == value[i:i + 4] for i in range(len(value) - 3)))

    def test_render_runbook_has_one_row_per_step_and_dry_run_column(self):
        loaded = plan()
        table = window.render_runbook(loaded, {"W01": 60.0})
        self.assertEqual(len(table.strip().splitlines()), 2 + len(loaded["steps"]))
        self.assertIn("1.0 min", table)


class FakeTransport:
    def __init__(self, log):
        self.log = log

    def request(self, method, path, body=None, query=None, content_type="application/json"):
        self.log.append((method, path, body))
        return 200, {"licenseMode": "Trial", "trialSecondsLeft": 6000}


class RunnerSafety(unittest.TestCase):
    def runner(self, target, tmp, docker=None):
        args = SimpleNamespace(oip_install=None, reference_report=None, steady_seconds=None, db_outage_seconds=0)
        return window.Runner(plan(), target, Path(tmp), args, stdin=io.StringIO(""), stdout=io.StringIO(),
                             docker=docker or (lambda a: SimpleNamespace(returncode=1, stdout="", stderr="")))

    def test_ours_preflight_never_asks_for_the_reference_image(self):
        calls = []

        def docker(args):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("ours", tmp, docker)
            with mock.patch.object(window, "tcp_open", return_value=False):
                runner._do_preflight(plan()["before"][0], None, None)
        config = json.loads((ROOT / "reference" / "config.json").read_text())
        self.assertFalse(any(config["image"] in arg for call in calls for arg in call))
        self.assertFalse(any(call and call[0] == "pull" for call in calls))

    def test_reference_probes_refuse_a_trial_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("reference", tmp)
            log = []
            with mock.patch.object(runner, "_reference_transport", return_value=FakeTransport(log)), \
                    mock.patch.object(runner, "read_secret", return_value="x"):
                with self.assertRaises(PlanError):
                    runner._probes([{"method": "POST", "path": "/data/api/v1/trial"}])
                rows = runner._probes([{"method": "GET", "path": "/data/api/v1/trial"}])
        self.assertEqual(log, [("GET", "/data/api/v1/trial", None)])
        self.assertEqual(rows[0]["status"], 200)
        self.assertNotIn("6000", json.dumps(rows))

    def test_reference_prompt_without_a_terminal_records_no_answer(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("reference", tmp)
            self.assertIsNone(runner._ask(["question"]))

    def test_ours_manual_step_does_not_prompt(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("ours", tmp)
            with mock.patch.object(window, "new_secret_file", return_value=False):
                out = runner._do_manual(plan()["steps"][1], None, None)
        self.assertIsNone(out["answer"])

    def test_skipped_steps_go_straight_to_collect_and_teardown(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("ours", tmp)
            ran = []
            runner._run_step = lambda step, steps=None, index=None: (ran.append(step["id"]),
                                                                     runner.results.append(window.Result(step["id"], "ok")))
            runner.window_start = 0.0
            runner.clock = lambda: 200 * 60.0
            runner._run_window(None, None)
        self.assertEqual(ran, ["W90", "W91"])
        statuses = {r.id: r.status for r in runner.results}
        self.assertEqual(statuses["W01"], "skipped")
        self.assertEqual(statuses["P1-08"], "skipped")

    def test_failed_check_stops_without_teardown_so_the_window_can_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("ours", tmp)
            ran = []

            def fake(step, steps=None, index=None):
                ran.append(step["id"])
                runner.results.append(window.Result(step["id"], "failed" if step["kind"] == "check" else "ok"))

            runner._run_step = fake
            runner.window_start = 0.0
            runner.clock = lambda: 0.0
            code = runner._run_window(None, None)
        self.assertEqual(code, 1)
        self.assertEqual(ran, ["W01", "W02", "W03"])

    def test_resume_starts_at_the_named_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner("ours", tmp)
            ran = []
            runner._run_step = lambda step, steps=None, index=None: ran.append(step["id"])
            runner.window_start = 0.0
            start = window.schedule(plan()["steps"])[[s["id"] for s in plan()["steps"]].index("P1-12")]["start"]
            runner.clock = lambda: start * 60.0
            runner._run_window(None, "P1-12")
        self.assertEqual(ran[0], "P1-12")
        self.assertNotIn("W04", ran)


class BinaryProbe(unittest.TestCase):
    def test_backup_download_keeps_type_and_size_not_bytes(self):
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        seen = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen["csrf"] = self.headers.get("X-CSRF-Token")
                seen["body"] = self.rfile.read(int(self.headers.get("content-length") or 0))
                payload = b"\xae\x00\xffsealed"
                self.send_response(200)
                self.send_header("content-type", "application/octet-stream")
                self.send_header("content-length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            transport = SimpleNamespace(base=f"http://127.0.0.1:{server.server_address[1]}", cookie="oip_session=x", csrf="t1")
            with mock.patch.dict("os.environ", {"NO_PROXY": "127.0.0.1", "no_proxy": "127.0.0.1"}):
                status, info = window.session_raw(transport, "POST", "/api/v1/backups", {"passphrase": "p" * 16})
        finally:
            server.shutdown()
        self.assertEqual(status, 200)
        self.assertEqual(info, {"contentType": "application/octet-stream", "bytes": 9})
        self.assertEqual(seen["csrf"], "t1")
        self.assertIn(b"passphrase", seen["body"])

    def test_discard_body_is_refused_on_a_reference_probe(self):
        bad = plan()
        bad["steps"][6]["reference"] = [{"method": "GET", "path": "/x", "discardBody": True}]
        with self.assertRaises(PlanError):
            window.check_plan(bad)


class ReferencePaths(unittest.TestCase):
    """The reference-only code paths, with every process and request faked."""

    def runner(self, tmp):
        args = SimpleNamespace(oip_install=None, reference_report=None, steady_seconds=None, db_outage_seconds=0)
        return window.Runner(plan(), "reference", Path(tmp), args, stdin=io.StringIO(""), stdout=io.StringIO(),
                             docker=lambda a: SimpleNamespace(returncode=0, stdout="", stderr=""))

    def test_up_uses_the_container_script_at_s2_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner(tmp)
            done = SimpleNamespace(returncode=0, stdout="started", stderr="")
            with mock.patch.object(window.subprocess, "run", return_value=done) as run:
                out = runner._do_up(plan()["steps"][0], None, None)
        command = run.call_args.args[0]
        self.assertTrue(command[1].endswith("reference_env.py"))
        self.assertEqual(command[2:], ["up", "--size", "S2"])
        self.assertEqual(out["exit"], 0)

    def test_ramp_target_dials_the_fanout_with_the_token_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner(tmp)
            with mock.patch.object(runner, "read_secret", return_value="tok") as secret, \
                    mock.patch("bench.dockerbox.container_gateway_ip", return_value="172.17.0.1"):
                target = runner._ramp_target(runner._ramp_profile())
        self.assertEqual(type(target).__name__, "referenceTarget")
        self.assertEqual(target.sim_host, "172.17.0.1")
        self.assertEqual(target.modbus_port, 25020)
        self.assertFalse(target.delete_tags)
        secret.assert_called_with("token")

    def test_check_reads_the_trial_route_with_get_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner(tmp)
            log = []
            steps = plan()["steps"]
            with mock.patch.object(runner, "_reference_transport", return_value=FakeTransport(log)), \
                    mock.patch.object(window, "tcp_open", return_value=True), \
                    mock.patch.object(runner, "elapsed_minutes", return_value=12.0):
                out = runner._do_check(steps[2], steps, 2)
        self.assertEqual(log, [("GET", "/data/api/v1/trial", None)])
        self.assertTrue(out["windowFits"])
        self.assertEqual(out["trialSecondsLeft"], 6000)

    def test_teardown_removes_the_container_through_the_script(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = self.runner(tmp)
            done = SimpleNamespace(returncode=0, stdout="", stderr="")
            with mock.patch.object(window.subprocess, "run", return_value=done) as run:
                out = runner._do_teardown(plan()["steps"][-2], None, None)
        self.assertEqual(run.call_args.args[0][2:], ["stop"])
        self.assertTrue(out["referenceRemoved"])


class CommandLine(unittest.TestCase):
    def test_list_prints_the_runbook(self):
        out = io.StringIO()
        with mock.patch("sys.stdout", out):
            code = main(["window", "--plan", str(PLAN_PATH), "--list"])
        self.assertEqual(code, 0)
        self.assertIn("| 14-44 | W04 |", out.getvalue())

    def test_a_target_is_required_to_run(self):
        err = io.StringIO()
        with mock.patch("sys.stderr", err):
            code = main(["window", "--plan", str(PLAN_PATH)])
        self.assertEqual(code, 2)
        self.assertIn("--target", err.getvalue())


if __name__ == "__main__":
    unittest.main()
