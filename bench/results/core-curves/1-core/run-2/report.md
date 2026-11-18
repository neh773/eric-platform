# Load ramp: oip / core-curves

Core curves at 1, 2 and 4 CPUs with 2 GB per CPU. 100% changing every 1000 ms, Modbus TCP, OPC UA reads, local history off. Step ramp from 10,000 to 320,000 points. Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **cpu**.

Wall clock 662.98s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | modbus | 1000 | 9.47 | 21.99 | 150.4 | 630.00 | 1075.00 | 9230.15 | 10000.00 | 0 | 0 | -2.61 |
| 20000 | modbus | 1000 | 9.16 | 22.94 | 165.5 | 791.00 | 1306.00 | 18611.35 | 20000.00 | 0 | 0 | -0.66 |
| 30000 | modbus | 1000 | 9.82 | 23.75 | 181.5 | 802.00 | 1297.00 | 28486.54 | 30000.00 | 0 | 0 | 1.50 |
| 40000 | modbus | 1000 | 11.04 | 25.32 | 198.0 | 920.00 | 1481.00 | 37947.98 | 40000.00 | 0 | 0 | 1.42 |
| 60000 | modbus | 1000 | 18.02 | 27.13 | 209.9 | 1017.00 | 1734.00 | 56855.13 | 60000.00 | 0 | 0 | 1.44 |
| 80000 | modbus | 1000 | 22.00 | 28.76 | 250.2 | 816.00 | 1465.00 | 75788.23 | 80000.00 | 0 | 0 | 1.41 |
| 100000 | modbus | 1000 | 24.52 | 31.40 | 293.3 | 1018.00 | 1347.00 | 92572.71 | 100000.00 | 0 | 0 | -1.05 |
| 120000 | modbus | 1000 | 24.32 | 32.09 | 308.0 | 1076.00 | 2234.00 | 113921.82 | 120000.00 | 0 | 0 | 3.56 |
| 160000 | modbus | 1000 | 35.87 | 35.14 | 364.5 | 1222.00 | 2360.00 | 154088.54 | 160000.00 | 0 | 0 | 1.44 |
| 200000 | modbus | 1000 | 53.12 | 40.05 | 448.2 | 1185.00 | 2167.15 | 183725.06 | 200000.00 | 0 | 0 | -3.16 |

## Notes

- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 160000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 200000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
