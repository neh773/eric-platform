# Tasks, cycle time, and the core split

## The task

This build has one cyclic task. The period you should expect is 10 ms unless the engine was started with another period. The runtime pane in Logic shows the period it is actually using, in milliseconds, after you choose **Start** or after a deploy that starts the engine.

The period is the time from the start of one scan to the start of the next. It is not a promise that the scan will finish in that time. If the work takes longer, the overrun count increases. See [Diagnostics](diagnostics.md).

There is no second task, no event task, and no motion task in this build.

## Core and memory split

The admin page for the PLC resource split shows the processors and the memory the gateway can see.

1. Open the admin UI and the PLC resources page.
2. Read the detected cores, threads, and memory.
3. Set how many cores and how many gigabytes the scan engine may use.
4. Choose preview before apply. Preview does not change the machine. It shows the command or the pending boot line.
5. Apply writes the setting. On Linux, applying a boot split needs a reboot before the engine is pinned away from the rest of the system. On Windows, apply sets a processor affinity and a locked working set, and it sets the gateway Java heap. Restart the ERIC Gateway service and `eric-plc` so they pick that up. Revert removes those settings.

Windows cannot give the engine a core that nothing else will interrupt. The banner on the page says so. Use the split so a bench PC is less likely to starve the scan. Do not treat it as a real-time partition.

## Power user

`GET /api/v1/platform` tells the page whether the gateway itself is on Windows. The resource routes preview, apply, and revert the split. The logic status route returns `periodMs`, `jitterP99Ns`, `execP99Ns`, and `overruns`.
