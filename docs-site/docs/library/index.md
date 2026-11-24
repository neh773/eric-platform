---
title: Process library
sidebar_label: Process library
---

# Process library

:::caution PLC on Windows
This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel. Scan times under 100 mS are difficult to execute. For higher performance, use on Linux.
:::

These blocks are ordinary Structured Text in the scan engine. Insert the block name from **Logic**, then fill in the inputs. `DT` is the task period in seconds. Nothing here is a safety function.

## Scale a raw signal

`ERIC_AI_SCALE` turns a raw number into engineering units. Give the raw low and high, and the engineering low and high. The output is the straight line between them. A raw value outside that raw span raises **BAD**. **CLAMP** keeps the output inside the engineering span. If the two raw ends are the same, the output stays at the engineering low and **BAD** is on.

## Limit alarms

`ERIC_ALARM_LIMITS` watches one measurement against high-high, high, low, and low-low. An alarm turns on only after the measurement has been past the limit for **DELAY** seconds. It turns off when the measurement comes back through the limit by **DEADBAND**. A delay of zero turns the alarm on in the same scan.

## Motor and valve

`ERIC_MOTOR` has three choices. Hand follows the hand command. Auto follows the auto command. Otherwise the run output is off. A trip, or a missing permissive, forces the run output off. If the run output is on and the feedback stays off for **FAIL_TIME**, **FAIL_START** latches and the run output drops until **RESET**.

`ERIC_VALVE` works the same way for open and close. Hand open and hand close are separate. Auto uses one open command, and the close output is the opposite. It will not drive open and close together. **FAIL_OPEN** and **FAIL_CLOSE** latch the same way as a failed motor start.

## Ramp, filter, total, and run time

`ERIC_RAMP` moves the output toward the input, but no faster than **RATE** units each second. The first scan, and **RESET**, set the output equal to the input so it does not jump later.

`ERIC_FILTER` has two outputs. **OUT** is a first-order lag with time constant **T**. **AVG** is the average of the last **N** lag samples, from 1 to 16. A time constant of zero copies the input straight through.

`ERIC_TOTALIZER` adds `RATE * DT` into **TOTAL** on every scan. **RESET** sets the total back to zero.

`ERIC_RUNTIME` adds run time, in hours, while **RUN** is on, and adds one to **STARTS** each time **RUN** turns on. **RESET** clears both.

## On and off, select, and split

`ERIC_HYSTERESIS` turns on when the input reaches **ON_LEVEL** and stays on until the input falls to **OFF_LEVEL**. Put the on level above the off level for a high switch.

`ERIC_SELECT` picks the larger input when **HIGH** is on, and the smaller input when **HIGH** is off. **USE_B** tells you the second input won.

`ERIC_SPLIT_RANGE` takes an input from 0 to 100 and a split point. Below the split, output A rises from 0 to 100 and output B stays at 0. Above the split, A stays at 100 and B rises from 0 to 100. A split of 0 or 100 is treated as 50.

## Time proportion, lead-lag, and dead time

`ERIC_TPO` turns a percent into on and off time. Over each **CYCLE** seconds, the output is on for that percent of the cycle and off for the rest. Zero percent stays off. One hundred percent stays on.

`ERIC_LEADLAG` applies `(1 + T_LEAD * s) / (1 + T_LAG * s)` with a backward step. The first scan, and **RESET**, copy the input so the output does not jump. If the lag time is zero or negative, the output copies the input. Equal lead and lag times also copy the input. A lag with no lead is an ordinary first-order lag.

`ERIC_DEADTIME` holds the input and releases it **TIME** seconds later. Until that time has passed, the output is zero and **READY** is off. The longest delay kept here is 64 scans. A time of zero copies the input.

## Sequence and pump rotation

`ERIC_SEQUENCER` has four steps. **START** leaves idle and enters step 1. A step ends when its dwell time has elapsed and **TRANS** is on. **HOLD** freezes the timer. After step 4, **DONE** stays on until **RESET**, which returns to idle.

`ERIC_ALTERNATOR` runs one or two pumps. A demand of one starts the lead pump, or the other pump if the lead is not available. A demand of two starts both available pumps. **ROTATE** swaps which pump is lead. If **ROTATE_AFTER** hours is greater than zero, the lead also swaps after it has run that long.
