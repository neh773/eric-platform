# Load ramp: oip / core-curves

Core curves at 1, 2 and 4 CPUs with 2 GB per CPU. 100% changing every 1000 ms, Modbus TCP, OPC UA reads, local history off. Step ramp from 10,000 to 200,000 points. Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **completed**.

Wall clock 661.901s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | modbus | 1000 | 8.71 | 22.72 | 154.0 | 794.00 | 1207.00 | 9048.49 | 10000.00 | 0 | 0 | -2.79 |
| 20000 | modbus | 1000 | 9.34 | 23.71 | 176.9 | 749.00 | 1188.00 | 18981.49 | 20000.00 | 0 | 0 | 1.49 |
| 30000 | modbus | 1000 | 9.69 | 24.35 | 191.0 | 843.00 | 1246.00 | 28478.95 | 30000.00 | 0 | 0 | 1.48 |
| 40000 | modbus | 1000 | 10.83 | 26.41 | 188.7 | 693.50 | 1331.00 | 37936.37 | 40000.00 | 0 | 0 | 1.51 |
| 60000 | modbus | 1000 | 15.38 | 27.44 | 222.7 | 963.00 | 1449.00 | 56431.54 | 60000.00 | 0 | 0 | 1.52 |
| 80000 | modbus | 1000 | 20.43 | 29.18 | 256.6 | 1074.00 | 1636.00 | 75113.69 | 80000.00 | 0 | 0 | -0.89 |
| 100000 | modbus | 1000 | 19.33 | 32.07 | 292.0 | 971.00 | 1365.00 | 94797.26 | 100000.00 | 0 | 0 | 1.33 |
| 120000 | modbus | 1000 | 28.64 | 33.43 | 324.2 | 1103.50 | 1811.00 | 113870.35 | 120000.00 | 0 | 0 | 1.47 |
| 160000 | modbus | 1000 | 37.83 | 36.57 | 390.5 | 1238.00 | 2374.00 | 148187.43 | 160000.00 | 0 | 0 | -0.98 |
| 200000 | modbus | 1000 | 47.50 | 41.30 | 477.0 | 1466.00 | 2806.50 | 185721.61 | 200000.00 | 0 | 0 | -0.40 |

## Notes

- At 80000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 160000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 200000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
