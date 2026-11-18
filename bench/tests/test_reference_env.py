"""Unit tests for the scripted reference-product container. No docker, no network."""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "reference"))

import reference_env as env  # noqa: E402


class GuardTests(unittest.TestCase):
    config = env.load_config()
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)

    def test_fresh_window_fits(self):
        ok, _ = env.guard_check(self.start, self.start + timedelta(minutes=5), self.config)
        self.assertTrue(ok)

    def test_exactly_ten_minutes_spare_fits(self):
        ok, _ = env.guard_check(self.start, self.start + timedelta(minutes=10), self.config)
        self.assertTrue(ok)

    def test_past_ten_minutes_refused(self):
        ok, message = env.guard_check(self.start, self.start + timedelta(minutes=10, seconds=1), self.config)
        self.assertFalse(ok)
        self.assertIn("manual", message)

    def test_run_over_procedure_cap_refused(self):
        ok, _ = env.guard_check(self.start, self.start, self.config, need_minutes=111)
        self.assertFalse(ok)

    def test_parse_docker_time(self):
        parsed = env.parse_time("2026-11-09T16:30:47.955777123Z")
        self.assertEqual(parsed.year, 2026)
        self.assertEqual(parsed.tzinfo, timezone.utc)


class CommandTests(unittest.TestCase):
    config = env.load_config()

    def test_limits_match_harness_sizes(self):
        self.assertEqual(env.size_limits("s2"), (2.0, "4g"))
        self.assertEqual(env.size_limits("EDGE"), (4.0, "4g"))
        with self.assertRaises(env.EnvError):
            env.size_limits("S3")

    def test_run_command_has_no_secret_and_publishes_8088(self):
        cmd = env.run_command(self.config, cpus=2, memory="4g", heap_megabytes=2048, env_file="/tmp/e")
        self.assertIn("--cpus", cmd)
        self.assertEqual(cmd[cmd.index("--cpus") + 1], "2")
        self.assertEqual(cmd[cmd.index("--memory") + 1], "4g")
        self.assertIn("8088:8088", cmd)
        self.assertEqual(cmd[-2:], ["-m", "2048"])
        self.assertIn("--env-file", cmd)
        self.assertFalse([c for c in cmd if "PASSWORD" in c])

    def test_env_text_commissioning(self):
        text = env.env_file_text(self.config, "pw")
        for line in ("ACCEPT_REFERENCE_EULA=Y", "REFERENCE_EDITION=standard", "GATEWAY_ADMIN_PASSWORD=pw"):
            self.assertIn(line, text)
        with self.assertRaises(env.EnvError):
            env.env_file_text(self.config, "a\nb")

    def test_password_file_random_and_private(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "secrets" / "pw.txt"
            env.ensure_password_file(path)
            first = path.read_text()
            env.ensure_password_file(path)
            self.assertEqual(first, path.read_text())
            self.assertGreaterEqual(len(first.strip()), 20)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_harness_command_uses_same_limits_and_never_resets(self):
        text = " ".join(env.harness_command(self.config, "S4", "profiles/reference-trial.json"))
        self.assertIn("--cpus 4 --memory 8g", text)
        self.assertIn("--gateway http://127.0.0.1:8088", text)
        self.assertNotIn("reset", text.lower())


class WaitTests(unittest.TestCase):
    def test_wait_succeeds_after_retries(self):
        answers = iter([False, False, True])
        self.assertTrue(env.wait_healthy(env.load_config(), 100, sleep=lambda s: None, probe=lambda u: next(answers)))

    def test_wait_times_out(self):
        ticks = iter(range(0, 1000, 10))
        self.assertFalse(env.wait_healthy(env.load_config(), 20, sleep=lambda s: None,
                                          probe=lambda u: False, clock=lambda: next(ticks)))

    def test_second_up_needs_confirmation(self):
        args = mock.Mock(reset_confirmed=False)
        with mock.patch.object(env, "container_exists", return_value=False), \
                mock.patch.object(env, "read_state", return_value={"startedAt": "x"}):
            with self.assertRaises(env.EnvError):
                env.cmd_up(args, env.load_config())


if __name__ == "__main__":
    unittest.main()
