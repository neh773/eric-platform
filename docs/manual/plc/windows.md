# Windows soft-PLC

On Windows, `eric-plc` is a normal process. It shares the PC with Windows. The scan period moves when the PC is busy. There is no real-time kernel and no core that Windows promises to leave alone.

## The banner

While the gateway process is running on Windows, Logic, the PLC status, and the resource page show a banner. It cannot be dismissed. It says this PLC is on a Windows PC, that scan timing is not guaranteed, and that it is for development, testing, training, and non-critical slow process control only. It also says scan times under 100 mS are difficult to execute, and that for higher performance, use on Linux.

A Linux gateway does not show that banner. Linux can get closer to a fixed scan when the kernel is real-time and a core is isolated. That setup is not what the Windows installer gives you.

## What to use it for

A bench program. A trainer. A slow loop where a late scan is an inconvenience.

## What not to use it for

Safety. Fast motion. Anything that must trip in a fixed number of milliseconds. Live production. See [Safety](../safety.md).

The beta warning and this banner are separate. The beta warning is about the whole product. This banner is about the Windows scan.
