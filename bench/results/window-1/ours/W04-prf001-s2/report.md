# Load ramp: OipTarget / h2h

PRF-001 head-to-head vs the reference platform 8.3.10 on S2, S4, S8, S16, and EDGE. One size is one 2 h trial. a manual license reset is manual. Cutoffs stay CPU above 50%, memory above 50%, clock drift outside +/-10%, and any error or data loss. E(n) is not this profile.

Stopped because **completed**.

Wall clock 586.485s. Cap 1680.0. Trial seconds left 1680.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5000 | modbus | 1000 | 24.16 | 15.84 | 144.5 | 506.00 | 543.00 | 499.89 | 500.00 | 0 | 0 | 0.00 |
| 10000 | modbus | 1000 | 3.96 | 15.93 | 160.9 | 972.00 | 1006.00 | 999.78 | 1000.00 | 0 | 0 | -0.00 |
| 20000 | modbus | 1000 | 6.71 | 16.44 | 211.5 | 822.00 | 856.00 | 1999.46 | 2000.00 | 0 | 0 | -0.01 |
| 40000 | modbus | 1000 | 9.83 | 20.17 | 343.3 | 193.00 | 235.00 | 3999.35 | 4000.00 | 0 | 0 | 0.00 |
| 80000 | modbus | 1000 | 17.28 | 25.21 | 446.5 | 1020.00 | 1051.00 | 7998.86 | 8000.00 | 0 | 0 | 0.01 |
| 160000 | modbus | 1000 | 23.97 | 30.76 | 662.5 | 686.00 | 723.00 | 15997.68 | 16000.00 | 0 | 0 | 0.01 |
| 320000 | modbus | 1000 | 46.24 | 37.44 | 793.5 | 1098.00 | 1177.00 | 31990.86 | 32000.00 | 0 | 0 | -0.01 |

## Notes

- Wall-clock cap is the remaining trial (1680s), tighter than the profile cap of 6600s.
