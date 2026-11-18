---
title: PID block
sidebar_label: PID block
---

# PID block

:::caution PLC on Windows
This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel.
:::

`ERIC_PID` is a control block you put in a Structured Text program. It is not a safety controller.

Each scan it looks at where the measurement is and where you want it, and it writes one output. A level loop is the example: the setpoint and the level are tags, and the output is the valve command.

Build it with `plc/eric-plc/scripts/build-pid.sh`. The level example is `plc/eric-plc/examples/level`. Its cycle is 100 ms, and the program passes that same 0.1 s into the block. If you change the task period, change that number too, or pass the measured scan interval instead.

## What each setting does

| Setting | Meaning |
|---|---|
| SP | Where you want the measurement. |
| PV | The measurement now. |
| MANUAL | On: the output follows MAN_OUT. Off: the block calculates the output. |
| MAN_OUT | The output you want while manual is on. |
| KP | How hard the block reacts to the present error. Larger moves the output more. |
| TI | Integral time, in seconds. This removes a leftover error. Larger is slower and calmer. Zero or negative turns the integral off. |
| TD | Derivative time, in seconds. This reacts to how fast the measurement is moving. Zero or negative turns it off. |
| N | Filter on that derivative. The filter time is TD / N. 8 to 10 is a normal start. Zero or negative turns the derivative off. |
| OUT_MIN, OUT_MAX | The smallest and largest output, for example 0 and 100 for a valve. |
| DIRECT | On: the output rises when the measurement is below the setpoint (a heater, or a valve that fills). Off: the output rises when the measurement is above the setpoint (a cooler, or a valve that drains). |
| DT | Seconds since the last scan. Use the task period, or the measured interval when a scan ran long. |
| RESET | On: the block forgets its internal memory and starts again from the present error. |

## What it reports

| Output | Meaning |
|---|---|
| OUT | The command to send. |
| ERROR | Setpoint minus measurement when DIRECT is on. Measurement minus setpoint when DIRECT is off. |
| LIMITED_HIGH | The command is sitting on OUT_MAX. |
| LIMITED_LOW | The command is sitting on OUT_MIN. |

The derivative watches the measurement, not the setpoint. Changing the setpoint does not spike the output.

If the command sits on a limit and the error is still pushing that way, the block stops accumulating that error. When the error reverses, the command leaves the limit on the next scan instead of staying stuck.

Putting the loop in manual, with MAN_OUT equal to the current output, does not jump. Turning manual off again holds that same output for the scan, then continues. Changing KP, TI, TD, N, or DIRECT while automatic also holds the current output for that scan.

## Tuning

1. Turn the derivative off (TD = 0) and use a long integral time.
2. Raise KP until the measurement starts to ring around the setpoint, then lower it until the ring is gone.
3. Shorten TI until the leftover error disappears in a reasonable time. If it rings, lengthen TI again.
4. Add a little TD, with N around 8, only if the measurement is still slow to stop. Too much TD makes the output noisy.
5. Set OUT_MIN and OUT_MAX to what the valve or drive can actually do.

Start conservative. A slow loop that settles is easier to tighten than a loop that is already oscillating.
