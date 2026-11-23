---
title: PLC on Windows
sidebar_label: PLC on Windows
---

# PLC on Windows

:::caution PLC on Windows
This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel. Scan times under 100 mS are difficult to execute. For higher performance, use on Linux.
:::

The same words are shown, and cannot be dismissed, on the Logic editor, the PLC status, and the resource split page while the gateway is running on Windows. The gateway tells the page which operating system it is actually on. A Linux gateway does not show the banner.

## What Windows can and cannot do

The scan clock uses a high-resolution waitable timer and a 1 ms timer period. The tag bridge is a named file mapping with the same layout as Linux. Commands use a named pipe. The engine can pin itself to the cores you choose, ask for the real-time priority class (and fall back to high if Windows refuses), lock a working set, and turn off power throttling for its process.

Windows can still preempt that thread, move interrupts onto the same core, and trim memory. None of those settings is a real-time partition. A timing sample taken on a GitHub-hosted Windows runner is labeled **CI Windows runner, not reference**. It is not a performance claim.

## Resource split

On Windows the resource page asks for PLC cores and RAM. Apply writes `C:\ProgramData\ERIC\plc-resources.json` (affinity and locked memory for the next `eric-plc` start) and sets the gateway service `-Xmx`. It also moves the gateway process off those cores when Windows allows it. The page says that a core cannot be fully isolated. Revert deletes the file and the `-Xmx` and restores the gateway affinity. Uninstall does the same even if you keep ProgramData.

SMT cannot be switched off from this page on Windows.

## Compiler

The installer includes RuSTy `plc.exe` and `iec61131std.dll` as separate LGPL files, with the license and a source offer. They are not linked into ERIC. The block library is the `.st` sources under the PLC folder.
