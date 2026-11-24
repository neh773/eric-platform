# What ERIC is and what it can do

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

ERIC (Engineered Runtime Interface & Control) is one gateway process. It talks to plant devices, holds tags, history and alarms, and serves operator screens in a browser. Control Canvas is the designer. The same sign-in covers admin, design and runtime.

## Gateway

The gateway is the server. Admin pages live at `/admin`. They cover devices, tags, history, alarms, users, projects, backups, diagnostics and deployment mode (edge, standard or hub).

Drivers that ship in the tree: Modbus, OPC UA client, OPC UA server, BACnet/IP, DNP3, Logix, MQTT (including Sparkplug), S7, a simulator, and UDP/TCP. A device is a config resource. The Connections page shows live status. There is no separate "new device" wizard; the [connections](gateway/connections.md) page shows the API call.

Tags are named values with quality. Memory tags, expression tags and device tags share one browser. History can stay on the embedded store or go to a database, with store-and-forward when the path is down. Alarms evaluate on tags, land in a journal, and can notify through a pipeline. Reports, named queries, transaction groups, charts (sequential function charts), email profiles and HTTP endpoints are gateway services.

## Screens

Control Canvas (`/studio/?project=<name>`) is where you draw views, bind them to tags, and save them into a project. The palette has display, input, chart, container, navigation and equipment symbols (pump, valve, motor, tank). Pipes, maps, trends and faceplates are included.

Operators open `/hmi/<project>/`. Values update live. Writes go through the same tag security as the API. A project can be marked public so a session opens without a password. That session can still read and write tags on that gateway. Leave it off unless you mean that.

## Soft-PLC

Structured Text runs in the scan engine. The editor is in the gateway and in Control Canvas. You check, compile and deploy a program, map variables to tags, and run tasks. On Linux the engine can be pinned to cores. On Windows the scan uses a timer and is not a real-time partition. The standard library and a process library (including PID) ship beside the compiler. The compiler is a separate program.

## How you install it

Windows and Linux installers, `oipctl` for start/stop/backup from a shell, a container image with compose recipes, and a Helm chart for one gateway with a persistent data disk. See [Install and uninstall](install.md).
