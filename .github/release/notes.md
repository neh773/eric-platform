> [!WARNING]
> **ERIC 0.1.0-beta is beta software.** Use it only with test hardware, under safe conditions. Do not use it for live production, life-safety, or equipment-protection systems. It is provided AS IS, with no warranty (AGPL-3.0).

ERIC is an open-source SCADA and HMI platform with a built-in soft PLC. The PLC and the HMI share one tag database and historian.

## Downloads

| File | What it is |
| --- | --- |
| `ERIC-Gateway-0.1.0-beta-windows-x64.exe` | Windows gateway installer: service, admin UI, HMI, historian, alarms, and the `eric-plc` soft PLC with the Structured Text compiler. No system Java needed. |
| `Control-Canvas-0.1.0-beta-windows-x64.exe` | Windows installer for the Control Canvas screen designer shortcut. Install the gateway first. |
| `ERIC-0.1.0-beta-linux-x64-app-images.tar.gz` | Linux app images (gateway and Control Canvas) built with jpackage. Unpack and run `bin/` from each folder. Not a package-manager installer. |

## Getting started (Windows)

1. Run `ERIC-Gateway-0.1.0-beta-windows-x64.exe` and accept the beta and license screen. The `ERIC Gateway` service starts on its own.
2. Open <http://127.0.0.1:8090/admin> for the admin UI, or run `Control-Canvas-0.1.0-beta-windows-x64.exe` and use its shortcut to open the designer at <http://127.0.0.1:8090/designer>.
3. To remove it, use Add/Remove Programs. Your projects in `C:\ProgramData\ERIC` are kept.

Install guide: [docs/install/windows.md](https://github.com/neh773/eric-platform/blob/main/docs/install/windows.md). Manual: [docs/manual](https://github.com/neh773/eric-platform/tree/main/docs/manual).

## Known issues

- #1 Windows: HTTPS certificate stays locked after the TLS listener stops
- #2 Windows: 100,000-tag batch write takes ~75 s (budget 20 s)
- #3 MQTT high-point bench test needs a non-loopback IPv4 interface

## Feedback

This is a community project. Please report bugs in [Issues](https://github.com/neh773/eric-platform/issues) and share ideas and questions in [Discussions](https://github.com/neh773/eric-platform/discussions).
