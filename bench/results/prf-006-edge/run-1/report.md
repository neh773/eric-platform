# Load ramp: oip / oip-edge-prf006

PRF-006 on EDGE (4 vCPU / 4 GB): starts at the 88,500-tag floor, then the oip-edge steps. 100% changing every 1000 ms, local history on (one HistorySettings per tag, embedded historian). Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **memory**.

Wall clock 155.966s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 88500 | modbus | 1000 | 46.69 | 41.78 | 829.4 | 814.00 | 1334.00 | 80464.64 | 88500.00 | 0 | 0 | -3.14 |
| 120000 | modbus | 1000 | 34.89 | 53.06 | 945.0 | 922.00 | 1896.00 | 112176.97 | 120000.00 | 0 | 0 | -1.36 |

Local history: rows the collector wrote between the first and last sample of the window (GET /api/v1/diagnostics/history/providers).

| Points | Rows written | Rows/s | Dropped | Pending max |
|---:|---:|---:|---:|---:|
| 88500 | 3211125 | 75861.90 | 0 | 528000 |
| 120000 | 2530000 | 55459.06 | 2069854 | 1000000 |

## Notes

- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- Applied container limits cpus=4.0, memory=4g to oip-edge-gw.
