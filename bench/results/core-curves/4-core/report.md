# Load ramp: oip / core-curves

Core curves at 1, 2 and 4 CPUs with 2 GB per CPU. 100% changing every 1000 ms, Modbus TCP, OPC UA reads, local history off. Step ramp from 10,000 to 320,000 points. Cutoffs, windows and sampling copied unchanged from oip-edge.

Stopped because **completed**.

Wall clock 850.292s. Cap 7200.0.

Comparable drift is how far delivered ticks lag the wall clock, as a percent of the step period. CPU percent is docker CPU (100 = one core) divided by the cgroup CPU quota. Memory percent is container RSS over the cgroup limit when one is set, otherwise over the host. Values/s counts source ticks advanced on the sampled changing tags, scaled up to the whole point count. A read is slower than the source period, so one read can cover several ticks.

| Points | Protocol | Period ms | CPU % | Memory % | Heap MB | Latency p50 ms | Latency p95 ms | Values/s | Expected/s | Quality errors | Mismatches | Drift % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | modbus | 1000 | 2.95 | 7.66 | 143.6 | 700.00 | 982.00 | 9094.98 | 10000.00 | 0 | 0 | -2.25 |
| 20000 | modbus | 1000 | 2.51 | 7.91 | 274.9 | 461.00 | 1064.00 | 18971.79 | 20000.00 | 0 | 0 | 1.38 |
| 30000 | modbus | 1000 | 3.30 | 8.83 | 249.4 | 586.50 | 1018.00 | 27806.50 | 30000.00 | 0 | 0 | -0.90 |
| 40000 | modbus | 1000 | 4.48 | 9.04 | 319.2 | 803.00 | 1087.00 | 37109.12 | 40000.00 | 0 | 0 | -0.82 |
| 60000 | modbus | 1000 | 4.07 | 10.48 | 310.2 | 779.00 | 1147.00 | 55666.27 | 60000.00 | 0 | 0 | -0.81 |
| 80000 | modbus | 1000 | 14.15 | 11.09 | 444.9 | 936.00 | 1713.00 | 75619.07 | 80000.00 | 0 | 0 | 1.31 |
| 100000 | modbus | 1000 | 8.37 | 11.12 | 405.7 | 894.00 | 1616.00 | 94566.51 | 100000.00 | 0 | 0 | 1.22 |
| 120000 | modbus | 1000 | 6.13 | 13.84 | 637.3 | 1174.00 | 1989.00 | 112998.79 | 120000.00 | 0 | 0 | 1.19 |
| 160000 | modbus | 1000 | 10.56 | 13.89 | 654.0 | 937.00 | 1811.00 | 147237.60 | 160000.00 | 0 | 0 | -1.02 |
| 200000 | modbus | 1000 | 29.12 | 14.07 | 607.0 | 1466.00 | 2878.00 | 181584.31 | 200000.00 | 0 | 0 | -3.00 |
| 240000 | modbus | 1000 | 18.70 | 16.05 | 689.5 | 1258.00 | 3066.00 | 230280.70 | 240000.00 | 0 | 0 | 3.26 |
| 280000 | modbus | 1000 | 18.93 | 15.87 | 776.0 | 1407.00 | 2977.00 | 256508.63 | 280000.00 | 0 | 0 | -3.12 |
| 320000 | modbus | 1000 | 21.68 | 15.96 | 815.2 | 1499.00 | 3206.00 | 300659.38 | 320000.00 | 0 | 0 | 1.30 |

## Notes

- At 100000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 120000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 160000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 200000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 240000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 280000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
- At 320000 points the simulator CPU exceeded 80% of one core. A gateway cutoff in this step may be the simulator falling behind.
