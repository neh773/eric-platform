# High-point load bench

Tools for driving a gateway to the edge of its capacity and writing down where it stops. The simulator is a black-box data source. The harness talks to a gateway only through its documented HTTP API and through `docker stats`. It does not read or write gateway files.

This is separate from the small functional simulators in `test-infra/`. Those stay on ports 15020, 14840 and 11883. This bench uses 25020–25023, 24840, 21883 and 28080 so the two stacks can be told apart, and only one of them should be up when RAM is tight.

The simulator image is `python:3.12-slim-bookworm` at the digest already listed in `THIRD_PARTY.md`. The harness is the Python standard library, plus optional [asyncua](https://github.com/FreeOpcUa/opcua-asyncio) (LGPL-3.0-or-later) when a target is read through OPC UA. asyncua is not imported by the unit tests or the simulator image, and it is not linked into the gateway.

```bash
cd bench
python3 -m pip install -r requirements-reference.txt
```

## What a point is

Every protocol serves the same integer for the same index and the same millisecond:

```
tick     = 0 if now < epoch else (now - epoch) // period_ms
changing = (index % 100) < percent_changing
base     = index % 1000
raw      = base if not changing else (base + tick) % 30000
```

| Protocol | Address |
|---|---|
| Modbus TCP | unit id `1 + index // registers_per_unit` (max 247), PDU offset `index % registers_per_unit`. The public one-based holding-register address is that offset plus one (`3.HR51`). Every published Modbus port serves the full unit map. |
| OPC UA | `ns=1;i=1000000+index` in namespace `urn:oip:bench:points`. Anonymous, `SecurityPolicy#None`. Endpoint `opc.tcp://<host>:<port>/oip/bench`. |
| Sparkplug B | group `OIPBENCH`, edge `Bench`, device `D{index // metrics_per_device}`, metric `P{index:07d}`. The in-process MQTT broker keeps the birth retained. |

Tag names are `p` plus seven digits (`p0000100`).

## Simulator

```bash
cd bench
python3 -m bench self-test
python3 -m bench sim-up
python3 -m bench sim-up --points 100000
python3 -m bench sim-down
```

`sim-up` builds `oip-bench-points:local` and starts one container, capped at 1 vCPU and 384 MB. Compose names the container `oip-bench-points-1` (project `oip-bench`, service `points`). The ramp uses that name, or the name `docker compose ps` prints when the stack is already up. Defaults are 1000 points, 10% changing, 1000 ms period. Override with `BENCH_POINTS`, `BENCH_PERCENT`, `BENCH_PERIOD_MS`.

### PRF-012

`test-infra/perf/prf-012.json` is the address-space config. `fullPoints` is 150,000. `ciPoints` is what the Simulator stack smoke job serves. The check binds a non-loopback interface. Driver helpers (`require_driver_target`, `HighPointDriver`) refuse 127.0.0.1 before they open a socket.

```bash
cd bench
python3 -m bench prf012 --ci
python3 -m bench prf012
python3 sim/server.py --points 150000 --require-networked
```

`python3 -m bench prf012` reads the first point, the middle point, and the last point over Modbus and OPC UA, and counts every Sparkplug birth metric. `--self-test` stays on 127.0.0.1 and is not this check.

HTTP on port 28080:

| Route | Use |
|---|---|
| `GET /health` | liveness |
| `GET /expected?index=N` | integer the formula produces right now |
| `GET /stats` | connection counts |
| `POST /control` | `points`, `percentChanging`, `periodMs`, `epochMs`, `frozen`, `nowMs`, `registersPerUnit`, `metricsPerDevice` |

The process does not allocate an object per point. Memory grows when an OPC UA client creates monitored items, and when Sparkplug births are retained. A 200,000-point process with those births retained used about 25 MB of the 384 MB cap and served the last holding register with the formula value. If the report says the simulator CPU went over 80% of its one core, a gateway cutoff in that step may be the simulator, not the gateway.

## Ramp

One gateway at a time. CPU is docker's CPU percent (100 means one busy core) divided by the container's CPU quota. Memory percent is container RSS over the cgroup limit when docker set one, otherwise over the host. the reference platform heap and clock drift come from `GET /data/api/v1/systemPerformance/currentGauges` (`heapMemory`, `maxMemory`) and `GET /data/api/v1/systemPerformance/driftGauge` (`clockDrift`). Comparable drift is `(delivered ticks − expected ticks) / expected` over the sample window; negative means the values are behind the wall clock. Values/s counts source ticks advanced, not the number of reads that saw a new value: docker stats and the sample sleep are slower than the source period, so one read often covers several ticks.

The read is timestamped after the docker stats sample. Stats takes about a second, and stamping before it makes latency look early.

The ramp stops on the first of: an error, quality errors above 1% of reads, CPU of the quota above 50%, memory above 50%, drift outside ±10%. It also stops before a step that would cross the wall-clock cap. The cap is `maxWallSeconds`. On an the reference platform target the harness GETs `/data/api/v1/trial` and uses `trialSecondsLeft` when `licenseMode` is `Trial` (an expired trial is zero). The effective cap is the tighter of the two. The route is the published Trial Information operation. The harness never POSTs it, so it does not reset a trial. If the route does not answer, pass `--trial-seconds-left`. That flag is also the cap for a gateway that has no trial route.

```bash
cd bench
python3 -m bench sim-up --points 120000

# the reference platform 8.3 on port 8088. Restart the gateway yourself so the 2-hour trial is fresh.
python3 -m bench ramp \
  --profile profiles/reference-trial.json \
  --target reference \
  --gateway http://127.0.0.1:8088 \
  --token-file ../secrets/reference-api-token.txt \
  --container <reference-container-name> \
  --start-sim

# Our gateway on port 8090 (host). Inside the image the HTTP port is 8088.
python3 -m bench ramp \
  --profile profiles/dev.json \
  --target oip \
  --gateway http://127.0.0.1:8090 \
  --container oip-gateway \
  --start-sim
```

`--sim-host auto` asks docker for the bridge address the gateway container uses to reach published host ports (often `172.17.0.1`). Pass an address when the gateway is not in docker.

Head-to-head runs need the same cgroup on both containers. Pass the same `--cpus` and `--memory` for the reference platform ramp and the oip ramp (`docker update` on the named `--container`). the reference platform's JVM heap does not follow the cgroup; the official image takes it as the `-m` runtime argument, in megabytes (`--reference-heap 2048` or `--reference-heap 2g`). Applying the heap recreates that container with the same mounts, ports, environment, and command, then waits until the gateway answers. A failed recreate renames the previous container back and starts it. On `ramp`, `--reference-heap` is refused for the oip target: that JVM follows the cgroup memory limit. `scale` accepts `--cpus` and `--memory` together as a single tier, and `--oip-heap` or `--reference-heap` to pin the heap instead of taking half the tier memory.

```bash
python3 -m bench ramp \
  --profile profiles/reference-trial.json \
  --target reference \
  --container reference \
  --cpus 2 --memory 4g --reference-heap 2g
```

Reports land in `bench/out/<profile>/<target>/report.json` and `report.md`.

### Profiles

| File | What it is |
|---|---|
| `profiles/ci.json` | 20 then 40 points. Used by unit tests. No docker. |
| `profiles/dev.json` | 1k, 5k, 10k Modbus points. Short steps. Fits a box with about 3 GB free. |
| `profiles/reference-trial.json` | Modbus up to 120k points. Planned sample time is about 7 minutes. `maxWallSeconds` is 6600 so the run stays inside a 2-hour trial, including setup. |
| `profiles/reference-trial-opcua.json` | OPC UA up to 50k points, same trial cap. |
| `profiles/scale-dev.json` | 2 vCPU / 768m and 4 vCPU / 1024m. 100% changing, 1 s, points double until a cutoff. |
| `profiles/scale-full.json` | ADR 0010 sizes: 2/4/8/16 vCPU at 2 GB RAM per vCPU. Throughput ramp, 100% changing. |
| `profiles/scale-capacity.json` | Same sizes. Tag capacity: 1 s, 10% changing. E(n) on the last good point count. |
| `profiles/h2h.json` | PRF-001 ramp for S2, S4, S8, S16, and EDGE. Sampling stays inside 110 min. |
| `profiles/oip-trial.json` | Same steps as `reference-trial`, read through our OPC UA server. |
| `profiles/oip-edge.json` | 120k–200k points, 100% changing, OPC UA reads. |
| `profiles/oip-edge2.json` | 250k–1,000k points, 100% changing, OPC UA reads. |
| `profiles/oip-edge-rest.json` | The edge steps, read with `POST /api/v1/tags/read`. |
| `profiles/oip-edge-prf006.json` | PRF-006 on EDGE: 88,500 points, then the edge steps, local history on. Cutoffs copied from `oip-edge`. |
| `profiles/core-curves.json` | Core curves: 10k to 320k points, 100% changing, OPC UA reads, local history off. Run at 1, 2 and 4 CPUs with 2 GB per CPU by `core_curves.sh`. Cutoffs copied from `oip-edge`. |
| `profiles/core-curves-ram.json` | One 100k step with the `core-curves` settings, for the lowest clean memory limit at 4 CPUs. |

## Head-to-head (PRF-001)

the reference platform 8.3.10 and our gateway, same size, same load. The sizes are S2 (2 vCPU / 4 GB), S4 (4 / 8 GB), S8 (8 / 16 GB), S16 (16 / 32 GB), and EDGE (4 vCPU / 4 GB). Cutoffs stay CPU above 50%, memory above 50%, clock drift outside ±10%, and any error or data loss. Samples in the steady window are every 10 s. The window fails when its mean crosses a cutoff or any 60 s mean crosses one. Exactly 50% and exactly ±10% do not cross.

Each the reference platform size is its own 2 h trial. The harness pauses and asks for a manual license reset in the gateway UI. It never POSTs `/data/api/v1/trial`. A run that is not a terminal needs `--reset-confirmed` after that manual reset. Sizes are not stacked into one trial. The procedure is 20 min setup, 25 min ramp, 45 min steady, 20 min collect (110 min). A 60 min soak does not fit in the same trial as the ramp.

The report for each size is `out/head-to-head/<size>/report.json`. It records the reference platform's last good load and our result at that load.

```bash
cd bench
# CI. Scripted gateways, no docker, no pause on stdin.
python3 -m bench head-to-head --dry-run

python3 -m bench head-to-head \
  --size S2 \
  --container reference \
  --gateway http://127.0.0.1:8088 \
  --token-file ../secrets/reference-api-token.txt \
  --oip-gateway http://127.0.0.1:8090 \
  --oip-password-file ../secrets/oip-admin.txt \
  --image oip-gateway:local \
  --start-sim \
  --reset-confirmed
```

## Scaling (PRF-002)

Each tier ramps points at 100% changing and a 1 s period until CPU, memory, drift, or quality crosses a cutoff. The profile has to already be that ramp. `is_throughput_ramp` requires percentChanging 100, a single 1000 ms period, at least 3 strictly increasing point counts, and a last point at least 4x the first. `bench scale` exits with an error naming that rule when the profile fails it, and does not replace the profile with the default 5,000-to-160,000 series. T is the value changes per second at the last step that stayed inside the cutoffs. Efficiency against the baseline tier (2 vCPU when that size is in the run) is

```
E(n) = (T(n) / T(baseline)) / (n / baseline)
```

ADR 0010 requires E(n) >= 0.70 for throughput and, separately, for tag capacity. Throughput T is value changes per second at 100% changing. Tag-capacity T is the last good point count on a 1 s scan with 10% changing (`profiles/scale-capacity.json`). EDGE is not a point on this series. Exactly 0.70 passes. Below 0.70 the command exits 1. A tier that finishes the series still under the cutoffs, or whose simulator exceeds 80% of a core first, has no efficiency and the command exits 2.

```bash
python3 -m bench scale \
  --profile profiles/scale-full.json \
  --capacity-profile profiles/scale-capacity.json \
  --metric both \
  --target oip \
  --dry-run
```

`--dry-run` writes the table from a scripted series so CI does not need docker. The numbers are not a measurement.

The JVM heap follows the tier memory (half the cgroup limit). `--oip-heap` or `--reference-heap` pins one heap for every tier instead. Our gateway publishes `8090:8088` and `4840:4840`, sets `OIP_OPCUA_BIND=0.0.0.0`, and starts with a new `/data` (`docker rm -v`) so the next tier does not see the previous tags.

The simulator is one process per core. A single container with a multi-CPU quota still saturates one core under the GIL, and earlier runs stopped on the simulator before the gateway. The fleet is sized at twice the core count that would sit on the 80,000-point budget (`headroom` 2), so the simulator stays under the 80% flag. `--start-sim` builds the image and runs that fleet. Our gateway shards Modbus units across the ports. the reference platform keeps one device per host IP; a local fanout on the Modbus port spreads connections across the same processes. The report flags a tier when any simulator exceeds 80% of its core.

When one machine cannot hold the fleet, `--sim-hosts` spreads it. `sim-hosts.example.json` puts the overflow on a second host via `docker -H ssh://...`. `address` is where the harness opens the control port. `gatewayAddress` is what the gateway dials. `pointsPerVcpu` above 80,000 is refused, because that would shrink the fleet. The second host needs the simulator image; the harness copies it with `docker save` / `docker load` before starting those containers.

```bash
cd bench
python3 -m bench scale \
  --profile profiles/scale-dev.json \
  --target oip \
  --image oip-gateway:local \
  --gateway http://127.0.0.1:8090 \
  --oip-password-file ../secrets/oip-admin.txt \
  --start-sim

# One the reference platform tier, or the profile series. --cpus and --memory together replace the series with that tier.
python3 -m bench scale \
  --profile profiles/scale-full.json \
  --target reference \
  --container reference \
  --cpus 2 --memory 4g --reference-heap 2g \
  --start-sim
```

`--sizes 2:4g,4:8g` overrides the profile. This mode does not accept an the reference platform license. the reference platform tiers reuse the named container: cgroup limits and the image `-m` heap are applied the same way as a ramp, which recreates the container and keeps its mounts. a manual license reset is not called. A tier that would start after the trial window is skipped.

## the reference platform, documented API only

Auth header is `X-Reference-API-Token` from Platform → Security → API Keys. That is not the admin password in `secrets/reference-admin.txt`. If the key requires a secure connection, pass an `https://` gateway URL.

| Action | Request |
|---|---|
| Modbus device | `POST /data/api/v1/resources/com.reference.opcua/device` with a one-element JSON array. Profile type `ModbusTcp`. One device per host IP. The unit id is in the item path (`[benchmodbus]3.HR1`), which is the documented addressing form. The signature is `changes[].newSignature`. |
| OPC UA connection | `POST /data/api/v1/resources/reference/opc-connection`, same array body and `newSignature`. |
| Tags | `POST /data/api/v1/tags/import?provider=default&type=json&collisionPolicy=Overwrite` in batches. The body is the JSON document with content type `application/octet-stream`. A non-zero `failureCount` fails the step. Each tag is an atomic OPC tag, data type `Int2`, deadband off, sample mode TagGroup. |
| Reads | the reference platform's OPC UA server, anonymous, `SecurityPolicy#None`. Node id `ns=2;s=[provider]pNNNNNNN`. Endpoint `reference.opcuaReadEndpoint`, default `opc.tcp://127.0.0.1:62541`. 8.3.10 has no REST tag-read route. |
| Drift / heap | `GET /data/api/v1/systemPerformance/driftGauge` (`clockDrift`) and `GET /data/api/v1/systemPerformance/currentGauges` (`heapMemory`, `maxMemory`) |
| Trial | `GET /data/api/v1/trial` (`trialSecondsLeft` while `licenseMode` is `Trial`). Not a reset. |

On 8.3.10 a resource create that answers 409, or `success: false`, is deleted through the same API and created again. Cleanup deletes the device and the OPC connection. It refreshes the signature with `GET .../resources/find/...` first.

Tag reads need the reference platform's OPC UA server reachable from the harness. On Connections → OPC UA → Server Settings, for the run:

| Setting | Value for the run | Product default |
|---|---|---|
| Bind Addresses | `0.0.0.0` (or the address the harness can open) | `localhost` |
| Bind Port | `62541`, published on the container | `62541` |
| Security Policies | include `None` | `Basic256Sha256` |
| Anonymous Access Allowed | true | false |
| Expose Tag Providers | true | false |

`localhost` as the bind address is not reachable from another container or from the host's published port. Anonymous access and `SecurityPolicy#None` are wider than the product defaults. Restore the previous settings after the run. The harness does not change these settings.

8.3.10 has no tag-delete route. Imported tags stay in the provider. Their names are `p` plus seven digits. `--delete-tags` is opt-in: it deletes the tag provider resource and POSTs the same provider document back, which removes every tag in that provider, not only the bench tags. The `system` provider is refused. Leave the flag off unless that provider holds nothing you need. `collisionPolicy=Overwrite` overwrites a tag of the same name. It is not a wipe of the provider.

If a create is rejected, the report contains the response body. The schema for that build is `GET /data/api/v1/resources/type/com.reference.opcua/device` and `GET /data/api/v1/resources/type/reference/opc-connection`.

## Our gateway

Login is `POST /api/v1/auth/login`. The harness keeps the `oip_session` cookie and sends `X-CSRF-Token` on writes. A 401 logs in again. The user is `--oip-user` (default `admin` when a password file is set). The password is read from `--oip-password-file`, either one line or an env file containing `OIP_ADMIN_PASSWORD`. It is not a command-line argument and it is not written into the report. Scale passes it to the container with `docker run --env-file`.

Tag resources are created with `POST /api/v1/config/batch` (`scope` `gateway`), `tagBatch` resources at a time. A 409 on a rerun means a stored version is no longer 0. The harness reads the current versions and submits the batch again as an update. Scale also removes the container with `docker rm -v`, so the next tier gets an empty data volume. Device and connection resources are `PUT` through the config API. A 409 there is the same version update.

Reads are `POST /api/v1/tags/read`. Heap is `GET /api/v1/diagnostics/memory`.

`oip.history` in a profile (`true`, or an object of `HistorySettings` spec fields such as `storageProvider` and `sampleMode`) turns local history on: one `historian/HistorySettings` resource per bench tag, written through the same config batch. Each step then records `history`, the rows the collector wrote between the first and last sample of the window, from `GET /api/v1/diagnostics/history/providers` (`written`, `dropped`, `pending`). With history on, any dropped history row (in the window or before it) stops the ramp as `data-loss`, and so does a step whose history counters could not be read. Off when absent.

`--read-via-opcua`, or `oip.readViaOpcUa`, reads our gateway through its OPC UA server. Anonymous access is off. The session uses the same username and password as the HTTP login. The endpoint is `--opcua-read-endpoint` or `oip.opcuaReadEndpoint`. The node id template is `oip.opcuaNodeId`, default `ns=2;s=Tags/{provider}/{path}`, with placeholders `{provider}`, `{path}` (`bench/pNNNNNNN`), `{name}`, and `{index}`. A missing endpoint is an error, not a silent fallback.

Modbus `Device` and OPC UA client `Connection` resources are written through the config API. A 422 stops the ramp and is recorded. The harness does not invent a success and does not configure drivers by writing files. Set `requireDevices` to false only when the connections already exist; the tags are still written through the config API.

Sparkplug is published by the simulator. Neither target gets a Sparkplug subscription from this harness, so that protocol is not a quality failure. The report says the births are available.

## Hardware plans

P2-04, P2-05, P3-04, P4-01, P4-14, and P4-15 each have one command. It writes an ADR 0010 report for our gateway and for the comparison VM. `--dry-run` uses the local simulator and does not open the comparison VM. `--measure` refuses to dial. Configs are `profiles/hardware/`. Captured comparison output is not stored in this repository.

```bash
python3 -m bench hardware P2-04 --dry-run
python3 -m bench hardware --all --dry-run
```

Reports land in `out/hardware/<PLAN>/report.json` and `report.md`. A dry-run is not a gate result and is not copied over the CI-size reports.

## Trial window runner

One plan, one command, one trial window. The plan is data (`plans/window-1.json`): setup, the PRF-001 ramp at S2 limits, the parity checks, collect, teardown and slack, each with a minute budget, an output file and what it closes. Captured window output is not stored in this repository.

```bash
cd bench
python3 -m bench window --plan plans/window-1.json --list              # the runbook table
python3 -m bench window --plan plans/window-1.json --target ours       # dry run, our gateway only
python3 -m bench window --plan plans/window-1.json --target reference  # the real window, on a terminal
python3 -m bench window --plan plans/window-1.json --target reference --phase after   # PRF-001 our half
```

`--target ours` never starts, contacts or pulls the reference product. It runs our gateway from `gateway/server/build/install/server` as a host process (two cores, `-Xmx2048m`, ports 8090, 24860 and 28060) and the simulators as host processes, so it needs no image build. `--target reference` starts the container only through `reference/reference_env.py up`, reads the trial route with GET only, and asks the person at the terminal each UI question. Before every step the runner checks the window clock. An optional step that no longer fits is skipped, and a required step that would overrun sends the run to collect and teardown. `--from <id>` resumes on the same clock. Parity probes record status codes and field names, never values. Every output is labelled container-size, not reference-size.

## Tests

```bash
cd bench
python3 -m unittest discover -s tests -v
python3 -m bench self-test
```

CI runs the unit tests and `docker build` of the simulator followed by `--self-test` inside the image.
