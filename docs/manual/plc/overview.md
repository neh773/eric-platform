# PLC overview

ERIC can run Structured Text in a scan engine called `eric-plc`. Control Canvas is where you write the program. The gateway holds the tags. The engine reads and writes those tags on each scan.

This is not a safety controller. On Windows it is not a real-time controller. Read [Safety](../safety.md) and [Windows soft-PLC](windows.md) before you deploy anything that moves equipment.

## What is in this build

- One Structured Text program at a time, checked and deployed from Control Canvas.
- A cyclic task. The usual period is 10 ms. The status line shows the period the engine is actually using.
- The bundled IEC standard library: timers, counters, and edge triggers.
- The ERIC process library, including `ERIC_PID`.
- A tag map that connects program variables to gateway tags.
- Watched values, force, and unforce while the engine is running.
- A resource page that proposes how many cores and how much memory the engine may use.

## What is not in this build

Ladder, function block diagram, and sequential function chart. Online program change. Breakpoints. A separate simulation mode inside the engine. Motion. A safety task. Direct rack I/O from the engine. Those items are on the [wishlist](../wishlist.md).

## Where to go next

1. [The editor](editor.md)
2. [Write, check, and deploy](programs.md)
3. [Tasks, cycle time, and the core split](tasks.md)
4. [Standard library](stdlib.md)
5. [Process library](process-library.md)
6. [Tags](tags.md)
7. [Diagnostics](diagnostics.md)
8. [Troubleshooting](troubleshooting.md)
