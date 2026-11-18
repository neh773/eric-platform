---
title: PLC runtime (preview)
sidebar_label: PLC runtime
---

# PLC runtime (preview)

:::caution PLC on Windows
This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel.
:::

This page is the preview of a controller that runs one Structured Text program. It is not a safety controller, and it is not SIL.

The program runs in a separate process, `eric-plc`. The gateway keeps the tags. A bridge copies tag values into the controller and writes controller outputs back onto tags.

## What the example does

The example program is a motor seal-in:

- **Start** has to stay on for 200 ms before the motor seals in.
- **Stop** drops the motor immediately.
- A raw analog count, 0 to 32767, is scaled to 0 to 1000.

The 200 ms wait uses the standard on-delay timer from the compiler's library.

## Build the program

The Structured Text compiler is an external tool. The build script downloads a pinned release and does not copy the compiler source into this repository.

From a checkout:

```bash
plc/eric-plc/scripts/build-st.sh
cargo build --release --manifest-path plc/eric-plc/Cargo.toml --locked
```

## Point the gateway at the image

Add this block to `gateway.yaml`. Create the tags named in the mapping first. They are ordinary memory tags, or tags that already come from a device.

```yaml
plc:
  enabled: true
  image: /dev/shm/eric-plc-sealin
  mapping: plc/eric-plc/examples/sealin/mapping.json
  publishMillis: 10
  bridgeTimeoutMillis: 100
```

The mapping file lists each Structured Text address, the tag path, the type, and the safe value for an output:

```json
{
  "inputs": [
    {"address": "%IX0.0", "tag": "plc/start", "type": "BOOL"}
  ],
  "outputs": [
    {"address": "%QX1.0", "tag": "plc/motor", "type": "BOOL", "safe": false}
  ]
}
```

Supported types are BOOL, INT, DINT, REAL, and LREAL.

## Run the scan

```bash
eric-plc sim --config plc/eric-plc/examples/sealin/plc.toml --duration-ms 600000 &
eric-plc run --config plc/eric-plc/examples/sealin/plc.toml --duration-ms 600000
eric-plc stats --status plc/eric-plc/examples/sealin/build/status.json
eric-plc report --status plc/eric-plc/examples/sealin/build/status.json --out report.md
```

`sim` stands in for the gateway and writes changing inputs. With the gateway bridge running, skip `sim`.

## When something goes wrong

- If a scan is still running when the next scan is due, that scan is an overrun. The engine does not try to catch up.
- Three overruns in a row (the default) stop the program, write every output to its safe value, and latch a fault until the process exits.
- If the bridge goes quiet, output tags are set to their safe values and the quality goes bad. The program is not stopped by a bridge loss. A later heartbeat resumes normal outputs.
- Stopping the process also writes the safe outputs.

Values are not kept across a restart in this preview.

## A number you can trust

A run on a container or a cloud VM is labeled **container-size, not reference**. The reference run is a Protectli-class machine with a PREEMPT_RT kernel and an isolated core:

```bash
scripts/plc_reference_run.sh 1 600
```

`1` is the isolated core. `600` is the number of seconds.
