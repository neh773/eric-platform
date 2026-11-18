# Load ramp: oip / core-curves

Core curves at 1, 2 and 4 CPUs with 2 GB per CPU. 100% changing every 1000 ms, Modbus TCP, OPC UA reads, local history off. Step ramp from 10,000 to 320,000 points. Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **completed**.

Wall clock 856.011s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | modbus | 1000 | 6.17 | 21.30 | 427.7 | 600.00 | 1126.00 | 9280.38 | 10000.00 | 0 | 0 | -0.26 |
| 20000 | modbus | 1000 | 6.16 | 22.91 | 490.1 | 793.00 | 1122.00 | 18506.08 | 20000.00 | 0 | 0 | -1.05 |
| 30000 | modbus | 1000 | 6.62 | 17.05 | 291.0 | 694.00 | 1277.00 | 27837.26 | 30000.00 | 0 | 0 | -0.77 |
| 40000 | modbus | 1000 | 7.07 | 17.36 | 338.8 | 777.50 | 1059.00 | 37139.68 | 40000.00 | 0 | 0 | -0.73 |
| 60000 | modbus | 1000 | 9.04 | 20.50 | 434.0 | 666.00 | 1383.00 | 56785.73 | 60000.00 | 0 | 0 | 1.33 |
| 80000 | modbus | 1000 | 16.69 | 20.65 | 386.8 | 768.00 | 1518.00 | 74186.76 | 80000.00 | 0 | 0 | -0.87 |
| 100000 | modbus | 1000 | 13.51 | 20.70 | 387.0 | 746.00 | 1594.00 | 92642.14 | 100000.00 | 0 | 0 | -0.96 |
| 120000 | modbus | 1000 | 15.46 | 20.83 | 453.5 | 842.00 | 1140.00 | 111283.23 | 120000.00 | 0 | 0 | -0.94 |
| 160000 | modbus | 1000 | 23.01 | 31.23 | 822.5 | 1065.00 | 2055.00 | 153472.36 | 160000.00 | 0 | 0 | 3.34 |
| 200000 | modbus | 1000 | 28.34 | 31.45 | 649.5 | 1279.00 | 2533.00 | 188730.14 | 200000.00 | 0 | 0 | 1.08 |
| 240000 | modbus | 1000 | 40.02 | 37.15 | 891.0 | 1777.00 | 2963.00 | 219629.76 | 240000.00 | 0 | 0 | -1.08 |
| 280000 | modbus | 1000 | 49.47 | 37.69 | 984.0 | 1973.00 | 3449.00 | 258252.31 | 280000.00 | 0 | 0 | -1.22 |
| 320000 | modbus | 1000 | 45.86 | 37.95 | 1032.0 | 2474.00 | 3589.00 | 304332.07 | 320000.00 | 0 | 0 | 3.09 |

## Notes

- At 80000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 160000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 200000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 240000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 280000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 320000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
