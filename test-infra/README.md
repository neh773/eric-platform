# Test simulators

Docker Compose stack used by driver, historian and alarm tests. Nothing here is a gateway module and nothing is wired to real equipment.

```bash
docker compose -f test-infra/compose.yaml up -d --build --wait
./gradlew :test-infra:support:smokeTest
# or, against the stack you just started:
OIP_SIM_STACK=external ./gradlew :test-infra:support:smokeTest
docker compose -f test-infra/compose.yaml down -v
```

`./gradlew :test-infra:support:smokeTest` with `OIP_SIM_STACK` unset starts the same file through Testcontainers (`SimulatorStack.start()`). Other modules depend on project `:test-infra:support` and call `SimulatorStack.fromEnvironment()`.

## Port map

| Service | Direct host port | Through Toxiproxy | What it is |
|---|---|---|---|
| Modbus TCP | 15020 | **5020** | Holding registers, unit id 1. Container listens on 5020 (not privileged 502). |
| Modbus control | 15021 | — | `GET /health`, `GET /value`, `POST /control` |
| OPC UA | 14840 | **4840** | `opc.tcp://localhost:4840/oip/sim` (anonymous, SecurityPolicy None) |
| OPC UA control | 14841 | — | Same HTTP control API |
| MQTT (Mosquitto) | 11883 | **1883** | Anonymous test broker |
| Sparkplug control | 15030 | — | Same HTTP control API. Edge node publishes to Mosquitto inside the compose network. |
| PostgreSQL + TimescaleDB | **25432** (`OIP_SIM_POSTGRES_PORT`) | 15432 | Database `oip`, user `oip`, password `oip` (test only). Container port stays 5432. |
| Mailpit SMTP | **1025** | 11025 | Catches mail; no relay |
| Mailpit HTTP | **8025** | — | UI and `/api/v1`, liveness `/livez` |
| Toxiproxy API | **8474** | — | `POST /proxies/{name}` with `enabled: false` cuts that path |

Client tests should use the **bold** proxy ports so a comms cut is one API call. Direct ports stay up during a cut, which is how a test tells "the simulator died" from "the network path died".

```bash
curl -s -X POST http://localhost:8474/proxies/modbus -H 'content-type: application/json' \
  -d '{"name":"modbus","listen":"0.0.0.0:5020","upstream":"modbus:5020","enabled":false}'
curl -s -X POST http://localhost:8474/proxies/modbus -H 'content-type: application/json' \
  -d '{"name":"modbus","listen":"0.0.0.0:5020","upstream":"modbus:5020","enabled":true}'
```

`SimulatorStack.cut(SimService.MODBUS)` / `restore` do this. `ToxiproxyClient.addTimeout` adds a timeout toxic when the test needs a hang instead of a reset.

## Scripted values

All three data simulators share one waveform. `phase = floorMod(nowMs - epochMs, periodMs) / periodMs` in `[0, 1)`.

| mode | value |
|---|---|
| `constant` | `offset` |
| `ramp` | `offset + amplitude * phase` |
| `sine` | `offset + amplitude * sin(2π * phase)` |
| `step` | `offset` while `phase < 0.5`, else `offset + amplitude` |

`POST /control` accepts any subset of `mode`, `amplitude`, `offset`, `periodMs` (1..65535), `epochMs`, `frozen`, `nowMs`. With `frozen: true`, `nowMs` is the clock; otherwise the wall clock is used. Defaults: sine, amplitude 100, offset 0, period 10000 ms, epoch 0, live clock.

```bash
curl -s -X POST http://localhost:15021/control -H 'content-type: application/json' \
  -d '{"mode":"sine","amplitude":100,"offset":0,"periodMs":1000,"epochMs":0,"frozen":true,"nowMs":250}'
```

That is phase 0.25, so `value` is 100 (within 1e-6; `sin` is libm).

Modbus holding registers (0-based PDU address, function 03/06/16). Engineering values are `int16(round(value * 100))` using Java `Math.round` (`floor(x + 0.5)`), on the wire as a uint16 bit pattern.

| Addr | Name | Access |
|---|---|---|
| 0 | mode (0 constant, 1 ramp, 2 sine, 3 step) | RW |
| 1 | amplitude × 100 | RW |
| 2 | offset × 100 | RW |
| 3 | period ms | RW |
| 4 | live value × 100 | RO |
| 5 | frozen (0/1) | RW |
| 6..7 | frozen nowMs, uint32 big-endian | RW |
| 8..9 | epochMs low 32 bits | RW |

