"""PRF-012: 150,000 points on Modbus, OPC UA and MQTT, never via loopback."""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sim"))

from bench.formula import BenchConfig, changes
from bench.prf012 import MIN_POINTS, load_config, prove, refusal_probe, resolve_points, sample_indexes
import network


class Prf012ConfigTest(unittest.TestCase):
    def test_full_points_meet_the_floor_and_ci_is_smaller(self):
        config = load_config()
        self.assertGreaterEqual(config["fullPoints"], MIN_POINTS)
        self.assertGreaterEqual(MIN_POINTS, 150_000)
        self.assertLess(config["ciPoints"], config["fullPoints"])
        self.assertGreaterEqual(config["ciPoints"], 1)
        self.assertEqual(config["protocols"], ["modbus", "opcua", "mqtt"])
        self.assertIs(config["loopbackAllowed"], False)
        self.assertEqual(config["changePercents"], [10, 6])
        self.assertEqual(config["periodMs"], 1000)

    def test_change_fractions_follow_the_index(self):
        for percent in load_config()["changePercents"]:
            BenchConfig(points=100, percent_changing=percent).validated()
            self.assertTrue(changes(0, percent))
            self.assertTrue(changes(int(percent) - 1, percent))
            self.assertFalse(changes(int(percent), percent))

    def test_resolve_points_prefers_an_explicit_count(self):
        config = {"fullPoints": 150_000, "ciPoints": 64}
        previous = os.environ.pop("OIP_PRF012_POINTS", None)
        try:
            self.assertEqual(resolve_points(config, points=None, ci=False), 150_000)
            self.assertEqual(resolve_points(config, points=None, ci=True), 64)
            self.assertEqual(resolve_points(config, points=150_000, ci=True), 150_000)
            os.environ["OIP_PRF012_POINTS"] = "32"
            self.assertEqual(resolve_points(config, points=None, ci=False), 32)
            self.assertEqual(resolve_points(config, points=8, ci=False), 8)
        finally:
            if previous is None:
                os.environ.pop("OIP_PRF012_POINTS", None)
            else:
                os.environ["OIP_PRF012_POINTS"] = previous

    def test_sample_indexes_cover_both_ends(self):
        self.assertEqual(sample_indexes(150_000), [0, 75_000, 149_999])
        self.assertEqual(sample_indexes(64), [0, 32, 63])
        self.assertEqual(sample_indexes(1), [0])


class Prf012RefusalTest(unittest.TestCase):
    def test_driver_refuses_loopback_before_connecting(self):
        refusal_probe()

    def test_named_loopback_hosts_are_refused(self):
        for host in ("127.0.0.1", "127.1", "localhost", "::1", "::ffff:127.0.0.1", "0.0.0.0"):
            self.assertTrue(network.is_loopback_host(host), host)
            with self.assertRaises(network.LoopbackRefused):
                network.require_driver_target(host)

    def test_networked_bind_picks_a_real_interface(self):
        with self.assertRaises(network.LoopbackRefused):
            network.networked_bind("127.0.0.1")
        host = network.networked_bind("0.0.0.0")
        self.assertFalse(network.is_loopback_host(host))
        network.require_driver_target(host)


class Prf012ServeTest(unittest.TestCase):
    def test_ci_size_serves_each_protocol_off_loopback(self):
        config = load_config()
        result = prove(config["ciPoints"])
        self.assertEqual(result["points"], config["ciPoints"])
        self.assertEqual(result["protocols"], ["modbus", "opcua", "mqtt"])
        self.assertEqual(result["mqttMetrics"], config["ciPoints"])
        self.assertFalse(network.is_loopback_host(result["host"]))
        self.assertEqual(result["indexes"], sample_indexes(config["ciPoints"]))

    def test_full_point_count_on_modbus_opcua_and_mqtt(self):
        config = load_config()
        result = prove(config["fullPoints"])
        self.assertGreaterEqual(result["points"], 150_000)
        self.assertEqual(result["mqttMetrics"], result["points"])
        self.assertEqual(result["indexes"], [0, result["points"] // 2, result["points"] - 1])
        self.assertFalse(network.is_loopback_host(result["host"]))
        for index in result["indexes"]:
            self.assertIsInstance(result["values"][index], int)


if __name__ == "__main__":
    unittest.main()
