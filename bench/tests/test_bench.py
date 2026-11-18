"""Unit tests for the formula, the ramp cutoffs and the gateway payloads.

The in-process simulator self-test covers Modbus, OPC UA and Sparkplug together.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import math
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sim"))

from bench.cli import (
    DEFAULT_SIM_CONTAINER,
    _sampler,
    _target,
    build_parser,
    main,
    resolve_sim_container,
)
from bench.dockerbox import (
    apply_reference_heap,
    heap_mb,
    oip_gateway_command,
    recreate_command,
    remove_container_command,
    sample_cpu_many,
    stats_line,
    update_limits_command,
    with_reference_heap_arg,
)
from bench.formula import BenchConfig, address, expected_values_per_sec, raw_at, tag_name
from bench.profile import Limits, PlanError, Profile, load_profile, profile_from_dict
from bench.metrics import (
    choose_cutoff,
    cpu_percent_of_quota,
    drift_percent,
    history_cutoff,
    implied_tick,
    latency_ms,
    parse_bytes,
    ticks_advanced,
)
from bench.opcua import OpcUaError, OpcUaReader, attribute_from_datavalue, datetime_to_ms, reference_node_id
from bench.ramp import ManualClock, effective_wall_seconds, run_ramp
from bench.report import render_markdown, write_report
from bench.scaling import (
    Size,
    efficiency,
    heap_for_memory,
    is_throughput_ramp,
    plan_sim_fleet,
    prepare_scale_profile,
    ramp_points,
    resolve_sizes,
    select_sizes,
)
from bench.secrets import password_env_text, read_admin_password
from bench.simfleet import ConnectionFanout, fleet_layout, sim_container_command
from bench.targets import (
    HostSample,
    HttpTransport,
    referenceTarget,
    OipTarget,
    TagReading,
    reference_tag,
    modbus_device_body,
    oip_modbus_device,
    oip_tag,
    parse_readings,
    parse_trial_seconds_left,
    stored_versions,
)


class FormulaTest(unittest.TestCase):
    def test_changing_and_constant_are_deterministic(self):
        cfg = BenchConfig(points=300, percent_changing=10, period_ms=1000, epoch_ms=5_000)
        self.assertEqual(raw_at(0, 5_000, cfg), 0)
        self.assertEqual(raw_at(0, 8_000, cfg), 3)
        self.assertEqual(raw_at(0, 8_000, cfg), raw_at(0, 8_000, cfg))
        self.assertEqual(raw_at(50, 8_000, cfg), 50)
        self.assertEqual(raw_at(9, 99_000, cfg), (9 + 94) % 30_000)

    def test_address_matches_public_one_based_hr(self):
        cfg = BenchConfig(points=10_000, registers_per_unit=100, metrics_per_device=25)
        located = address(250, cfg)
        self.assertEqual((located.unit_id, located.pdu, located.hr), (3, 50, 51))
        self.assertEqual(located.reference_item, "3.HR51")
        self.assertEqual(located.opc_item, "ns=1;i=1000250")
        self.assertEqual(located.sparkplug_device, "D10")
        self.assertEqual(located.metric, "P0000250")
        self.assertEqual(tag_name(250), "p0000250")

    def test_expected_rate(self):
        self.assertEqual(expected_values_per_sec(1000, 10, 1000), 100)


class MetricsTest(unittest.TestCase):
    def test_docker_units_and_quota(self):
        self.assertEqual(parse_bytes("1.5GiB"), int(1.5 * 1024 ** 3))
        self.assertEqual(parse_bytes("512MiB"), 512 * 1024 ** 2)
        self.assertEqual(cpu_percent_of_quota(200, 4), 50)
        sample = stats_line("200%", "100MiB / 1GiB", "10%", 4)
        self.assertEqual(sample.cpu_percent_of_quota, 50)
        self.assertEqual(sample.rss_bytes, 100 * 1024 * 1024)
        self.assertEqual(sample.memory_percent, 10)

    def test_sample_cpu_many_uses_one_docker_stats_call(self):
        calls = []

        class Proc:
            returncode = 0
            stdout = "80%\n160.5%\n\nbad\n40%\n"

        def fake_run(cmd, text=True, capture_output=True):
            calls.append(list(cmd))
            return Proc()

        names = [f"sim-{index}" for index in range(25)]
        with mock.patch("bench.dockerbox.subprocess.run", side_effect=fake_run):
            self.assertEqual(sample_cpu_many([], 1.0), [])
            sampled = sample_cpu_many(names, 1.0)
        self.assertEqual(calls, [[
            "docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", *names,
        ]])
        # One core cap: docker's 100% is 100% of that quota. Bad lines are skipped.
        self.assertEqual(sampled, [80.0, 160.5, 40.0])

    def test_sample_cpu_many_returns_nothing_when_docker_stats_fails(self):
        class Proc:
            returncode = 1
            stdout = "90%\n"

        with mock.patch("bench.dockerbox.subprocess.run", return_value=Proc()):
            self.assertEqual(sample_cpu_many(["sim-0", "sim-1"], 1.0), [])

    def test_latency_and_drift(self):
        self.assertEqual(implied_tick(7, 9, 100), 2)
        self.assertIsNone(implied_tick(50, 3, 10))
        self.assertEqual(latency_ms(0, 2, 5_000, 0, 1000, 100), 3000)
        self.assertAlmostEqual(drift_percent([(0, 0), (2000, 1)], 1000), -50)
        self.assertEqual(ticks_advanced(0, 0, 5, 100), 5)
        self.assertEqual(ticks_advanced(0, 2, 2, 100), 1)

    def test_cutoff_order(self):
        limits = load_profile(ROOT / "profiles" / "ci.json").limits
        self.assertEqual(choose_cutoff({"reads": 10, "qualityErrors": 5, "mismatches": 0,
                                        "cpuPercentOfQuota": {"max": 10}, "memoryPercent": {"max": 10},
                                        "driftPercent": 0}, limits), "quality")
        self.assertEqual(choose_cutoff({"reads": 10, "qualityErrors": 0, "mismatches": 0,
                                        "cpuPercentOfQuota": {"max": 80}, "memoryPercent": {"max": 10},
                                        "driftPercent": 0}, limits), "cpu")
        self.assertEqual(choose_cutoff({"reads": 10, "qualityErrors": 0, "mismatches": 0,
                                        "cpuPercentOfQuota": {"max": 10}, "memoryPercent": {"max": 90},
                                        "driftPercent": 0}, limits), "memory")
        self.assertEqual(choose_cutoff({"reads": 10, "qualityErrors": 0, "mismatches": 0,
                                        "cpuPercentOfQuota": {"max": 10}, "memoryPercent": {"max": 10},
                                        "driftPercent": -20}, limits), "drift")


class _Fake:
    name = "fake"
    notes: list[str] = []

    def __init__(self, cpus, bad=False, stale=False, sim_cpu=None):
        self.cpus = cpus
        self.bad = bad
        self.stale = stale
        self.sim_cpu = sim_cpu
        self.step = 0
        self.cfg = None
        self.notes = []

    def setup(self, cfg):
        self.cfg = cfg

    def apply_step(self, points, period_ms, protocol, cfg):
        self.cfg = cfg
        self.step += 1

    def read_values(self, indexes, now_ms):
        at = self.cfg.epoch_ms if self.stale else now_ms
        rows = []
        for index in indexes:
            if self.bad:
                rows.append(TagReading(index, None, False, "Bad"))
            else:
                rows.append(TagReading(index, raw_at(index, at, self.cfg), True, "Good"))
        return rows

    def sample_host(self):
        cpu = self.cpus[min(self.step, len(self.cpus)) - 1]
        return HostSample(
            cpu_percent_of_quota=cpu, memory_percent=10, rss_bytes=1024, heap_bytes=512,
            sim_cpu_percent=self.sim_cpu,
        )

    def cleanup(self):
        return None


def _profile(**overrides):
    data = {
        "name": "unit",
        "points": [20, 40],
        "periodMs": 1000,
        "percentChanging": 100,
        "protocols": ["modbus"],
        "warmupSeconds": 0,
        "sampleSeconds": 2,
        "sampleIntervalSeconds": 1,
        "sampleTags": 4,
        "registersPerUnit": 10,
        "maxWallSeconds": 30,
        "cutoffs": {"minReads": 4},
    }
    data.update(overrides)
    return profile_from_dict(data)


class RampTest(unittest.TestCase):
    def test_stops_on_cpu_and_writes_report(self, tmp=None):
        clock = ManualClock()
        report = run_ramp(_profile(), _Fake([10, 90]), clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "cpu")
        self.assertEqual(len(report["steps"]), 2)
        self.assertGreater(report["steps"][0]["valuesPerSec"], 0)
        text = render_markdown(report)
        self.assertIn("cpu", text)
        directory = ROOT / "out" / "unit-report"
        json_path, md_path = write_report(report, directory)
        self.assertTrue(json_path.exists() and md_path.exists())

    def test_simulator_saturation_is_not_recorded_as_a_gateway_cutoff(self):
        clock = ManualClock()
        report = run_ramp(
            _profile(points=[20]),
            _Fake([90], sim_cpu=95),
            clock=clock,
            epoch_ms=int(clock.time() * 1000),
            stop_when_sim_saturated=True,
        )
        self.assertEqual(report["stoppedBecause"], "simulator")
        self.assertTrue(report["steps"][0]["simSaturated"])
        self.assertIsNone(report["steps"][0].get("cutoff"))

    def test_stops_on_quality(self):
        clock = ManualClock()
        report = run_ramp(_profile(), _Fake([5, 5], bad=True), clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "quality")
        self.assertEqual(len(report["steps"]), 1)

    def test_stops_on_drift_when_values_stay_at_epoch(self):
        clock = ManualClock()
        epoch = int(clock.time() * 1000)
        report = run_ramp(_profile(), _Fake([5, 5], stale=True), clock=clock, epoch_ms=epoch)
        self.assertEqual(report["stoppedBecause"], "drift")
        self.assertLess(report["steps"][0]["driftPercent"], -10)

    def test_refuses_a_plan_that_exceeds_the_trial_window(self):
        with self.assertRaises(PlanError):
            profile_from_dict({
                "points": [10, 20, 30],
                "sampleSeconds": 3000,
                "warmupSeconds": 0,
                "maxWallSeconds": 6600,
            })

    def test_runtime_budget_stops_before_the_next_step(self):
        clock = ManualClock()
        # Built directly so the plan check does not reject it. The runner still
        # refuses to start a step that would cross the cap.
        profile = Profile(
            name="budget",
            description="",
            points=[10, 20, 30],
            period_ms=[1000],
            percent_changing=100,
            protocols=["modbus"],
            warmup_seconds=0,
            sample_seconds=2,
            sample_interval_seconds=1,
            sample_tags=4,
            limits=Limits(),
            max_wall_seconds=3,
            registers_per_unit=10,
            metrics_per_device=500,
            modbus_extra_ports=0,
            tag_batch=2000,
            require_devices=True,
            reference={},
            oip={},
        )
        report = run_ramp(profile, _Fake([1, 1, 1]), clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "budget")
        self.assertEqual(len(report["steps"]), 1)

    def test_trial_seconds_tighten_the_wall_cap(self):
        self.assertEqual(effective_wall_seconds(30, 3), 3)
        self.assertEqual(effective_wall_seconds(30, None), 30)
        clock = ManualClock()
        profile = Profile(
            name="trial",
            description="",
            points=[10, 20, 30],
            period_ms=[1000],
            percent_changing=100,
            protocols=["modbus"],
            warmup_seconds=0,
            sample_seconds=2,
            sample_interval_seconds=1,
            sample_tags=4,
            limits=Limits(),
            max_wall_seconds=30,
            registers_per_unit=10,
            metrics_per_device=500,
            modbus_extra_ports=0,
            tag_batch=2000,
            require_devices=True,
            reference={},
            oip={},
        )
        report = run_ramp(profile, _Fake([1, 1, 1]), clock=clock, epoch_ms=int(clock.time() * 1000), wall_seconds=3)
        self.assertEqual(report["stoppedBecause"], "budget")
        self.assertEqual(len(report["steps"]), 1)
        self.assertEqual(report["maxWallSeconds"], 3)
        self.assertEqual(report["trialSecondsLeft"], 3)
        self.assertIn("remaining trial", render_markdown(report))

    def test_read_timestamp_follows_the_host_sample(self):
        clock = ManualClock()
        seen = []

        class Probe(_Fake):
            def read_values(self, indexes, now_ms):
                seen.append((now_ms, int(clock.time() * 1000)))
                return super().read_values(indexes, now_ms)

        def sampler():
            clock.sleep(2)
            return HostSample(cpu_percent_of_quota=1, memory_percent=1)

        profile = _profile(points=[4], percentChanging=100, sampleSeconds=0, sampleIntervalSeconds=0, sampleTags=1, maxWallSeconds=10)
        run_ramp(profile, Probe([1]), clock=clock, sampler=sampler, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(seen[0][0], seen[0][1])
        self.assertEqual(seen[0][0], int(clock.wall0 * 1000) + 2000)

    def test_values_per_sec_counts_ticks_advanced(self):
        clock = ManualClock()

        class Jump(_Fake):
            def __init__(self):
                super().__init__([1])
                self.calls = 0

            def read_values(self, indexes, now_ms):
                del now_ms
                self.calls += 1
                tick = 0 if self.calls == 1 else 5
                rows = []
                for index in indexes:
                    rows.append(TagReading(index, (index % 1000 + tick) % 30_000, True, "Good"))
                return rows

        profile = _profile(
            points=[20], percentChanging=100, sampleSeconds=5, sampleIntervalSeconds=5,
            sampleTags=4, maxWallSeconds=30,
        )
        report = run_ramp(profile, Jump(), clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "completed", report.get("error"))
        # Five seconds, and the value jumps 5 ticks. Counting reads would report 4/s.
        self.assertAlmostEqual(report["steps"][0]["valuesPerSec"], 20)

    def test_packaged_profiles_fit_their_windows(self):
        for path in sorted((ROOT / "profiles").glob("*.json")):
            load_profile(path)
        trial = load_profile(ROOT / "profiles" / "reference-trial.json")
        self.assertLessEqual(trial.planned_seconds(), 6600)
        self.assertIn("modbus", trial.protocols)
        dev = load_profile(ROOT / "profiles" / "dev.json")
        self.assertLessEqual(max(dev.points), 10_000)
        full = load_profile(ROOT / "profiles" / "scale-full.json")
        self.assertEqual([row["cpus"] for row in full.raw["sizes"]], [2, 4, 8, 16])
        self.assertEqual(full.percent_changing, 100)
        self.assertEqual(full.period_ms, [1000])
        for name in ("oip-trial", "oip-edge", "oip-edge2", "oip-edge-rest"):
            loaded = load_profile(ROOT / "profiles" / f"{name}.json")
            self.assertEqual(loaded.oip.get("opcuaNodeId"), "ns=2;s=Tags/{provider}/{path}")
            self.assertEqual(loaded.tag_batch, 2000)


class PayloadTest(unittest.TestCase):
    def test_reference_uses_one_device_and_unit_ids_in_the_item_path(self):
        cfg = BenchConfig(points=120, percent_changing=10, registers_per_unit=50)
        body = modbus_device_body("benchmodbus", "172.17.0.1", 25020)
        self.assertEqual(body["config"]["profile"]["type"], "ModbusTcp")
        self.assertEqual(body["config"]["settings"]["connectivity"]["hostname"], "172.17.0.1")
        self.assertFalse(body["config"]["settings"]["advanced"]["zeroBasedAddressing"])
        tag = reference_tag(100, cfg, "modbus")
        self.assertEqual(tag["opcItemPath"], "[benchmodbus]3.HR1")
        self.assertEqual(tag["opcServer"], "the reference platform OPC UA Server")
        self.assertEqual(tag["dataType"], "Int2")
        opc = reference_tag(4, cfg, "opcua")
        self.assertEqual(opc["opcItemPath"], "ns=1;i=1000004")
        self.assertEqual(opc["opcServer"], "benchopc")

    def test_oip_resources_match_the_config_envelope(self):
        cfg = BenchConfig(points=20, registers_per_unit=10, percent_changing=10)
        device = oip_modbus_device("bench-mb-0", "127.0.0.1", 25020, 1000)
        self.assertEqual(device["apiVersion"], "oip/v1")
        self.assertEqual(device["metadata"]["module"], "org.oip.driver-modbus")
        self.assertEqual(device["kind"], "Device")
        self.assertRegex(device["metadata"]["name"], r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        tag = oip_tag(11, cfg, "modbus", "bench-mb-1", 1000)
        self.assertEqual(tag["metadata"]["module"], "tags")
        self.assertEqual(tag["metadata"]["name"], "p0000011")
        self.assertEqual(tag["spec"]["path"], "bench/p0000011")
        self.assertEqual(tag["spec"]["opcItemPath"], "2.HR2")
        self.assertEqual(tag["spec"]["type"], "opc")
        self.assertEqual(tag["spec"]["scanPeriodMillis"], 1000)

    def test_reading_parser(self):
        rows = parse_readings({"values": [{"value": 4, "quality": "Good", "timestamp": 1_700_000_000_000}]}, [3])
        self.assertTrue(rows[0].good)
        self.assertEqual(rows[0].value, 4)

    def test_reference_target_posts_the_documented_routes(self):
        calls = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                calls.append((method, path, body, query, content_type))
                if method == "POST" and path.endswith("/device"):
                    return 201, {"success": True, "changes": [{"newSignature": "sig-new"}]}
                if method == "POST" and path.endswith("/tags/import"):
                    return 200, {"failureCount": 0}
                if method == "GET" and path.endswith("/driftGauge"):
                    return 200, {"clockDrift": 12}
                if method == "GET" and path.endswith("/currentGauges"):
                    return 200, {"heapMemory": 2.85746728e8, "maxMemory": 1073741824}
                if method == "GET" and path.endswith("/device/benchmodbus"):
                    return 200, {"signature": "sig-fresh"}
                if method == "DELETE":
                    return 204, {}
                return 404, {}

        class Client:
            def __init__(self, endpoint):
                self.endpoint = endpoint
                self.nodes = []

            def connect(self):
                return None

            def get_node(self, node_id):
                self.nodes.append(node_id)
                return node_id

            def read_attributes(self, nodes):
                values = []
                for node_id in nodes:
                    index = int(node_id.rsplit("p", 1)[-1])
                    values.append(_DataValue(index % 1000))
                return values

            def disconnect(self):
                return None

        client = Client("opc.tcp://127.0.0.1:62541")
        reader = OpcUaReader("opc.tcp://127.0.0.1:62541", client_factory=lambda endpoint: client)
        target = referenceTarget(Transport(), "172.17.0.1", 25020, 24840, batch=4, reader=reader)
        clock = ManualClock()
        profile = _profile(points=[6], percentChanging=0, sampleSeconds=1, sampleTags=4, maxWallSeconds=10)
        report = run_ramp(profile, target, clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "completed", report.get("error"))
        device_posts = [body for method, path, body, _, _ in calls if method == "POST" and path.endswith("/device")]
        self.assertEqual(len(device_posts), 1)
        self.assertIsInstance(device_posts[0], list)
        self.assertEqual(device_posts[0][0]["config"]["profile"]["type"], "ModbusTcp")
        imported = [call for call in calls if call[0] == "POST" and call[1].endswith("/tags/import")]
        self.assertGreaterEqual(len(imported), 2)
        self.assertEqual(sum(len(call[2]["tags"]) for call in imported), 6)
        self.assertTrue(all(call[3]["type"] == "json" and call[3]["collisionPolicy"] == "Overwrite" for call in imported))
        self.assertTrue(all(call[4] == "application/octet-stream" for call in imported))
        self.assertFalse(any(path.endswith("/tags/read") for _, path, _, _, _ in calls))
        self.assertIn("ns=2;s=[default]p0000000", client.nodes)
        self.assertTrue(any(path.endswith("/systemPerformance/driftGauge") for _, path, _, _, _ in calls))
        self.assertTrue(any(path.endswith("/systemPerformance/currentGauges") for _, path, _, _, _ in calls))
        self.assertEqual(report["steps"][0]["referenceDriftGaugeMs"], 12)
        self.assertEqual(report["steps"][0]["heapBytes"]["max"], 285746728)
        self.assertTrue(any(method == "DELETE" and path.endswith("/sig-fresh") for method, path, _, _, _ in calls))
        self.assertFalse(any("/tag-provider/" in path for _, path, _, _, _ in calls))
        self.assertIsNone(reader._client)

    def test_oip_history_option_writes_one_history_setting_per_tag_and_counts_rows(self):
        posted = []
        written = {"n": 0}

        class Transport:
            def request(self, method, path, body=None, query=None):
                if path == "/api/v1/config/batch":
                    # The target reuses its batch list after a post; keep a copy.
                    posted.append(json.loads(json.dumps(body)))
                    return 200, {}
                if path == "/api/v1/tags/read":
                    return 200, {"results": []}
                if path == "/api/v1/diagnostics/history/providers":
                    written["n"] += 40
                    return 200, {"collector": {"written": written["n"], "dropped": 0, "pending": 3,
                                               "consecutiveFlushFailures": 0}}
                return 200, {}

        clock = ManualClock()
        target = OipTarget(Transport(), "172.17.0.1", [25020], 24840, history={}, tag_batch=25, clock=clock)
        profile = _profile(points=[40], sampleSeconds=2, maxWallSeconds=10)
        report = run_ramp(profile, target, clock=clock, epoch_ms=int(clock.time() * 1000))
        kinds = [item["kind"] for body in posted for item in body["items"]]
        self.assertEqual(kinds.count("Tag"), 40)
        self.assertEqual(kinds.count("HistorySettings"), 40)
        history = [item for body in posted for item in body["items"] if item["kind"] == "HistorySettings"]
        self.assertEqual(history[0]["metadata"]["module"], "historian")
        self.assertEqual(history[0]["spec"], {
            "tagPath": "[default]bench/p0000000", "sampleMode": "onChange", "storageProvider": "local",
        })
        # Each batch holds one kind, so a 409 refresh lists one config kind.
        self.assertTrue(all(len({item["kind"] for item in body["items"]}) == 1 for body in posted))
        step = report["steps"][0]
        self.assertEqual(step["history"]["samples"], 3)
        self.assertEqual(step["history"]["rowsWritten"], 80)
        self.assertAlmostEqual(step["history"]["rowsPerSec"], 40.0)
        self.assertEqual(step["history"]["dropped"], 0)

    def test_a_dropped_history_row_is_data_loss(self):
        self.assertIsNone(history_cutoff({}))
        self.assertIsNone(history_cutoff({"history": {"dropped": 0, "droppedTotal": 0}}))
        self.assertEqual(history_cutoff({"history": {"dropped": 1, "droppedTotal": 1}}), "data-loss")
        # Dropped before the window (warmup or an earlier step) is still lost.
        self.assertEqual(history_cutoff({"history": {"dropped": 0, "droppedTotal": 5}}), "data-loss")
        # History on but no counters: nothing shows the rows were kept.
        self.assertEqual(history_cutoff({"history": None}), "data-loss")
        self.assertEqual(history_cutoff({"history": {"dropped": 0}}), "data-loss")

    def test_oip_history_drop_stops_the_ramp_as_data_loss(self):
        written = {"n": 0}

        class Transport:
            def request(self, method, path, body=None, query=None):
                if path == "/api/v1/tags/read":
                    return 200, {"results": []}
                if path == "/api/v1/diagnostics/history/providers":
                    written["n"] += 40
                    return 200, {"collector": {"written": written["n"], "dropped": 7 if written["n"] > 80 else 0,
                                               "pending": 3, "consecutiveFlushFailures": 0}}
                return 200, {}

        clock = ManualClock()
        target = OipTarget(Transport(), "172.17.0.1", [25020], 24840, history={}, tag_batch=25, clock=clock)
        report = run_ramp(_profile(points=[40], sampleSeconds=2, maxWallSeconds=10), target, clock=clock,
                          epoch_ms=int(clock.time() * 1000))
        # Data loss is the stop reason even when another cutoff (here quality: the fake reads nothing) also crossed.
        self.assertEqual(report["stoppedBecause"], "data-loss")
        step = report["steps"][0]
        self.assertEqual(step["cutoff"], "data-loss")
        self.assertEqual(step["history"]["dropped"], 7)

    def test_oip_clock_drift_is_read_from_the_gateway_drift_object(self):
        class Transport:
            def request(self, method, path, body=None, query=None):
                if path == "/api/v1/diagnostics/performance":
                    return 200, {"clockDrift": {"intervalMs": 1000, "drift": 0.0123, "lateMillis": 12.3, "samples": 9}}
                return 200, {}

        sample = OipTarget(Transport(), "172.17.0.1", [25020], 24840).sample_host()
        self.assertAlmostEqual(sample.clock_drift_percent, 1.23)

    def test_oip_without_history_option_writes_no_history_settings(self):
        posted = []

        class Transport:
            def request(self, method, path, body=None, query=None):
                if path == "/api/v1/config/batch":
                    posted.append(json.loads(json.dumps(body)))
                if path == "/api/v1/diagnostics/history/providers":
                    raise AssertionError("history stats read with history off")
                return 200, {"results": []}

        clock = ManualClock()
        target = OipTarget(Transport(), "172.17.0.1", [25020], 24840)
        report = run_ramp(_profile(points=[20], sampleSeconds=1, maxWallSeconds=10), target, clock=clock,
                          epoch_ms=int(clock.time() * 1000))
        self.assertTrue(all(item["kind"] == "Tag" for body in posted for item in body["items"]))
        self.assertNotIn("history", report["steps"][0])

    def test_oip_stops_when_the_device_kind_is_rejected(self):
        class Transport:
            def request(self, method, path, body=None, query=None):
                if method == "PUT" and path.endswith("/Device/bench-mb-0"):
                    return 422, {"detail": "unknown kind Device"}
                return 500, {}

        target = OipTarget(Transport(), "127.0.0.1", [25020], 24840, require_devices=True)
        clock = ManualClock()
        profile = _profile(points=[4], sampleSeconds=1, maxWallSeconds=10)
        report = run_ramp(profile, target, clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "error")
        self.assertIn("Device", report["error"])


def _saturated(values, reason):
    return {
        "stoppedBecause": reason,
        "steps": [
            {"points": 10000, "valuesPerSec": values, "cutoff": None, "simSaturated": False},
            {"points": 20000, "valuesPerSec": values * 2, "cutoff": reason, "simSaturated": False},
        ],
    }


class ScalingTest(unittest.TestCase):
    def test_efficiency_is_throughput_ratio_not_per_cpu_of_a_fixed_load(self):
        # The old metric divided one offered load by vCPU, so 4 vCPU scored 0.50
        # while the gateway was idle. ADR 0010 divides the saturated rates, then the core ratio.
        rows = efficiency(
            [_saturated(200, "cpu"), _saturated(560, "cpu")],
            [2, 4],
        )
        self.assertEqual(rows[0]["valuesPerSec"], 200)
        self.assertEqual(rows[1]["valuesPerSec"], 560)
        self.assertEqual(rows[0]["efficiency"], 1)
        self.assertIsNone(rows[0]["meetsMinimum"])
        self.assertAlmostEqual(rows[1]["efficiency"], (560 / 200) / (4 / 2))
        self.assertAlmostEqual(rows[1]["efficiency"], 1.4)
        self.assertTrue(rows[1]["meetsMinimum"])
        self.assertEqual(rows[1]["baselineCpus"], 2)

    def test_a_fixed_load_that_never_cuts_off_has_no_efficiency(self):
        idle = {"stoppedBecause": "completed", "steps": [
            {"points": 20000, "valuesPerSec": 2000, "cutoff": None, "simSaturated": False},
        ]}
        rows = efficiency([idle, idle, idle], [2, 4, 8])
        self.assertTrue(all(row["efficiency"] is None for row in rows))
        self.assertTrue(all(row["cutoffReached"] is False for row in rows))
        text = render_markdown({
            "kind": "scale",
            "target": "oip",
            "profile": "scale-full",
            "formula": "E(n) = (T(n) / T(baseline)) / (n / baseline)",
            "efficiencyMin": 0.7,
            "baselineCpus": 2,
            "sizes": rows,
        })
        self.assertIn("0.70", text)
        self.assertIn("E(n)", text)
        self.assertNotIn("per vCPU", text)

    def test_sim_saturation_is_not_a_gateway_cutoff(self):
        report = {"stoppedBecause": "simulator", "steps": [
            {"points": 5000, "valuesPerSec": 4000, "cutoff": None, "simSaturated": False},
            {"points": 10000, "valuesPerSec": 8000, "cutoff": None, "simSaturated": True},
        ]}
        rows = efficiency([_saturated(4000, "cpu"), report], [2, 4])
        self.assertTrue(rows[1]["simulatorLimited"])
        self.assertIsNone(rows[1]["efficiency"])
        self.assertEqual(rows[1]["points"], 5000)

    def test_last_good_step_is_the_sustained_rate(self):
        report = {"stoppedBecause": "cpu", "steps": [
            {"points": 10000, "valuesPerSec": 9000, "cutoff": None},
            {"points": 20000, "valuesPerSec": 15000, "cutoff": "cpu"},
        ]}
        rows = efficiency([report], [2])
        self.assertEqual(rows[0]["valuesPerSec"], 9000)
        self.assertEqual(rows[0]["points"], 10000)
        self.assertTrue(rows[0]["cutoffReached"])

    def test_heap_and_simulator_fleet_follow_the_tier(self):
        self.assertEqual(heap_for_memory("4g"), "2g")
        self.assertEqual(heap_for_memory("8g"), "4g")
        self.assertEqual(heap_for_memory("768m"), "384m")
        small = plan_sim_fleet(2, 160_000)
        large = plan_sim_fleet(8, 1_280_000)
        self.assertGreaterEqual(large.instances, small.instances)
        self.assertGreaterEqual(large.instances, 8)
        self.assertEqual(large.cpus_each, 1.0)
        self.assertGreaterEqual(plan_sim_fleet(2, 1_280_000).instances, math.ceil(1_280_000 / 80_000))

    def test_profile_sizes_and_the_throughput_workload(self):
        profile = load_profile(ROOT / "profiles" / "scale-dev.json")
        sizes = resolve_sizes(None, profile.raw["sizes"])
        self.assertEqual([(row.cpus, row.memory) for row in sizes], [(2, "768m"), (4, "1024m")])
        self.assertEqual([(row.cpus, row.memory) for row in resolve_sizes("2:512m", None)], [(2, "512m")])
        self.assertTrue(is_throughput_ramp(profile))
        self.assertIs(prepare_scale_profile(profile), profile)
        self.assertEqual(select_sizes(sizes, 2, "4g"), [Size(2, "4g")])
        with self.assertRaises(ValueError):
            select_sizes(sizes, 2, None)

    def test_scale_refuses_a_profile_that_fails_the_throughput_rule(self):
        default = ramp_points(160_000)
        self.assertEqual(default, [5_000, 10_000, 20_000, 40_000, 80_000, 160_000])
        full = load_profile(ROOT / "profiles" / "scale-full.json")
        self.assertTrue(is_throughput_ramp(full))
        self.assertIs(prepare_scale_profile(full), full)
        self.assertNotEqual(full.points, default)

        flat = profile_from_dict({
            "name": "flat",
            "points": [20000],
            "percentChanging": 10,
            "periodMs": 1000,
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 5000,
            "scaleMaxPoints": 40000,
        })
        with self.assertRaises(PlanError) as caught:
            prepare_scale_profile(flat)
        message = str(caught.exception)
        self.assertEqual(flat.points, [20000])
        self.assertEqual(flat.percent_changing, 10)
        self.assertIn("'flat'", message)
        self.assertIn("is_throughput_ramp", message)
        self.assertIn("at least 3", message)
        self.assertIn("4x", message)
        self.assertIn(str(default), message)

        # Three rising points whose last is under 4x the first still fail the rule.
        short = profile_from_dict({
            "name": "short",
            "points": [5000, 10000, 15000],
            "percentChanging": 100,
            "periodMs": 1000,
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 5000,
        })
        self.assertFalse(is_throughput_ramp(short))
        with self.assertRaises(PlanError) as caught:
            prepare_scale_profile(short)
        self.assertIn("at least 3", str(caught.exception))
        self.assertIn("4x", str(caught.exception))
        self.assertEqual(short.points, [5000, 10000, 15000])

        # Two points can be 4x apart and still fail the 3-point rule.
        two = profile_from_dict({
            "name": "two",
            "points": [5000, 20000],
            "percentChanging": 100,
            "periodMs": 1000,
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 5000,
        })
        self.assertFalse(is_throughput_ramp(two))
        with self.assertRaises(PlanError) as caught:
            prepare_scale_profile(two)
        self.assertIn("at least 3", str(caught.exception))

        ok = profile_from_dict({
            "name": "ok-ramp",
            "points": [8000, 16000, 32000],
            "percentChanging": 100,
            "periodMs": 1000,
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 5000,
        })
        self.assertIs(prepare_scale_profile(ok), ok)
        self.assertEqual(ok.points, [8000, 16000, 32000])

        body = {
            "name": "flat-hold",
            "points": [20000],
            "percentChanging": 10,
            "periodMs": 1000,
            "sampleSeconds": 1,
            "warmupSeconds": 0,
            "maxWallSeconds": 5000,
            "sizes": [{"cpus": 2, "memory": "768m"}],
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "flat.json"
            path.write_text(json.dumps(body))
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                code = main(["scale", "--profile", str(path), "--target", "oip"])
        self.assertEqual(code, 2)
        text = err.getvalue()
        self.assertTrue(text.startswith("error: "))
        self.assertIn("flat-hold", text)
        self.assertIn("is_throughput_ramp", text)
        self.assertIn("at least 3", text)
        self.assertIn("4x", text)
        self.assertIn(str(default), text)

    def test_gateway_command_keeps_the_password_off_the_command_line(self):
        secret = "s3cret-value"
        command = oip_gateway_command(
            image="oip-gateway:local",
            cpus=2,
            memory="4g",
            publish="8090:8088",
            heap="2g",
            env_file="/tmp/oip-admin.env",
        )
        text = " ".join(command)
        self.assertNotIn(secret, text)
        self.assertNotIn("OIP_ADMIN_PASSWORD", text)
        self.assertIn("--env-file", command)
        self.assertIn("/tmp/oip-admin.env", command)
        self.assertIn("4840:4840", command)
        self.assertIn("OIP_OPCUA_BIND=0.0.0.0", command)
        self.assertIn("-Xmx2g", text)
        self.assertEqual(remove_container_command("oip-bench-gw"), ["docker", "rm", "-v", "-f", "oip-bench-gw"])
        self.assertEqual(read_admin_password(secret, source="unit"), secret)
        self.assertEqual(
            read_admin_password("OIP_ADMIN_PASSWORD=" + secret + "\n", source="unit"),
            secret,
        )
        env = password_env_text(secret)
        self.assertTrue(env.startswith("OIP_ADMIN_PASSWORD="))
        self.assertNotIn(secret, " ".join(command))

    def test_sim_fleet_is_one_process_per_core_and_fans_out(self):
        command = sim_container_command(
            name="oip-bench-sim-0", image="oip-bench-points:local", cpus=1, memory="512m",
            host_modbus_port=25020, host_http_port=28080, points=1000,
        )
        self.assertIn("--cpus", command)
        self.assertEqual(command[command.index("--cpus") + 1], "1")
        self.assertIn("--no-sparkplug", command)
        reference = fleet_layout(2, reference=True)
        self.assertNotEqual(reference[0]["modbusPort"], 25020)
        direct = fleet_layout(2, reference=False)
        self.assertEqual([row["modbusPort"] for row in direct], [25020, 25021])
        backends = []
        servers = []
        for _ in range(2):
            sock = socket.socket()
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", 0))
            sock.listen(8)
            sock.settimeout(2)
            servers.append(sock)
            backends.append(("127.0.0.1", sock.getsockname()[1]))
        fanout = ConnectionFanout("127.0.0.1", 0, backends)
        port = fanout.start()
        try:
            for _ in range(4):
                client = socket.create_connection(("127.0.0.1", port), timeout=2)
                client.sendall(b"x")
                client.close()
            got = [0, 0]
            for index, server in enumerate(servers):
                server.settimeout(1)
                while True:
                    try:
                        conn, _addr = server.accept()
                    except socket.timeout:
                        break
                    got[index] += 1
                    conn.close()
            self.assertEqual(sum(got), 4)
            self.assertTrue(all(count > 0 for count in got))
        finally:
            fanout.stop()
            for server in servers:
                server.close()


class _DataValue:
    def __init__(self, value, good=True, name="Good", timestamp=None):
        self.StatusCode = _Status(good, name)
        self.Value = _Variant(value)
        self.SourceTimestamp = timestamp
        self.ServerTimestamp = None


class _Status:
    def __init__(self, good, name):
        self._good = good
        self.name = name

    def is_good(self):
        return self._good


class _Variant:
    def __init__(self, value):
        self.Value = value


class referenceContractTest(unittest.TestCase):
    def test_import_failure_count_is_an_error(self):
        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                if method == "POST" and path.endswith("/device"):
                    return 201, {"changes": [{"newSignature": "sig"}]}
                if method == "POST" and path.endswith("/tags/import"):
                    return 200, {"failureCount": 2, "errors": ["bad"]}
                return 404, {}

        target = referenceTarget(Transport(), "172.17.0.1", 25020, 24840, batch=10)
        cfg = BenchConfig(points=2, percent_changing=0, period_ms=1000, registers_per_unit=10)
        with self.assertRaises(Exception) as caught:
            target.apply_step(2, 1000, "modbus", cfg)
        self.assertIn("failure", str(caught.exception))

    def test_device_conflict_is_replaced(self):
        calls = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                calls.append((method, path, body))
                if method == "POST" and path.endswith("/device") and len(calls) == 1:
                    return 409, {"success": False}
                if method == "GET" and path.endswith("/device/benchmodbus"):
                    return 200, {"signature": "old"}
                if method == "DELETE":
                    return 204, {}
                if method == "POST" and path.endswith("/device"):
                    return 200, {"success": True, "changes": [{"newSignature": "new"}]}
                return 404, {}

        target = referenceTarget(Transport(), "172.17.0.1", 25020, 24840)
        target._ensure_modbus()
        self.assertEqual(target.signature, "new")
        self.assertTrue(any(method == "DELETE" and path.endswith("/old") for method, path, _ in calls))
        self.assertIsInstance(calls[0][2], list)

    def test_opc_connection_uses_the_same_resource_array(self):
        seen = {}

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                seen["body"] = body
                return 201, {"changes": [{"newSignature": "opc"}]}

        target = referenceTarget(Transport(), "10.0.0.2", 25020, 24840)
        target._ensure_opc()
        self.assertEqual(target.opc_signature, "opc")
        self.assertEqual(seen["body"][0]["name"], "benchopc")
        self.assertEqual(seen["body"][0]["config"]["endpointUrl"], "opc.tcp://10.0.0.2:24840/oip/bench")

    def test_delete_tags_recreates_the_provider_and_is_opt_in(self):
        calls = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                calls.append((method, path, body))
                if method == "GET" and path.endswith("/tag-provider/default"):
                    return 200, {"name": "default", "signature": "prov", "config": {"type": "STANDARD"}}
                if method == "DELETE" and "/tag-provider/" in path:
                    return 204, {}
                if method == "POST" and path.endswith("/tag-provider"):
                    return 201, {"changes": [{"newSignature": "prov2"}]}
                return 404, {}

        left = referenceTarget(Transport(), "172.17.0.1", 25020, 24840, delete_tags=False)
        left.cleanup()
        self.assertFalse(any("/tag-provider" in path for _, path, _ in calls))
        self.assertTrue(any("no tag-delete" in note for note in left.notes))

        calls.clear()
        wiped = referenceTarget(Transport(), "172.17.0.1", 25020, 24840, delete_tags=True)
        wiped.cleanup()
        posted = [body for method, path, body in calls if method == "POST" and path.endswith("/tag-provider")]
        self.assertEqual(posted, [[{"name": "default", "config": {"type": "STANDARD"}}]])
        self.assertTrue(any(method == "DELETE" and path.endswith("/default/prov") for method, path, _ in calls))
        system = referenceTarget(Transport(), "172.17.0.1", 25020, 24840, provider="system", delete_tags=True)
        with self.assertRaises(Exception) as caught:
            system.cleanup()
        self.assertIn("system", str(caught.exception))

    def test_trial_parser_ignores_a_license_and_honors_an_expired_trial(self):
        self.assertEqual(parse_trial_seconds_left({"licenseMode": "Trial", "trialSecondsLeft": 4078, "expired": False}), 4078)
        self.assertEqual(parse_trial_seconds_left({"licenseMode": "Trial", "trialSecondsLeft": 10, "expired": True}), 0)
        self.assertIsNone(parse_trial_seconds_left({"licenseMode": "Licensed", "trialSecondsLeft": 0, "expired": False}))
        self.assertIsNone(parse_trial_seconds_left({"licenseMode": "Trial"}))

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                self.method = method
                return 200, {"licenseMode": "Trial", "trialSecondsLeft": 90, "expired": False}

        transport = Transport()
        target = referenceTarget(transport, "172.17.0.1", 25020, 24840)
        self.assertEqual(target.trial_seconds_left(), 90)
        self.assertFalse(target.trial_licensed)
        self.assertEqual(transport.method, "GET")

        class Licensed:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                return 200, {"licenseMode": "Licensed", "trialSecondsLeft": 0, "expired": False}

        licensed = referenceTarget(Licensed(), "172.17.0.1", 25020, 24840)
        self.assertIsNone(licensed.trial_seconds_left())
        self.assertTrue(licensed.trial_licensed)

    def test_octet_stream_header(self):
        seen = {}

        class Response:
            status = 200

            def read(self):
                return b"{}"

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def opener(req, timeout=0):
            del timeout
            seen["type"] = req.get_header("Content-type")
            seen["body"] = req.data
            return Response()

        import urllib.request
        original = urllib.request.urlopen
        urllib.request.urlopen = opener
        try:
            status, payload = HttpTransport("http://gw").request(
                "POST", "/data/api/v1/tags/import", {"tags": []}, content_type="application/octet-stream",
            )
        finally:
            urllib.request.urlopen = original
        self.assertEqual(status, 200)
        self.assertEqual(payload, {})
        self.assertEqual(seen["type"], "application/octet-stream")
        self.assertIn(b"tags", seen["body"])


class OpcUaReadTest(unittest.TestCase):
    def test_attribute_timestamp_and_quality(self):
        from datetime import datetime, timezone
        moment = datetime(2026, 1, 1, tzinfo=timezone.utc)
        parsed = attribute_from_datavalue(_DataValue(4, timestamp=moment))
        self.assertTrue(parsed.good)
        self.assertEqual(parsed.value, 4)
        self.assertEqual(parsed.timestamp_ms, int(moment.timestamp() * 1000))
        self.assertEqual(datetime_to_ms(datetime(2026, 1, 1)), int(calendar_ms()))
        bad = attribute_from_datavalue(_DataValue(None, good=False, name="Bad"))
        self.assertFalse(bad.good)
        self.assertEqual(reference_node_id("default", "p0000007"), "ns=2;s=[default]p0000007")

    def test_missing_asyncua_is_explained(self):
        import builtins
        real = builtins.__import__

        def guarded(name, *args, **kwargs):
            if name == "asyncua.sync" or name.startswith("asyncua"):
                raise ImportError("no asyncua")
            return real(name, *args, **kwargs)

        reader = OpcUaReader("opc.tcp://127.0.0.1:62541")
        builtins.__import__ = guarded
        try:
            with self.assertRaises(OpcUaError) as caught:
                reader.read(["ns=2;s=[default]p0000000"])
        finally:
            builtins.__import__ = real
        self.assertIn("requirements-reference.txt", str(caught.exception))

    def test_oip_opcua_read_is_behind_the_flag(self):
        calls = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                calls.append((method, path, body))
                if method == "PUT" and "/Device/" in path:
                    return 201, {}
                if method == "POST" and path == "/api/v1/config/batch":
                    return 200, {"created": len(body["items"]), "updated": 0}
                if method == "POST" and path.endswith("/tags/read"):
                    return 200, {"values": [{"value": 1, "quality": "Good", "timestamp": 0} for _ in body["paths"]]}
                if method == "DELETE":
                    return 204, {}
                return 500, {}

        seen_nodes = []

        class Client:
            def __init__(self, endpoint):
                self.endpoint = endpoint

            def connect(self):
                return None

            def get_node(self, node_id):
                seen_nodes.append(node_id)
                return node_id

            def read_attributes(self, nodes):
                return [_DataValue(int(node.rsplit("p", 1)[-1]) % 1000) for node in nodes]

            def disconnect(self):
                return None

        clock = ManualClock()
        profile = _profile(points=[2], percentChanging=0, sampleSeconds=0, sampleIntervalSeconds=0, sampleTags=2, maxWallSeconds=10)
        rest = OipTarget(Transport(), "127.0.0.1", [25020], 24840, require_devices=True)
        report = run_ramp(profile, rest, clock=clock, epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "completed", report.get("error"))
        self.assertTrue(any(path.endswith("/tags/read") for _, path, _ in calls))

        calls.clear()
        reader = OpcUaReader("opc.tcp://127.0.0.1:4840", client_factory=lambda endpoint: Client(endpoint))
        opc = OipTarget(
            Transport(), "127.0.0.1", [25020], 24840,
            read_via_opcua=True,
            opcua_read_endpoint="opc.tcp://127.0.0.1:4840",
            opcua_node_template="ns=2;s={path}",
            reader=reader,
        )
        report = run_ramp(profile, opc, clock=ManualClock(), epoch_ms=int(clock.time() * 1000))
        self.assertEqual(report["stoppedBecause"], "completed", report.get("error"))
        self.assertFalse(any(path.endswith("/tags/read") for _, path, _ in calls))
        self.assertIn("ns=2;s=bench/p0000000", seen_nodes)
        self.assertIsNone(reader._client)

        missing = OipTarget(Transport(), "127.0.0.1", [25020], 24840, read_via_opcua=True)
        with self.assertRaises(Exception) as caught:
            missing.read_values([0], 0)
        self.assertIn("M2.4", str(caught.exception))


def calendar_ms() -> int:
    import calendar
    from datetime import datetime
    moment = datetime(2026, 1, 1)
    return calendar.timegm(moment.utctimetuple()) * 1000


class LimitsTest(unittest.TestCase):
    def test_equal_cgroup_limits_and_reference_heap(self):
        self.assertEqual(heap_mb("2048"), 2048)
        self.assertEqual(heap_mb("2g"), 2048)
        self.assertEqual(heap_mb("512m"), 512)
        self.assertEqual(
            update_limits_command("reference", 2, "4g"),
            ["docker", "update", "--cpus", "2", "--memory", "4g", "reference"],
        )
        self.assertEqual(with_reference_heap_arg(["-n", "gw", "-m", "1024"], 2048), ["-n", "gw", "-m", "2048"])
        command = recreate_command({
            "Name": "/reference",
            "Config": {
                "Image": "reference-platform:pinned",
                "Env": ["ACCEPT_REFERENCE_EULA=Y"],
                "Cmd": ["-n", "gw"],
                "User": "",
            },
            "HostConfig": {
                "NanoCpus": 2_000_000_000,
                "Memory": 4 * 1024 ** 3,
                "NetworkMode": "bridge",
                "RestartPolicy": {"Name": "unless-stopped"},
                "Binds": ["reference-data:/usr/local/bin/reference/data"],
                "PortBindings": {"8088/tcp": [{"HostIp": "", "HostPort": "8088"}], "62541/tcp": [{"HostIp": "", "HostPort": "62541"}]},
                "ExtraHosts": ["host.docker.internal:host-gateway"],
            },
        }, cpus=2, memory="4g", heap_mb_value=2048)
        self.assertEqual(command[:6], ["docker", "run", "-d", "--name", "reference", "--cpus"])
        self.assertIn("--memory", command)
        self.assertIn("4g", command)
        self.assertIn("-v", command)
        self.assertIn("reference-data:/usr/local/bin/reference/data", command)
        self.assertIn("8088:8088", command)
        self.assertIn("62541:62541", command)
        self.assertIn("--restart", command)
        self.assertIn("unless-stopped", command)
        self.assertEqual(command[-4:], ["-n", "gw", "-m", "2048"])
        self.assertIn("reference-platform:pinned", command)

    def test_heap_recreate_restores_the_previous_container(self):
        calls = []

        def run(command):
            calls.append(command)
            if command[:2] == ["docker", "run"]:
                raise RuntimeError("boom")

        with self.assertRaises(RuntimeError):
            apply_reference_heap("reference", ["docker", "run", "image"], run)
        self.assertEqual(calls[0], ["docker", "rm", "-f", "reference-bench-prev"])
        self.assertEqual(calls[-2], ["docker", "rename", "reference-bench-prev", "reference"])
        self.assertEqual(calls[-1], ["docker", "start", "reference"])


class OipHarnessTest(unittest.TestCase):
    def test_login_retries_on_401_and_keeps_the_session_cookie(self):
        calls = []

        class Transport:
            def __init__(self):
                self.cookie = None
                self.csrf = None
                self.secrets = []
                self.last_headers = None
                self.puts = 0

            def request(self, method, path, body=None, query=None, content_type="application/json"):
                calls.append((method, path))
                if path == "/api/v1/auth/login":
                    self.last_headers = _CookieHeaders(["other=1; Path=/", "oip_session=abc; HttpOnly"])
                    return 200, {"username": "admin", "csrfToken": "csrf-1", "password": body["password"]}
                if method == "PUT" and "/Device/" in path:
                    self.puts += 1
                    if self.puts == 1:
                        return 401, {}
                    return 201, {}
                return 500, {}

        target = OipTarget(Transport(), "127.0.0.1", [25020], 24840, username="admin", password="pw")
        target.setup(BenchConfig(points=1))
        target._ensure_devices(1000, "modbus", BenchConfig(points=1, period_ms=1000))
        self.assertEqual(target.transport.cookie, "oip_session=abc")
        self.assertEqual(target.transport.csrf, "csrf-1")
        self.assertIn("pw", target.transport.secrets)
        self.assertEqual(calls[0], ("POST", "/api/v1/auth/login"))
        self.assertIn(("PUT", "/api/v1/config/org.oip.driver-modbus/gateway/Device/bench-mb-0"), calls)
        self.assertEqual(sum(1 for method, path in calls if path == "/api/v1/auth/login"), 2)

    def test_transport_sends_csrf_and_does_not_echo_the_password(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import threading

        seen = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                self.rfile.read(length)
                seen["cookie"] = self.headers.get("Cookie")
                seen["csrf"] = self.headers.get("X-CSRF-Token")
                self.send_response(500)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"echo s3cret")

            def log_message(self, fmt, *args):
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            transport = HttpTransport(f"http://127.0.0.1:{server.server_address[1]}")
            transport.cookie = "oip_session=abc"
            transport.csrf = "csrf-1"
            transport.secrets = ["s3cret"]
            _status, payload = transport.request("POST", "/api/v1/config/batch", {"items": []})
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(seen["cookie"], "oip_session=abc")
        self.assertEqual(seen["csrf"], "csrf-1")
        self.assertNotIn("s3cret", str(payload))

    def test_tag_batches_and_a_409_is_retried_with_stored_versions(self):
        posts = []

        class Transport:
            def __init__(self):
                self.batches = 0

            def request(self, method, path, body=None, query=None, content_type="application/json"):
                if method == "PUT" and "/Device/" in path:
                    return 201, {}
                if method == "GET" and path.endswith("/Tag"):
                    return 200, {"items": [
                        {"metadata": {"name": "p0000000", "version": 3}},
                        {"metadata": {"name": "p0000001", "version": 4}},
                    ]}
                if method == "POST" and path == "/api/v1/config/batch":
                    self.batches += 1
                    posts.append({
                        "scope": body.get("scope"),
                        "items": [
                            {"metadata": dict(item.get("metadata") or {}), "spec": dict(item.get("spec") or {})}
                            for item in body["items"]
                        ],
                    })
                    if self.batches == 1:
                        return 409, {"detail": "version conflict"}
                    return 200, {"updated": len(body["items"])}
                if method == "DELETE":
                    return 204, {}
                return 500, {}

        cfg = BenchConfig(points=3, percent_changing=0, period_ms=1000, registers_per_unit=10)
        target = OipTarget(Transport(), "127.0.0.1", [25020], 24840, tag_batch=2)
        target.apply_step(3, 1000, "modbus", cfg)
        self.assertEqual([len(body["items"]) for body in posts], [2, 2, 1])
        versions = {item["metadata"]["name"]: item["metadata"]["version"] for item in posts[1]["items"]}
        self.assertEqual(versions, {"p0000000": 3, "p0000001": 4})
        self.assertEqual(stored_versions({"items": [{"metadata": {"name": "p0000002", "version": 1.0}}]}), {"p0000002": 1})

    def test_device_409_updates_the_stored_version(self):
        versions = []

        class Transport:
            def request(self, method, path, body=None, query=None, content_type="application/json"):
                if method == "GET":
                    return 200, {"metadata": {"name": "bench-mb-0", "version": 2}}
                if method == "PUT":
                    versions.append(body["metadata"]["version"])
                    if body["metadata"]["version"] == 0:
                        return 409, {"detail": "conflict"}
                    return 200, {}
                return 500, {}

        target = OipTarget(Transport(), "127.0.0.1", [25020], 24840)
        target._ensure_devices(1000, "modbus", BenchConfig(points=1, period_ms=1000))
        self.assertEqual(versions, [0, 2])

    def test_opcua_username_is_not_written_into_errors(self):
        made = []

        class Client:
            def __init__(self, endpoint):
                self.endpoint = endpoint
                self.user = None
                self.password = None

            def set_user(self, user):
                self.user = user

            def set_password(self, password):
                self.password = password

            def connect(self):
                raise RuntimeError(f"rejected {self.password}")

        def factory(endpoint):
            client = Client(endpoint)
            made.append(client)
            return client

        reader = OpcUaReader(
            "opc.tcp://127.0.0.1:4840/oip",
            client_factory=factory,
            username="admin",
            password="s3cret",
        )
        with self.assertRaises(OpcUaError) as caught:
            reader.read(["ns=2;s=Tags/default/bench/p0000000"])
        self.assertNotIn("s3cret", str(caught.exception))
        self.assertEqual(made[0].user, "admin")
        self.assertEqual(made[0].password, "s3cret")
        bare = OipTarget(object(), "127.0.0.1", [25020], 24840)
        self.assertEqual(bare.opcua_node_template, "ns=2;s=Tags/{provider}/{path}")


class _CookieHeaders:
    def __init__(self, values):
        self._values = values

    def get_all(self, name):
        if name.lower() == "set-cookie":
            return list(self._values)
        return []


class CliTest(unittest.TestCase):
    def test_sim_container_matches_compose_unless_overridden(self):
        self.assertEqual(DEFAULT_SIM_CONTAINER, "oip-bench-points-1")
        self.assertEqual(resolve_sim_container(None, None), "oip-bench-points-1")
        self.assertEqual(resolve_sim_container(None, ["custom-points-1"]), "custom-points-1")
        self.assertEqual(resolve_sim_container("explicit", ["other"]), "explicit")
        args = build_parser().parse_args(["ramp", "--profile", "profiles/ci.json", "--target", "reference"])
        self.assertIsNone(args.sim_container)
        self.assertIsNone(args.cpus)
        self.assertFalse(args.delete_tags)
        self.assertFalse(args.read_via_opcua)
        self.assertIsNone(args.trial_seconds_left)

    def test_samplers_ask_docker_stats_once_for_the_fleet(self):
        names = [f"oip-bench-sim-{index}" for index in range(25)]
        batched = []
        gateway = []

        def fake_many(containers, allocated):
            batched.append((list(containers), allocated))
            return [10.0, 80.0, 25.0]

        def fake_one(container, allocated=None):
            gateway.append((container, allocated))
            return HostSample(cpu_percent_of_quota=12.0, memory_percent=3.0, rss_bytes=10)

        class Transport:
            def __init__(self, *args, **kwargs):
                pass

            def request(self, method, path, body=None, query=None, content_type="application/json"):
                return 404, {}

        target = mock.Mock()
        target.sample_host.return_value = HostSample(heap_bytes=5)
        with mock.patch("bench.cli.docker_available", return_value=True), \
                mock.patch("bench.cli.sample_cpu_many", side_effect=fake_many), \
                mock.patch("bench.cli.sample_container", side_effect=fake_one), \
                mock.patch("bench.cli.allocated_cpus", return_value=4.0), \
                mock.patch("bench.cli.HttpTransport", Transport):
            host = _sampler("gw", names, 2.0, target)()
            self.assertEqual(batched, [(names, 1.0)])
            self.assertEqual(host.sim_cpu_percent, 80.0)
            self.assertEqual(gateway, [("gw", 2.0)])
            self.assertEqual(host.cpu_percent_of_quota, 12.0)
            self.assertEqual(host.heap_bytes, 5)

            batched.clear()
            gateway.clear()
            with mock.patch("bench.cli.docker_available", return_value=False):
                quiet = _sampler("gw", names, 2.0, target)()
            self.assertEqual(batched, [])
            self.assertEqual(gateway, [])
            self.assertIsNone(quiet.sim_cpu_percent)

            args = build_parser().parse_args([
                "ramp",
                "--profile", str(ROOT / "profiles" / "ci.json"),
                "--target", "oip",
                "--container", "gw",
                "--gateway", "http://127.0.0.1:9",
            ])
            args.sim_container = list(names)
            merged = _target(args, load_profile(ROOT / "profiles" / "ci.json"), "172.17.0.1")
            host = merged.sample_host()
            self.assertEqual(batched, [(names, 1.0)])
            self.assertEqual(host.sim_cpu_percent, 80.0)
            self.assertEqual(gateway, [("gw", 4.0)])
            self.assertEqual(host.cpu_percent_of_quota, 12.0)


class ProtocolTest(unittest.TestCase):
    def test_sparkplug_int_matches_the_existing_codec(self):
        other = _load_other_sparkplug()
        import sparkplug
        metric = {"name": "P0000000", "datatype": 3, "int_value": 12}
        self.assertEqual(sparkplug.encode_payload(2000, 1, [metric]), other.encode_payload(2000, 1, [metric]))
        decoded = sparkplug.decode_payload(sparkplug.encode_payload(2000, 1, [metric]))
        self.assertEqual(decoded["metrics"][0]["int_value"], 12)

    def test_self_test(self):
        import server
        self.assertEqual(server.self_test(), 0)


def _load_other_sparkplug():
    path = ROOT.parent / "test-infra" / "sim" / "sparkplug" / "sparkplug.py"
    spec = importlib.util.spec_from_file_location("oip_testinfra_sparkplug", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


if __name__ == "__main__":
    unittest.main()
