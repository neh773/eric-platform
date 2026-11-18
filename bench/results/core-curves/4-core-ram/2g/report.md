# Load ramp: oip / core-curves-ram

Lowest clean RAM at 4 CPUs: one fixed step of 100,000 points, run at 8, 4, 2 and 1 GB. Every other setting, and the cutoffs, copied unchanged from core-curves (and so from oip-edge).

Stopped because **completed**.

Wall clock 77.131s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100000 | modbus | 1000 | 8.76 | 40.05 | 392.7 | 797.00 | 1878.00 | 90806.76 | 100000.00 | 0 | 0 | -2.40 |

## Notes

- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
