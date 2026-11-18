# Phase 7 and 8: PLC and DCS

Decision note, Nick, 2026-11-15. Milestone 1 of phase 7 is a proof of concept. PID, the editor, and the resource split landed in milestones 2, 3, and 4.

## Language

Structured Text only. Ladder, function block diagram, sequential function chart, and instruction list are later, if they are built at all. The preview does not lower those languages to a shared intermediate form.

## Compiler

[RuSTy](https://github.com/PLC-lang/rusty) v1.0.8, LGPLv3. ERIC is Apache-2.0, so RuSTy is an external binary. Its source is not vendored. The build script downloads the pinned release and checks the hash.

The program links RuSTy's bundled standard library dynamically (`libiec61131std.so`): TON, TOF, TP, CTU and the other counters, R_TRIG, F_TRIG, SR, and RS. PID is ERIC's own `ERIC_PID` block. OSCAT is not linked.

The compiler writes a shared library. Hardware addresses become globals named `__PI_<numbers>` for both inputs and outputs, so an input and an output must not use the same address numbers. The scan calls `Program(Program_instance)` once per cycle. Constructors run when the library loads.

## Scan engine

`plc/eric-plc` is a Rust process, separate from the JVM gateway.

One cyclic task. The period is configurable and defaults to 10 ms. The wait is `clock_nanosleep(TIMER_ABSTIME)` on `CLOCK_MONOTONIC`. The thread asks for `SCHED_FIFO`, `mlockall`, and an optional `sched_setaffinity` core. If the container refuses those calls, the scan still runs and the status file records the refusal.

Each cycle reads inputs, executes the program, and writes outputs. A cycle that is still running at the next deadline is an overrun. The engine does not catch up. After `max_consecutive_overruns` (default 3; 0 disables the latch) the program stops, outputs are forced to the safe values in the mapping, and a fault is latched until the process exits.

Start and stop are the process lifetime. SIGINT and SIGTERM finish the current wait and write the safe outputs. Retain memory is out of scope. TODO: retain.

## I/O

No Modbus client in the engine. Inputs and outputs are gateway tags. ERIC already has a Modbus driver; a tag fed by that driver can be mapped into the program.

The bridge is a POSIX shared-memory file with a seqlock. The gateway is the only writer of the input region. The scan thread is the only writer of the output region. Heartbeats are `CLOCK_REALTIME` nanoseconds. The scan thread does not take a socket on the cyclic path.

The layout is 128 bytes of header and then 32-byte slots. `PlcImage.kt` and `plc/eric-plc/src/image.rs` implement the same bytes. A unit test on each side checks the 16-byte prefix.

On bridge loss the gateway marks output quality bad and writes the safe values. The engine keeps calling the program on the last inputs, but it publishes the safe outputs until the gateway heartbeat returns. A lost bridge does not by itself latch the watchdog.

Direct real-time I/O from the engine (EtherCAT, or Modbus from the engine rather than from the gateway) is a wishlist item, not part of the preview.

## Timing

The scan records start jitter, program execution time, overrun counts, and the age of each input value when it is consumed. `eric-plc stats` prints the latest JSON. `eric-plc report` writes a histogram. p50 and p99 are the upper edge of the bucket that contains that rank. The maximum is the exact sample.

A container or cloud-VM run is labeled `container-size, not reference`. `scripts/plc_reference_run.sh` is the run for a Protectli-class mini PC with PREEMPT_RT, `isolcpus` / `nohz_full`, and the engine pinned to the isolated core. The script labels the report `reference` only when the kernel is PREEMPT_RT and the boot command line isolates a core. It does not change boot parameters.

## Later, not this milestone

- Online change, retain, redundant controllers, and the phase 8 control-module library (motor, valve, analog in and out) with faceplates.

Phase 8 redundant controllers and the control-module library stay on the wishlist. This note does not start them.
