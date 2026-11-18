# Scripting

A script is a small program the gateway runs on a timer, or that a view runs when an operator acts. Scripts are for calculations and for sequences that do not belong in a PLC program. A loop that has to meet a cycle time belongs in Structured Text, not in a script.

## What you can do from the admin UI

1. Open the project.
2. Add a timer script. Give it a name and a delay in milliseconds.
3. The script body is JavaScript. It can read and write tags through the functions the runtime passes in.
4. Save. The timer starts when the project is running.
5. If the script throws, the gateway log names the script and the line. The timer keeps scheduling the next run.

A view can also run a script when a button is pressed or a value changes. That editor is in Control Canvas, on the component's events. See Events and scripts in the Control Canvas section.

## Power user

A timer resource is JSON. `delayMillis` is the pause between runs. `script` is the source.

```json
{
  "module": "org.oip.gateway",
  "scope": "gateway",
  "kind": "Timer",
  "name": "bench-tick",
  "apiVersion": 1,
  "spec": {
    "delayMillis": 2000,
    "script": "var v = readTag('Bench/Level'); writeTag('Bench/LevelEcho', v);"
  }
}
```

Put it with the configuration API the same way other gateway resources are stored. Reading a tag that does not exist returns a bad quality and does not stop the gateway.

Do not put a password in a script. Read it from the gateway's secret store, or do not store it at all.
