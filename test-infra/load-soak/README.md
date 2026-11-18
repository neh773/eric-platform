# Load and soak harness (PRF-003, PRF-008)

Two container scenarios from ADR 0010. Each runs the gateway in the test JVM and its peers in containers on a user-defined Docker bridge. No host port is published, and the gateway dials bridge addresses, never loopback. Sizes are in `test-infra/perf/prf-008.json` and `test-infra/perf/prf-003.json`. The Load bench CI job runs the `ci` size. `-PloadSoakSize=full` runs the floor size.

Both scenarios stay `implemented: false` in `test-infra/perf/perf-requirements.json` until a floor-size run on larger hardware exists. CI writes its reports under `test-infra/load-soak/build/reports/load-soak`.

## PRF-008 historian soak (`historianSoakTest`)

TimescaleDB, plus the fault proxy in front of it, both images pinned in `test-infra/compose.yaml`. Memory tags `soak/pNNNNNNN`, each with on-change `HistorySettings` to a SQL `HistoryProvider` on a `db/Connection` through the proxy. One scan per second, at an absolute 1 s deadline, writes `scan + 1` stamped `t0 + scan * 1000` to the tags where `(index + scan) % 100 < percent`. Partway through the steady phase the proxy cuts the database link for `cutSeconds`. A stretch phase at the stretch rate follows.

Verdict, read straight from the database after the drain: rows = writes plus one first sample per tag, no duplicate (tag, value), no row that breaks the change rule or has the wrong timestamp, every row Good, collector dropped and diverted 0, quarantine empty. Cutoffs (CPU 50, memory 50, drift +/-10 on VM counters, 10 s samples, steady mean and every 60 s mean) gate the steady phase. The stretch phase is recorded and does not gate.

## PRF-003 full load (`fullLoadTest`)

The bench point simulator (`oip-bench-points:ci`) with every point changing once per 1000 ms, and TimescaleDB in its own container. Half the points come through one Modbus TCP `Device`, half through one OPC UA `Connection`, scanned every 500 ms so no change is aliased away. Every tag has on-change history to TimescaleDB and one bit-mode alarm on a middle bit, with the journal on. Notification pipelines are off. 75 sessions, one API key each (a principal holds at most 8 sockets), subscribe 300 tags each on `/api/v1/ws`.

Verdict over the steady window, by event timestamp. An independent engine subscriber counts changes per tag. History rows per tag must match that count and the value sum. Every change must be Good. The alarm journal must lose no rows, and the history collector must drop none. After the simulator freezes, each session's last value per path must equal the tag's value. Cutoffs gate the window. CPU is the test JVM, which also hosts the 75 clients, so it overstates gateway CPU.

## Commands

```bash
docker build -t oip-bench-points:ci -f bench/sim/Dockerfile bench

# CI size (the Load bench job)
./gradlew :test-infra:load-soak:historianSoakTest --no-daemon
./gradlew :test-infra:load-soak:fullLoadTest --no-daemon

# Full size, on the reference machine (S8: 8 vCPU / 16 GB for PRF-003; the database on its own host)
./gradlew :test-infra:load-soak:historianSoakTest -PloadSoakSize=full -PloadSoakHeap=8g
./gradlew :test-infra:load-soak:fullLoadTest -PloadSoakSize=full -PloadSoakHeap=8g
```

Reports go to `test-infra/load-soak/build/reports/load-soak/`, and also to `-PloadSoakReport=<dir>` when that is set. The full PRF-008 run takes about 3.6 h (3 h steady, 30 min stretch, drain). The full PRF-003 run takes about 70 min. The Gradle timeout is 6 h at full size and 12 min at CI size. CPU and memory are measured against the machine the JVM runs on (`availableProcessors`, `MemTotal`), so run the full size on the reference size itself.
