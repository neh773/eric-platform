# PLC troubleshooting

## Check program lists a line

The compiler rejected that line. The message names the token it did not expect. Common causes: a missing `END_VAR` or `END_PROGRAM`, a type the editor completion list does not offer, or a block name that is not in the standard library or the process library.

## Compile failed on deploy

The running program is unchanged. Read the problem list. Deploy again after the source checks clean. If the list is empty and deploy still fails, the engine did not accept the compiled file. Read the gateway log for `eric-plc`.

## The value in the program never changes

The tag map address and the `AT` address disagree, the direction is `out` when you meant `in`, or the gateway tag is bad quality. Open the tag in the admin tag browser. A bad quality tag supplies the safe value from the map, not a live reading.

## The output tag stays at the safe value

The engine is stopped, the map was not saved before deploy, or a force is holding the output. The runtime pane says when forces are active. Unforce, or choose Start.

## Overruns climb on Windows

The PC is busy, or the period is shorter than Windows can keep. That is the soft-PLC limit. Lengthen the work the program does, or move the engine to a Linux PC with an isolated core. Do not use the Windows process where a late scan is unsafe.

## The banner is missing on a Windows PC

The page asked the gateway which operating system it is on, and the gateway is not on Windows. A browser on Windows talking to a Linux gateway does not show the banner. The banner follows the gateway, not the screen you are sitting at.

## Linux apply says a reboot is required

The core split changes the boot line. Until the reboot, the engine is still on the old cores. Preview first if you only wanted to read the command.
