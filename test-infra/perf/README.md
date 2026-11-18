# High-point-count simulator

PRF-012 defines it. Baselines live in `test-infra/perf/perf-requirements.json`.

The compose stack in `test-infra/compose.yaml` is a single shared waveform for functional tests. It is not this simulator. Hardware-floor benchmark results (PRF-001 and the runs after this simulator) are Not implemented. This directory configures the simulator those runs use.

The server is the bench point process (`bench/sim`). One address space is served over Modbus TCP, OPC UA, and MQTT/Sparkplug. Values are computed from the point index and the scan, not stored per point. `test-infra/perf/prf-012.json` sets `fullPoints` to 150,000 and `ciPoints` to the size the Simulator stack smoke job serves. A driver for this scenario refuses 127.0.0.1 and any other loopback target. The check binds a non-loopback interface, so that address is not accepting connections.

Change fraction and scan period are the config fields `percentChanging` and `periodMs` (10% and 6%, chosen from the point index and the scan number). The same knobs are `POST /control` on the long-running process, and `--percent` / `--period-ms` on `bench/sim/server.py`.

## How to run

From `bench/`:

```bash
python3 -m bench prf012 --ci
python3 -m bench prf012
python3 -m bench prf012 --points 150000
```

`--ci` uses `ciPoints`. With no flags the check uses `fullPoints`. `--points` and `OIP_PRF012_POINTS` override both. The Simulator stack smoke job runs the CI size (`./gradlew :test-infra:support:smokeTest`). The Load bench job runs the full size in `bench/tests/test_prf012.py`.

Leave the process up, still off loopback, with the full address space:

```bash
python3 sim/server.py --points 150000 --require-networked
BENCH_POINTS=150000 python3 -m bench sim-up
```

`sim-up` publishes host ports 25020–25023, 24840, 21883, and 28080. Point a gateway at the docker bridge address (`python3 -m bench ramp --sim-host auto`), not at 127.0.0.1. The in-process `--self-test` still binds 127.0.0.1; that check is the codec, not PRF-012.

the reference platform comparison runs follow ADR 0001 (documented use only) and finish inside the 2 h trial. a manual license reset stays manual.

PRF-009 and PRF-010 are measured by `DriverFloorTest` (`test-infra/driver-floors`), on the bench point image and a Docker bridge. That run is the driver floor, separate from the 150,000-point PRF-012 check above.

The container the Simulator stack smoke job starts is `test-infra/perf-sim` (image `oip-perf-sim`). It is not this directory and not `test-infra/compose.yaml`. `./gradlew :test-infra:perf-sim:perfSimTest` builds that image, runs the 100,000-at-10% and 150,000-at-6% scans, and reads `ciPoints` with the Modbus, OPC UA, and MQTT drivers on a Docker bridge. The full-size procedure is `test-infra/perf-sim/README.md`.

## Perf regression gate (PRF-013)

`scripts/perf_regression.py` fails when a tracked result is more than 15% worse than the accepted baseline for the same scenario, hardware size and metric. Exactly 15% worse passes. Throughput and capacity falling is worse; latency, CPU and memory rising is worse. Sizes are never compared with each other. A result with no baseline is printed as `no baseline`: it neither fails nor counts as a pass of the gate.

```bash
python3 scripts/perf_regression.py --result test-infra/load-soak/build/reports/load-soak/*.json
python3 scripts/perf_regression.py --result results.json --baseline accepted.json
```

A result file is `{"results": [{"scenario", "hardware", "metric", "value", "lowerIsBetter"}]}`, or a load-soak report (read by its `scenario` and `size`). The Load bench job runs the gate on the load-soak reports.

Accepted baselines are the `result.baseline` blocks in `test-infra/perf/perf-requirements.json` (PRF-004 to PRF-007, PRF-009 to PRF-011), plus any `--baseline` file of the same shape. To accept a baseline, commit a measured value from a real run of that scenario and size, with its direction. Baselines move only when a test is shown to be wrong, which is logged as an ADR.

Currently the Load bench job has no accepted baseline for PRF-003 and PRF-008 at size `ci`, so the gate lists them as `no baseline`. The driver floors (PRF-009, PRF-010) and the tag scan (PRF-007) compare to their baselines inside their own tests.