OPC UA nodes in the server's own namespace, string ids: `Sim.Value` (R), `Sim.Mode`, `Sim.Amplitude`, `Sim.Offset`, `Sim.PeriodMs`, `Sim.EpochMs`, `Sim.Frozen`, `Sim.NowMs`. The smoke test discovers the namespace index from `Server_NamespaceArray` (`ns=0;i=2255`).

Sparkplug B (specification 3.0) group `OIP`, edge `SimEdge`, device `SimDevice`:

| Topic | Contents |
|---|---|
| `spBv1.0/OIP/NBIRTH/SimEdge` | `bdSeq` (UInt64), `Node Control/Rebirth` (Boolean). Retained. |
| `spBv1.0/OIP/NDEATH/SimEdge` | MQTT will, QoS 1, same `bdSeq`. |
| `spBv1.0/OIP/DBIRTH/SimEdge/SimDevice` | `Value` (Double), `Mode` (String). Retained. |
| `spBv1.0/OIP/DDATA/SimEdge/SimDevice` | `Value`, `Mode`, every 200 ms, QoS 0. |
| `spBv1.0/OIP/NDATA/SimEdge` | `Mode`, about every 1 s. |
| `spBv1.0/OIP/NCMD/SimEdge` | `Node Control/Rebirth` = true triggers a new birth. |

A control change also rebirths. The payload codec in `org.oip.testinfra.Sparkplug` covers the metric types this node publishes (int, long, float, double, boolean, string), not DataSet/Template/properties.

Timescale starts with extension `timescaledb` and hypertable `sim_samples(ts, signal, value)`. The smoke test also runs a gateway named query against this same Postgres container (parameter binding, including a quote that must stay a bound value). That check does not start a second database.

## Images and licenses

Images are pinned by digest in `compose.yaml` and the Dockerfiles. Versions and licenses, including every `node-opcua` transitive package, are in `THIRD_PARTY.md`.

| Image | Digest | License |
|---|---|---|
| `python:3.12-slim-bookworm` | `sha256:6a0e06f5ba0b373d051aabd3fffb9b873f27f9a2a3a14bcbe37dee6f0e3bbc70` | PSF-2.0 (CPython) |
| `node:22-bookworm-slim` | `sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d` | MIT (Node.js) |
| `eclipse-mosquitto:2.0.22` | `sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c` | EPL-2.0 OR BSD-3-Clause |
| `timescale/timescaledb:2.31.0-pg16-oss` | `sha256:1bfcf994089b9c97b4ddfa07f2ff9eec430709715e419d9a6957d1a4b4614cd1` | Apache-2.0 (TimescaleDB) and the PostgreSQL License |
| `axllent/mailpit:v1.32.0` | `sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498` | MIT |
| `ghcr.io/shopify/toxiproxy:2.13.0` | `sha256:5d3dae09bc185dcc5ff6cf43abf6062a4a720aff0c4306a471e773acdcd107aa` | MIT |

`node-opcua@2.187.16` (MIT) is the OPC UA server library. open62541 was not used (MPL-2.0). Eclipse Milo 1.1.4 (EPL-2.0) is the smoke-test client only. `paho-mqtt==2.1.0` (EPL-2.0 OR BSD-3-Clause) is the Sparkplug publisher and has no further Python dependencies. The non-`-oss` Timescale tags add the Timescale License; this stack uses the Apache build only.

Anonymous MQTT and OPC UA `SecurityPolicy.None` are properties of these test doubles, not a gateway security profile.

## High-point load bench

Capacity ramps against the reference platform or our gateway live in `bench/`, on their own host ports (Modbus 25020–25023, OPC UA 24840, MQTT 21883, HTTP 28080). See `bench/README.md`. This compose file stays the small functional simulator.

The PRF-012 address space is not this compose file. `test-infra/perf/prf-012.json` holds `fullPoints` (150,000) and `ciPoints`. The simulator image is `test-infra/perf-sim`: one container, Modbus TCP, OPC UA, and MQTT (plain topics and Sparkplug B), on a Docker bridge. `./gradlew :test-infra:perf-sim:perfSimTest` is the CI-sized driver check. The full-size run is documented in `test-infra/perf-sim/README.md`.

From `bench/`, `python3 -m bench prf012` still serves the full count on a non-loopback interface and refuses a 127.0.0.1 driver target. `python3 -m bench prf012 --ci` is the size `./gradlew :test-infra:support:smokeTest` runs. See `test-infra/perf/README.md`.
