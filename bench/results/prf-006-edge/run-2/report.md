# Load ramp: oip / oip-edge-prf006

PRF-006 on EDGE (4 vCPU / 4 GB): starts at the 88,500-tag floor, then the oip-edge steps. 100% changing every 1000 ms, local history on (one HistorySettings per tag, embedded historian). Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **cpu**.

Wall clock 252.904s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 88500 | modbus | 1000 | 30.61 | 42.55 | 810.5 | 993.00 | 1627.00 | 82100.68 | 88500.00 | 0 | 0 | -0.10 |
| 120000 | modbus | 1000 | 31.64 | 49.12 | 744.5 | 982.00 | 1620.00 | 111057.46 | 120000.00 | 0 | 0 | -1.59 |
| 160000 | modbus | 1000 | 54.05 | 58.15 | 941.0 | 1035.00 | 2374.00 | 151026.04 | 160000.00 | 0 | 0 | 0.89 |

Local history: rows the collector wrote between the first and last sample of the window (GET /api/v1/diagnostics/history/providers).

| Points | Rows written | Rows/s | Dropped | Pending max |
|---:|---:|---:|---:|---:|
| 88500 | 3152125 | 74974.09 | 0 | 636875 |
| 120000 | 2480000 | 54234.64 | 2617500 | 1000000 |
| 160000 | 2430000 | 53297.01 | 3115750 | 1000000 |

## Notes

- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 160000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- Applied container limits cpus=4.0, memory=4g to oip-edge-gw.
