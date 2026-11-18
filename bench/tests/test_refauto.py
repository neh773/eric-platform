"""Dry run of the trial-window automation against a stub of the reference product."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "reference"))
sys.path.insert(0, str(ROOT / "tests"))

import refauto  # noqa: E402
from reference_stub import Stub  # noqa: E402


class Helpers(unittest.TestCase):
    def test_secret_names(self):
        for name in ("password", "tokenHash", "csrfToken", "apiKey", "secret", "passphrase"):
            self.assertTrue(refauto.secret_name(name), name)
        for name in ("keys", "name", "headerName", "enabled", "username"):
            self.assertFalse(refauto.secret_name(name), name)

    def test_safe_values_withholds_secret_strings_only(self):
        out = refauto.safe_values({"keys": ["a"], "tokenHash": "h", "password": "p", "nested": [{"apiKey": "k"}]})
        self.assertEqual(out["keys"], ["a"])
        self.assertEqual(out["tokenHash"], "[withheld]")
        self.assertEqual(out["nested"][0]["apiKey"], "[withheld]")

    def test_trial_route_write_is_refused_before_any_request(self):
        ref = refauto.Ref(None)
        ref.base = "http://127.0.0.1:9"
        for method in ("POST", "PUT", "DELETE", "PATCH"):
            with self.assertRaises(refauto.AutoError):
                ref.req(method, "/data/api/v1/trial")


class StubDryRun(unittest.TestCase):
    def run_step(self, stub: Stub, step: str, tmp: Path) -> dict:
        env = dict(os.environ, REFAUTO_BASE=f"http://127.0.0.1:{stub.port}", REFAUTO_OUT=str(tmp / "out"),
                   REFAUTO_EVIDENCE=str(tmp / "evidence"), REFAUTO_SECRET_ROOT=str(tmp), REFAUTO_OPCUA="opc.tcp://127.0.0.1:9", REFAUTO_OPCUA_ATTEMPTS="1",
                   REFAUTO_SIGNIN="form")
        proc = subprocess.run([sys.executable, str(ROOT / "reference" / "refauto.py"), "step", step], env=env,
                              capture_output=True, text=True, timeout=120)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads(proc.stdout.strip().splitlines()[-1])

    def setup_tmp(self, tmp: Path, password: str) -> None:
        (tmp / "secrets").mkdir()
        (tmp / "secrets" / "reference-admin.txt").write_text(password + "\n")

    def check_w02(self, flavour: str) -> None:
        stub = Stub(flavour, password="Adm1n-pass-xyz").start()
        try:
            with tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                self.setup_tmp(tmp, "Adm1n-pass-xyz")
                result = self.run_step(stub, "W02", tmp)
                checks = {c["check"]: c["ok"] for c in result["checks"]}
                self.assertTrue(checks["admin sign-in through the web login flow"], result)
                self.assertTrue(checks["key bench: created and saved to its token file"], result)
                self.assertTrue(checks["bench key may write (tag import of one memory tag)"], result)
                self.assertTrue(checks["parity-ro key accepted for a read"], result)
                self.assertTrue(checks["OPC UA server settings saved"], result)
                token = tmp / "secrets" / "reference-api-token.txt"
                self.assertEqual(oct(token.stat().st_mode & 0o777), "0o600")
                # Secrets never reach the evidence: not the password, a key, a hash or the session.
                key = token.read_text().strip()
                for path in (tmp / "evidence").rglob("*"):
                    if path.is_file():
                        text = path.read_text()
                        for secret in ("Adm1n-pass-xyz", key, "stub-hash-0", "csrf-sid"):
                            self.assertNotIn(secret, text, f"{secret} in {path}")
                opc = stub.resources[(refauto.OPCUA, "server-config", "server-config")]["config"]
                self.assertEqual(opc["endpoint"]["bindAddresses"], ["0.0.0.0"])
                self.assertTrue(opc["authentication"]["anonymousAccessAllowed"])
                self.assertTrue(opc["advanced"]["exposedTagsEnabled"])
                self.assertIn("None", opc["securityPolicies"])
                self.assertIn("None", opc["messageSecurityModes"])
                # The token file holds "<key name>:<key>", the form the API takes.
                self.assertTrue(key.startswith("bench:"), "token file is not in name:key form")
                self.assertEqual(stub.trial_writes, 0)
                self.assertFalse(any(m != "GET" and "trial" in p for m, p in stub.requests))
                # Then P1-06 disables parity-ro, and P1-11 sees the disabled key refused a backup.
                p106 = self.run_step(stub, "P1-06", tmp)
                c106 = {c["check"]: c["ok"] for c in p106["checks"]}
                self.assertTrue(c106["disabled key refused"], p106)
                self.assertTrue(c106["read-only key refused on a write route"], p106)
                p111 = self.run_step(stub, "P1-11", tmp)
                c111 = {c["check"]: c["ok"] for c in p111["checks"]}
                self.assertTrue(c111["admin key creates and downloads a backup"], p111)
                self.assertFalse(p111["facts"]["readOnlyMayDownloadBackup"], p111)
        finally:
            stub.stop()

    def test_w02_through_an_identity_provider_sign_in(self):
        self.check_w02("idp")

    def test_w02_through_an_html_form_sign_in(self):
        self.check_w02("form")

    def test_lockout_needs_a_baseline_sign_in_first(self):
        stub = Stub("form", password="Adm1n-pass-xyz").start()
        try:
            with tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                self.setup_tmp(tmp, "Adm1n-pass-xyz")
                self.run_step(stub, "W02", tmp)
                # No user yet: the baseline fails and no wrong tries are made, so no lockout is claimed.
                early = self.run_step(stub, "P1-07", tmp)
                checks = {c["check"]: c["ok"] for c in early["checks"]}
                self.assertFalse(checks["account signs in with its password before the wrong tries"], early)
                self.assertNotIn("wrongTries", early["facts"])
                self.assertNotIn("lockout observed (correct password refused after wrong tries)", checks)
                # With the user made (P1-04), the baseline holds; the stub has no lockout, so none is observed.
                self.run_step(stub, "P1-04", tmp)
                late = self.run_step(stub, "P1-07", tmp)
                checks = {c["check"]: c["ok"] for c in late["checks"]}
                self.assertTrue(checks["account signs in with its password before the wrong tries"], late)
                self.assertEqual(len(late["facts"]["wrongTries"]), 12)
                self.assertFalse(checks["lockout observed (correct password refused after wrong tries)"], late)
        finally:
            stub.stop()

    def test_every_step_runs_to_a_result_without_crashing(self):
        stub = Stub("idp", password="Adm1n-pass-xyz").start()
        try:
            with tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                self.setup_tmp(tmp, "Adm1n-pass-xyz")
                (tmp / "secrets" / "window-db.txt").write_text("db-pass-123\n")
                self.run_step(stub, "W02", tmp)
                for step in refauto.STEPS:
                    if step == "W02":
                        continue
                    result = self.run_step(stub, step, tmp)
                    self.assertIn(result["verdict"], ("collected", "partial", "failed", "no-evidence"))
                    crashed = [c for c in result["checks"] if c["check"] == "step ran to the end"]
                    self.assertEqual(crashed, [], f"{step}: {crashed}")
                    if step == "P1-12":
                        # The gateway audit field is named by the documented schema, absent until set.
                        props = stub.resources[(refauto.CORE, "security-properties", "security-properties")]
                        self.assertEqual(props["config"].get("gatewayAuditProfile"), "window-audit", result)
                    if step == "P1-09":
                        self.assertIn("userInactivityTimeout", result["facts"]["timeoutFields"], result)
                self.assertEqual(stub.trial_writes, 0)
        finally:
            stub.stop()


if __name__ == "__main__":
    unittest.main()


class RunnerHook(unittest.TestCase):
    def test_auto_runs_the_step_script_and_uses_auto_minutes(self):
        import io
        from types import SimpleNamespace
        from unittest import mock

        from bench import window

        plan = window.load_plan(ROOT / "plans" / "window-1.json")
        args = SimpleNamespace(auto=True, oip_install=None, reference_report=None, steady_seconds=None,
                               db_outage_seconds=0)
        with tempfile.TemporaryDirectory() as tmp:
            runner = window.Runner(plan, "reference", Path(tmp), args, stdin=io.StringIO(""), stdout=io.StringIO(),
                                   docker=lambda a: SimpleNamespace(returncode=1, stdout="", stderr=""))
            w02 = next(s for s in runner.plan["steps"] if s["id"] == "W02")
            self.assertEqual(w02["minutes"], w02["autoMinutes"])
            done = SimpleNamespace(returncode=0, stdout=json.dumps({"verdict": "collected", "evidenceDir": "x"}) + "\n",
                                   stderr="")
            with mock.patch.object(window.subprocess, "run", return_value=done) as run:
                out = runner._do_manual(w02, None, None)
            self.assertEqual(out["auto"]["verdict"], "collected")
            self.assertIsNone(out["answer"])
            self.assertEqual(run.call_args[0][0][-2:], ["step", "W02"])
            self.assertNotIn("status", out)
            # A failed setup automation fails the step instead of reading as ok.
            bad = SimpleNamespace(returncode=0, stdout=json.dumps({"verdict": "failed", "evidenceDir": "x"}) + "\n",
                                  stderr="")
            with mock.patch.object(window.subprocess, "run", return_value=bad):
                runner._run_step(w02)
            self.assertEqual(runner.results[-1].status, "failed")
        # Without --auto the person's minutes stand.
        with tempfile.TemporaryDirectory() as tmp:
            args.auto = False
            runner = window.Runner(plan, "reference", Path(tmp), args, stdin=io.StringIO(""), stdout=io.StringIO())
            w02 = next(s for s in runner.plan["steps"] if s["id"] == "W02")
            self.assertEqual(w02["minutes"], 8)


class Deadline(unittest.TestCase):
    def test_backstop_fires_inside_the_procedure_cap(self):
        from datetime import datetime, timedelta, timezone

        import deadline

        start = datetime(2026, 11, 9, 12, 0, tzinfo=timezone.utc)
        when = deadline.deadline(start, 3)
        self.assertEqual(when, start + timedelta(minutes=107))
        self.assertLess(when, start + timedelta(minutes=110))
