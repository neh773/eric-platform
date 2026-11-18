# Load ramp: oip / oip-edge-prf006

PRF-006 on EDGE (4 vCPU / 4 GB): starts at the 88,500-tag floor, then the oip-edge steps. 100% changing every 1000 ms, local history on (one HistorySettings per tag, embedded historian). Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **cpu**.

Wall clock 155.225s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 88500 | modbus | 1000 | 30.89 | 42.55 | 852.5 | 711.00 | 1169.00 | 81269.40 | 88500.00 | 0 | 0 | -1.37 |
| 120000 | modbus | 1000 | 50.47 | 60.76 | 1207.4 | 823.00 | 1189.00 | 110672.61 | 120000.00 | 0 | 0 | -1.32 |

Local history: rows the collector wrote between the first and last sample of the window (GET /api/v1/diagnostics/history/providers).

| Points | Rows written | Rows/s | Dropped | Pending max |
|---:|---:|---:|---:|---:|
| 88500 | 3805500 | 89364.62 | 0 | 127000 |
| 120000 | 5450000 | 119510.94 | 0 | 520000 |

## Notes

- Applied container limits cpus=4.0, memory=4g to oip-edge-gw.
