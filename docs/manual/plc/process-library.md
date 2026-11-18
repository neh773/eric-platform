# Process library

These blocks ship as Structured Text next to the standard library. `DT` is the task period in seconds. Call each block every scan. A block that is not called does not move.

Insert the name from the editor list, or type it. Check program compiles it with the program.

## ERIC_PID

PID on the process value. The derivative does not kick when the setpoint steps. While the output is against a limit, the integrator stops pushing further into that limit. Manual mode tracks the integrator so leaving manual does not jump. A tuning change on an automatic scan holds the previous output for that scan.

| Input | Meaning |
| --- | --- |
| `SP`, `PV` | Setpoint and process value. |
| `MANUAL`, `MAN_OUT` | Manual holds the output at `MAN_OUT`. |
| `KP`, `TI`, `TD`, `N` | Gain, integral time, derivative time, derivative filter factor. `TI <= 0` turns integration off. `TD <= 0` or `N <= 0` turns the derivative off. Filter time is `TD / N`. |
| `OUT_MIN`, `OUT_MAX` | Output limits. |
| `DIRECT` | True means the output rises when `PV` is below `SP`. |
| `DT` | Cycle time in seconds. |
| `RESET` | Clears the integrator state. |

| Output | Meaning |
| --- | --- |
| `OUT` | The manipulated value. |
| `ERROR` | The signed error. |
| `LIMITED_HIGH`, `LIMITED_LOW` | The output is on a limit. |

```
VAR
    Loop : ERIC_PID;
END_VAR
Loop(SP := DoSp, PV := Do, MANUAL := FALSE, MAN_OUT := 0.0,
     KP := 1.2, TI := 30.0, TD := 0.0, N := 10.0,
     OUT_MIN := 0.0, OUT_MAX := 100.0, DIRECT := TRUE, DT := 0.01, RESET := FALSE);
Speed := Loop.OUT;
```

## ERIC_AI_SCALE

Scales a raw number into engineering units. `BAD` is true when the raw span is zero or the raw value is outside the raw range. `CLAMP` holds the output inside the engineering range.

Inputs: `RAW`, `RAW_MIN`, `RAW_MAX`, `EU_MIN`, `EU_MAX`, `CLAMP`. Outputs: `OUT`, `BAD`.

## ERIC_ALARM_LIMITS

High-high, high, low, and low-low on one value, with a deadband and a delay. The delay and deadband use `DT`.

Inputs: `PV`, `HH`, `H`, `L`, `LL`, `DEADBAND`, `DELAY`, `DT`. Outputs: `HH_ACT`, `H_ACT`, `L_ACT`, `LL_ACT`.

## ERIC_MOTOR

Hand or auto command, with a permissive, a trip, and a start-failure timer. `RUN` is the command to the starter. `FAIL_START` latches if feedback does not arrive within `FAIL_TIME`.

Inputs: `HAND`, `AUTO`, `HAND_CMD`, `AUTO_CMD`, `PERMISSIVE`, `TRIP`, `FEEDBACK`, `FAIL_TIME`, `DT`, `RESET`. Outputs: `RUN`, `FAIL_START`.

## ERIC_VALVE

The same hand, auto, permissive, and trip pattern for an open and close pair. Each direction has its own failure if feedback does not arrive.

Inputs: `HAND`, `AUTO`, `HAND_OPEN`, `HAND_CLOSE`, `AUTO_OPEN`, `PERMISSIVE`, `TRIP`, `OPEN_FB`, `CLOSE_FB`, `FAIL_TIME`, `DT`, `RESET`. Outputs: `OPEN_OUT`, `CLOSE_OUT`, `FAIL_OPEN`, `FAIL_CLOSE`.

## ERIC_RAMP

Moves `OUT` toward `IN` at `RATE` units per second. `RESET` snaps to `IN`.

Inputs: `IN`, `RATE`, `DT`, `RESET`. Output: `OUT`.

## ERIC_FILTER

A first-order lag of time `T`, plus a moving average of the last `N` samples (at most 16). `OUT` is the lag. `AVG` is the average of the lag.

Inputs: `IN`, `T`, `N`, `DT`, `RESET`. Outputs: `OUT`, `AVG`.

## ERIC_TOTALIZER

Adds `RATE * DT` into `TOTAL`. `RESET` clears it.

Inputs: `RATE`, `DT`, `RESET`. Output: `TOTAL`.

## ERIC_RUNTIME

Hours and starts while `RUN` is true. Hours use `DT / 3600`. A rising `RUN` adds one start.

Inputs: `RUN`, `DT`, `RESET`. Outputs: `HOURS`, `STARTS`.

## ERIC_HYSTERESIS

`OUT` turns on at `ON_LEVEL` and off at `OFF_LEVEL`.

Inputs: `IN`, `ON_LEVEL`, `OFF_LEVEL`. Output: `OUT`.

## ERIC_SELECT

Picks the higher or the lower of `A` and `B`. `HIGH` true selects the higher. `USE_B` says the result came from `B`.

Inputs: `A`, `B`, `HIGH`. Outputs: `OUT`, `USE_B`.

## ERIC_SPLIT_RANGE

Splits a 0 to 100 command at `SPLIT` (default 50 if the split is outside 0 to 100). Below the split, `OUT_A` rises and `OUT_B` stays 0. Above it, `OUT_A` stays 100 and `OUT_B` rises.

Inputs: `IN`, `SPLIT`. Outputs: `OUT_A`, `OUT_B`.

## ERIC_TPO

Time-proportioned output. `IN` is a percent. `CYCLE` is the period in seconds. `OUT` is on for that fraction of the cycle.

Inputs: `IN`, `CYCLE`, `DT`. Output: `OUT`.

## ERIC_LEADLAG

Lead-lag. `T_LEAD` and `T_LAG` are seconds. `RESET` snaps to the input.

Inputs: `IN`, `T_LEAD`, `T_LAG`, `DT`, `RESET`. Output: `OUT`.

## ERIC_DEADTIME

Delays `IN` by `TIME` seconds, up to 64 scans. `READY` is true once the buffer has covered the delay. Before that, `OUT` is 0.

Inputs: `IN`, `TIME`, `DT`, `RESET`. Outputs: `OUT`, `READY`.

## ERIC_SEQUENCER

Four timed steps. `START` enters step 1. Each step waits for its time (`T1` to `T4`) and for `TRANS`, then advances. `HOLD` freezes the timer. `DONE` is true after step 4. `RESET` returns to step 0.

Inputs: `START`, `HOLD`, `RESET`, `TRANS`, `T1`, `T2`, `T3`, `T4`, `DT`. Outputs: `STEP`, `ELAPSED`, `DONE`.

## ERIC_ALTERNATOR

Lead and lag for two pieces of equipment. `DEMAND` of 1 runs the lead if it is available, otherwise the other. `DEMAND` of 2 runs both. `ROTATE` swaps the lead on a rising edge. `ROTATE_AFTER` swaps after that many lead hours.

Inputs: `DEMAND`, `AVAIL_1`, `AVAIL_2`, `ROTATE`, `ROTATE_AFTER`, `DT`, `RESET`. Outputs: `RUN_1`, `RUN_2`, `LEAD`.

## Power user

The source is `plc/eric-plc/st/eric_library.st` and `plc/eric-plc/st/eric_pid.st`. The Windows package copies those files into the PLC folder. They compile as part of the program. They are not a separate loaded library you version in a library manager. A library manager is on the wishlist.
