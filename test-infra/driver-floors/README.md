# Driver floors (PRF-009, PRF-010)

The Load bench CI job runs these against the bench point image. The functional compose stack is not this measurement. PRF-012 (150,000 points over Modbus, OPC UA, and MQTT) is the separate image in `test-infra/perf-sim`.

```
docker build -t oip-bench-points:ci -f bench/sim/Dockerfile bench
./gradlew :test-infra:driver-floors:driverFloorTest
```

The simulator container joins a Docker bridge network and does not publish a host port. The drivers dial that container's bridge address. `127.0.0.1` and any loopback address do not count. The test checks the network driver is `bridge` and that both ends of a probe socket are non-loopback.

## CI window

Defaults, what the Load bench job runs:

- **PRF-009.** 10,000 holding registers on one Modbus TCP connection, 20 ms scan, `concurrentRequests` 8, 4 s steady window. Every sample in the window is good quality. Reads coalesce to 125-register PDUs. Peak in-flight requests reach `concurrentRequests`.
- **PRF-010.** 50,000 OPC UA monitored items on one gateway connection, one sampling interval (one subscription), good quality, held 2 s. `OpcUaConnection.monitoredItemCount` is the monitored-item diagnostic. Create calls are batched (the test requires an average batch of at least 100).

Accepted baselines are `result.baseline` on PRF-009 and PRF-010 in `test-infra/perf/perf-requirements.json`. A later same-hardware result more than 15% worse fails PRF-013. A result 15% worse does not. The floors themselves (10,000 values/s, 50,000 items) are not lowered.

## Full-size soak

Same floors, longer window. Not the CI default.

```
docker build -t oip-bench-points:ci -f bench/sim/Dockerfile bench
./gradlew :test-infra:driver-floors:driverFloorTest -Pprf009Seconds=60 -Pprf010HoldSeconds=30
```

## What the profile showed

On a container bridge, before these baselines were recorded:

- Modbus, 10,000 registers, 20 ms scan, concurrency 1, 4, 8, and 16: about 500,000 good values/s. The scan finished inside the 20 ms period, so a deeper pipe did not raise the rate. Peak in-flight requests matched `concurrentRequests`. Values per PDU were 125. The accepted PRF-009 baseline is 499979 values/s from the 4 s window at concurrency 8.
- OPC UA: 50,000 items reached good quality in 3.6 s (`monitoredItemCount` 50000, 100 create batches). Items that share a sampling interval are one subscription and are created in batches of 500. The accepted PRF-010 baseline is 50000 good monitored items.

The drivers already held both floors. The test locks the rate, the coalesce, the pipe depth, the monitored-item count, and the batching.
