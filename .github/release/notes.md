> [!WARNING]
> **ERIC {{version}} is beta software.** Use it only with test hardware, under safe conditions. Do not use it for live production, life-safety, or equipment-protection systems. It is provided AS IS, with no warranty (AGPL-3.0).

ERIC is an open-source SCADA and HMI platform with a built-in soft PLC. The PLC and the HMI share one tag database and historian.

## Downloads

| File | What it is |
| --- | --- |
| `ERIC-Gateway-{{version}}-windows-x64.exe` | Windows gateway installer: service, admin UI, HMI, historian, alarms, and the `eric-plc` soft PLC with the Structured Text compiler. No system Java needed. |
| `Control-Canvas-{{version}}-windows-x64.exe` | Windows installer for the Control Canvas screen designer shortcut. Install the gateway first. |
| `ERIC-{{version}}-linux-x64-app-images.tar.gz` | Linux app images (gateway and Control Canvas) built with jpackage. Unpack and run `bin/` from each folder. Not a package-manager installer. |

## What changed in 0.1.1-beta

- **Linux archive works without setup.** The gateway app image now contains the admin pages, Control Canvas and the manual, and finds them with no environment variables. Running `bin/ERIC Gateway` starts the gateway and opens the admin page.
- **First-run administrator form.** A new gateway with no users shows **Create the administrator** on the sign-in page, only to a browser on the gateway machine.
- **Database connections from the admin page.** Add, edit, test and delete PostgreSQL, MySQL, MariaDB, SQL Server and SQLite connections in a form. The password is write-only.
- **UDT Definitions page.** Create, edit and delete UDT types in a form: parameters, members (device address, memory, expression, reference, derived, nested UDT), scaling, engineering units and range, alarms, and history.
- **Device values reach UDT members.** A UDT member bound to a Modbus or OPC UA address now reads the device directly.
- **Devices survive a restart.** Modbus and OPC UA device connections are kept when the gateway restarts.
- **History from a UDT member** goes to the provider chosen in the definition. The history-provider form now defaults to an SQL database.

Full list: [CHANGELOG.md](https://github.com/neh773/eric-platform/blob/main/CHANGELOG.md).

## Getting started (Linux)

1. `tar -xzf ERIC-{{version}}-linux-x64-app-images.tar.gz`
2. Run `"image/ERIC Gateway/bin/ERIC Gateway"`. Your browser opens the admin page; create the administrator there.
3. Data is kept in `~/.local/share/eric/data`.

## Getting started (Windows)

1. Run `ERIC-Gateway-{{version}}-windows-x64.exe` and accept the beta and license screen. The `ERIC Gateway` service starts on its own.
2. Open <http://127.0.0.1:8090/admin> for the admin UI, or run `Control-Canvas-{{version}}-windows-x64.exe` and use its shortcut to open the designer at <http://127.0.0.1:8090/designer>.
3. To remove it, use Add/Remove Programs. Your projects in `C:\ProgramData\ERIC` are kept.

Install guide: [docs/install/windows.md](https://github.com/neh773/eric-platform/blob/main/docs/install/windows.md). Manual: [docs/manual](https://github.com/neh773/eric-platform/tree/main/docs/manual).

## Known issues

- #1 Windows: HTTPS certificate stays locked after the TLS listener stops
- #2 Windows: 100,000-tag batch write takes ~75 s (budget 20 s)
- #3 MQTT high-point bench test needs a non-loopback IPv4 interface

## Feedback

This is a community project. Please report bugs in [Issues](https://github.com/neh773/eric-platform/issues) and share ideas and questions in [Discussions](https://github.com/neh773/eric-platform/discussions).
