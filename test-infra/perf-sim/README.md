# High-point simulator (PRF-012)

One container image. It serves the same address space over Modbus TCP, OPC UA, and MQTT (one plain topic per point, and Sparkplug B). The functional compose file `test-infra/compose.yaml` is a single waveform and is not this simulator.

`test-infra/perf/prf-012.json` holds `fullPoints` (150,000) and `ciPoints`. Drivers in `PerfSimHarnessTest` refuse `127.0.0.1` and `localhost` before they open a socket. The container is attached to a Docker bridge and is not published on the host's loopback.

## Configure

| Knob | Environment | Flag | Default |
|---|---|---|---|
| Point count | `POINTS` | `--points` | 150000 |
| Percent changing each period | `PERCENT_CHANGING` | `--percent` | 10 |
| Period | `PERIOD_MS` | `--period-ms` | 1000 |
| Value pattern | `PATTERN` | `--pattern` | `scan` |
| Registers per Modbus unit | `REGISTERS_PER_UNIT` | `--registers-per-unit` | 10000 |
| Sparkplug metrics per device | `METRICS_PER_DEVICE` | `--metrics-per-device` | 500 |

Patterns: `scan` (index base, plus the tick on the changing subset), `constant`, `ramp`, `step`, `sine`, `random`. The changing subset is `(index % 100) < percent`, the same rule as ADR 0010. `POST /control` accepts `points`, `percentChanging`, `periodMs`, `pattern`, `frozen`, `nowMs`, and `epochMs`.

Ports inside the container: Modbus TCP `5020`, OPC UA `4840` (`opc.tcp://<bridge-ip>:4840/oip/perf`), MQTT `1883`, HTTP `8080`.

Modbus holding registers are one-based (`1.HR1` is point 0 on unit 1). OPC UA node ids are `ns=1;i=1000000+index`. Plain MQTT is `number:oip/perf/p0000000`. Sparkplug B is group `OIPPERF`, edge `PerfSim`, device `D{index/500}`, metric `P{index}` (`spb:OIPPERF/PerfSim/D0/P0000000`).

`GET /health` reports `cpuPercent` (process CPU time over wall time, 100 = one core), `lastScanMs`, `maxScanMs`, `behindScans`, and `scans`. Scans are published on the wall-clock grid `epoch + n * period`, the same grid Modbus and OPC UA use for a read, so one tick is the value on all four protocols after the publish. A scan that takes longer than the period, or that starts more than one period after the previous one, increments `behindScans`.

## CI

The Simulator stack smoke job runs:

```bash
./gradlew :test-infra:perf-sim:perfSimTest
```

That builds `oip-perf-sim:ci`, refuses loopback peers, runs the pace check below, and starts `ciPoints` on a bridge. The Modbus, OPC UA, and MQTT drivers connect to the bridge address and count every point. A changing point and a stable point are sampled across a couple of periods.

## Full-size run

Build and prove the two change rates used by PRF-004 and PRF-005 (10% of 100,000 at 1 s, and 6% of 150,000 at 1 s). Each measured scan has to finish in under 1 s. The pace drain is inside the container; it is not a driver target.

```bash
docker build -t oip-perf-sim:ci -f test-infra/perf-sim/Dockerfile test-infra/perf-sim
docker run --rm oip-perf-sim:ci python -u /app/server.py --self-test
docker run --rm oip-perf-sim:ci python -u /app/server.py --pace
```

Leave 150,000 points up on a bridge, at the 6% rate, and read CPU from the bridge address (not from 127.0.0.1):

```bash
docker network create oip-perf
docker run -d --name oip-perf-sim --network oip-perf \
  -e POINTS=150000 -e PERCENT_CHANGING=6 -e PERIOD_MS=1000 -e PATTERN=scan \
  oip-perf-sim:ci
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' oip-perf-sim
curl -s http://<bridge-ip>:8080/health
docker rm -f oip-perf-sim
docker network rm oip-perf
```

Point a gateway at that bridge IP: Modbus port 5020, OPC UA `opc.tcp://<bridge-ip>:4840/oip/perf`, MQTT port 1883 with Sparkplug group `OIPPERF`.

### Measured on this image

`docker run --rm oip-perf-sim:ci python -u /app/server.py --pace` runs three scans back to back after one warmup, with one subscriber draining the socket. There is no idle between those scans, so `cpuPercent` there is the process during the burst (about one core), not the 1 s serving loop.

| Scenario | Points | Percent | maxScanMs | cpuPercent | behind |
|---|---|---|---|---|---|
| PRF-004 shape | 100000 | 10 | 43.804 | 94.2387 | 0 |
| PRF-005 shape | 150000 | 6 | 43.718 | 99.3763 | 0 |

Those two rows are one local run of the command above (the same one CI runs). A second run was 46.8 ms and 42.9 ms. `behind` stayed 0.

Serving on a bridge, `GET /health` about 12 s after `READY` (CPU includes birth, then the 1 s loop). `behindScans` stayed 0.

| POINTS | PERCENT_CHANGING | cpuPercent | lastScanMs | maxScanMs | scans |
|---|---|---|---|---|---|
| 150000 | 6 | 6.7354 | 32.502 | 41.468 | 13 |
| 100000 | 10 | 6.0397 | 32.102 | 37.309 | 13 |

The pace check is what CI runs. The 150,000-point process is the address space a reference-size run points at. It does not by itself satisfy the gateway floors in PRF-004, PRF-005, or PRF-006.
