# PLC overview

ERIC can run Structured Text in a scan engine called `eric-plc`. Control Canvas is where you write the program. The gateway holds the tags. The engine reads and writes those tags on each scan.

This is not a safety controller. On Windows it is not a real-time controller. Read [Safety](../safety.md) and [Windows soft-PLC](windows.md) before you deploy anything that moves equipment.

## What is in this build

- One Structured Text program at a time, checked and deployed from Control Canvas.
- A cyclic task. The usual period is 10 ms. The status line shows the period the engine is actually using.
- The bundled IEC standard library: timers, counters, and edge triggers.
- The ERIC process library, including `ERIC_PID`.
- A tag map that connects program variables to gateway tags.
- Structures: gateway UDTs as ST types, `STRUCT`, and `VAR_IN_OUT` / `VAR_INPUT` structure parameters, bound to UDT
  instances from a list. See [Structures and gateway UDTs](udts.md).
- The gateway starts and supervises the scan engine itself. Deploy compiles the program, starts `eric-plc`, and
  the program resumes after a gateway restart.
- Watched values, force, and unforce while the engine is running.
- A resource page that proposes how many cores and how much memory the engine may use.

## What a Linux computer needs

The Linux package includes the Structured Text compiler and the scan engine. Deploy also needs a C compiler to link
the program: install `gcc` (`sudo apt install gcc`, or `sudo dnf install gcc`). Without one, deploy says so. Check
works without it.

## What a Windows computer needs

Nothing extra. The Windows installer includes the Structured Text compiler, the scan engine and the linker that
turns a program into the file the engine loads (LLVM's `lld-link`, in `plc\llvm`). Deploy works on a plain Windows
10 or 11 PC with no Visual Studio, Build Tools or other developer tools. See [Windows soft-PLC](windows.md) for what
the Windows scan is and is not good for.

## What is not in this build

Ladder, function block diagram, and sequential function chart. Online program change. Breakpoints. A separate simulation mode inside the engine. Motion. A safety task. Direct rack I/O from the engine. Those items are on the [wishlist](../wishlist.md).

## Where to go next

1. [The editor](editor.md)
2. [Write, check, and deploy](programs.md)
3. [Tasks, cycle time, and the core split](tasks.md)
4. [Standard library](stdlib.md)
5. [Process library](process-library.md)
6. [Tags](tags.md)
7. [Structures and gateway UDTs](udts.md)
8. [Diagnostics](diagnostics.md)
9. [Troubleshooting](troubleshooting.md)
