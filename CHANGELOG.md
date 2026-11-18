# Changelog

## 0.1.0-beta

**ERIC v0.1.0 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

First soft release for controls engineers. Not for a safety function. Not a real-time controller on Windows. The Windows soft-PLC banner is unchanged and stays up while that PLC is running.

### Gateway

- Projects, tag providers, user-defined types, Modbus and OPC UA connections, historian, alarms, users, backups.
- Public HMI is a per-project setting, off by default. When it is on, that project's HMI can be opened without a sign-in. The admin UI stays protected.
- Example projects are optional. They are imported from the admin Projects page, from Control Canvas, or from a script. They are not created by the installer.
- The manual is served at `/docs`.

### Control Canvas and the HMI

- Views, pages, bindings, scripts on events, trends, and a map component.
- Pipes route onto named ports on equipment symbols.
- Faceplates are ordinary views opened as popups from a symbol.
- A page address can fill `{name}` in a title and in a trend pen.

### PLC

- Structured Text editor, check, and deploy through the bundled RuSTy compiler.
- Standard library and a process library that includes a PID block.
- A timing and partition wizard.
- On Windows the soft-PLC runs as a normal process. A banner stays up while it is running. It is not real-time.

### Known limits

See TESTING.md. The short list: Windows soft-PLC is not real-time, Public HMI can read and write tags on that gateway, example projects are not in the default install, and RuSTy is a separate LGPL program.

### Known issues in 0.1.0-beta

These are tracked as GitHub issues labelled `known-issue`.

- **Windows: HTTPS certificate stays locked after the TLS listener stops.** A backup restore or wipe that touches `https/` can fail on Windows until the gateway restarts.
- **Windows: large tag imports are slow.** Writing 100,000 tags in one batch took about 75 seconds on a 4-core test laptop against a 20-second target.
- **MQTT high-point bench test needs a non-loopback network interface.** The bench simulator exits on machines with only a loopback IPv4 address. This affects the test, not the MQTT driver.
