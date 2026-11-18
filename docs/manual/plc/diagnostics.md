# Diagnostics

The runtime pane in Logic is the scan monitor.

## Read the status

1. Deploy a program and choose **Start**.
2. The state line says whether the engine is running or stopped.
3. The next line is the scan sample: period in milliseconds, jitter p99 in nanoseconds, execution p99 in nanoseconds, and the overrun count.

Period is the task's requested spacing. Jitter is how much the start of the scan moved. Execution is how long the program took. Overruns count scans that did not finish before the next period was due.

## Watched variables

While the engine is running, the pane lists variables the engine is publishing. The value updates about four times a second. A variable marked forced is not using the program's value.

## Force

1. Type a value next to the variable.
2. Choose **Force**. An output can be forced. The pane says when any force is active.
3. Choose **Unforce** to give the variable back to the program.

A force is a commissioning tool. Clear it before you leave the bench. A forced output keeps that value across scans until you unforce it.

## What this pane does not do

It does not set a breakpoint. It does not show a trend of scan time. It does not change the program while it runs, other than forces. Online change is not in this build.

## Power user

`GET /api/v1/logic/status` returns `state`, `periodMs`, `jitterP99Ns`, `execP99Ns`, and `overruns`. The watch route returns the variables and whether forces are active. Force and unforce are separate posts. A 401 means the session cannot use the designer.
