# Load ramp: oip / core-curves-ram

Lowest clean RAM at 4 CPUs: one fixed step of 100,000 points, run at 8, 4, 2 and 1 GB. Every other setting, and the cutoffs, copied unchanged from core-curves (and so from oip-edge).

Stopped because **completed**.

Wall clock 73.492s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100000 | modbus | 1000 | 7.14 | 11.49 | 515.5 | 1006.00 | 1626.00 | 92790.83 | 100000.00 | 0 | 0 | -0.45 |

## Notes

- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
