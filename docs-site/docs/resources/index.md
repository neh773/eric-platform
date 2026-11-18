---
title: Resource split (PLC / ERIC)
sidebar_label: Resource split (PLC / ERIC)
---

# Resource split (PLC / ERIC)

:::caution PLC on Windows
This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel. On Windows the split is affinity, a locked working set, and the gateway `-Xmx`. Windows cannot fully isolate a core, and a reboot is not what applies it.
:::

The scan engine needs cores and memory that the rest of ERIC does not use. This page is the no-code way to choose that split. It is not a safety controller, and the change does not take effect until the machine reboots.

## What you will see

1. Open the gateway and sign in.
2. On a new gateway, the first-run list includes **Choose the PLC and ERIC resource split**. You can also open **PLC / ERIC resource split** under Platform.
3. The page lists each processor, whether it is a sibling of another thread, and how much memory the machine has.
4. The suggested PLC cores are one physical core on a small machine, and two when the machine has eight or more physical cores. The suggested PLC memory is 1 GB, or 2 GB when the machine has at least 16 GB.

## Choose the split

1. Set **PLC cores**. At least one core must stay with ERIC and the operating system.
2. Set **PLC memory**. At least 2 GB must stay for ERIC and the operating system.
3. Leave **Disable SMT siblings** on when the PLC cores have a paired thread. Those paired threads are kept off both the scan engine and ERIC, so they cannot interrupt the core.
4. Click **Preview**. The page shows the kernel arguments and the files that would be written. Nothing on the machine changes yet.

The arguments look like this: `isolcpus=managed_irq,domain,<cores> nohz_full=<cores> rcu_nocbs=<cores> irqaffinity=<the other cores>`.

## Apply, then reboot

Applying needs root. The gateway itself does not run as root.

1. Click **Apply**.
2. If a root helper is configured, it writes the files and the page says a reboot is required.
3. If no helper is configured, the page shows a `sudo` command. Run that command in a terminal on the gateway machine. The helper is `scripts/eric-plc-resources.sh`. Install it as `/usr/lib/eric/eric-plc-resources` and allow that one command through sudo. Do not allow the gateway program itself.
4. The page shows **Reboot required** until the running kernel command line matches the saved split. After the reboot it shows **applied**.

**Revert to default** restores the files that were there before the first apply and removes files this page created. That also needs the root helper, and it also needs a reboot.

The scan engine reads `/etc/eric/plc-resources.json` when its own configuration does not name a core, and it runs inside `eric-plc.slice` with permission to lock memory and to use a real-time priority.
