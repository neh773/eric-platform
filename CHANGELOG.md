# Changelog

## 0.1.1-beta

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

Everything a new user sets up can now be done in the admin page. No file imports, scripts or environment variables are needed.

### Fixed

- **A PLC program on an ordinary Windows or Linux PC could deploy, show "running", and never actually scan.** The gateway wrote a 10 ms period and left the engine's overrun latch at 3; scheduling jitter on a typical machine tripped the watchdog and held outputs at their safe values. A new program now uses a 100 ms period and 10 consecutive overruns (both still configurable in the Logic runtime pane). The status line says **Faulted: watchdog** or **Stopped:** with the engine error instead of staying on running, and **Start** relaunches the program. Mapping safe values are written as typed numbers, and the engine also accepts numeric strings such as `"0"`.
- **Linux app image had no admin pages, Control Canvas or manual.** The gateway image now bundles `admin/`, `web/` and `docs/` in `lib/app`, and the gateway finds them next to its jar with no environment variables. The image launcher starts the gateway when none is running and opens the admin page. Data goes to `~/.local/share/eric/data` (or `$XDG_DATA_HOME/eric/data`).
- **A fresh install had no way to sign in.** When the gateway has no users, `/admin` shows **Create administrator account** (username, password, confirm) instead of the sign-in form. It works only from a browser on the gateway machine (loopback, no proxy headers, same origin) and only until the first account exists; after that the route answers 409. `ERIC_ADMIN_PASSWORD` is still accepted for unattended installs. Applies to Linux and Windows.
- **Device values did not reach UDT members.** A UDT member bound to a device address (for example `{Device}` + `HRUS30`) is now subscribed to the driver, follows definition edits, and is released when the instance is removed.
- **Devices disappeared after a restart.** Config revalidation ran before a driver registered its schema and dropped the device. Kinds with no schema yet are now skipped until the driver registers.
- **Logic "running" did not run an engine on a default install.** The gateway now starts and supervises `eric-plc` itself: deploy compiles the program, starts the engine, links it to the tags, and the program resumes after a gateway restart.
- **Tag mapping rows could only be removed as JSON, and a row the program did not declare blocked deploy with an engine message.** Each row has **Remove**; deploy now says which address has no `AT` variable and how to fix it. A mapping that is still loading no longer replaces rows you already changed.
- **The Linux package had no Structured Text compiler or scan engine.** It now ships `eric-plc`, the pinned compiler with its standard library, and the process library in `lib/app/plc`. Deploy links the process-library blocks (`ERIC_PID` ...) into the program, and links it with the bundled LLVM linker `plc/llvm/ld.lld`, so Linux needs no gcc.
- **Restored tags had no value until a restart.** Restoring a backup from the admin page now announces the replaced configuration, so tags come back with values.
- **Turning on HTTPS needed a manual gateway restart.** Serve HTTPS on the Network page now starts (or stops) the HTTPS listener at once. HTTP port, address and redirect changes still apply on the next start, and the page says so.
- **HTTPS stopped working after a backup restore** (the TLS listener stayed stopped). The listener and its certificate watcher restart after the files are put back.
- **Plain HTTP on this machine showed an alarming session-cookie banner.** Plain HTTP from a browser on the gateway machine shows no warning; the Network page keeps a short note. Remote plain-HTTP access still warns.
- **Windows: the firewall rule for the web port stayed after stop and uninstall.** The gateway removes it on shutdown.
- **Health reported 0.1.0-SNAPSHOT on a release build.** The version now comes from the release tag and is written to the jar manifest.
- The gateway no longer prints `xdg-open` errors on a Linux machine without a desktop.
- New components in a coordinate view are placed on a free spot instead of 16 px over the previous one.
- Connections: the device list says when it is loading and when a device type could not be listed, instead of showing "No device connections yet". **New device** stays available while a device form is open.
- HTTP Endpoints: each endpoint's address is a link that opens it.
- A new, blank Control Canvas project no longer says "Unsaved style classes". **New program** with the name of an existing program opens it.
- The BACnet remote device form suggests the gateway's BACnet local devices and says when the named one does not exist.
- `GET /api/v1/db/connections/{name}` and `/db/queries/{name}` answer 404 instead of 500 for a missing item.
- Editing a tag-history entry on the Historian page no longer resets its sample rate, deadband and max time.
- Environment names: the gateway and its launcher accept `ERIC_*` names everywhere (`OIP_*` still works).

### Added

- **UDT Definitions** page (Services): create, edit and delete UDT types. Parameters; members of kind device address, memory, expression, reference, derived and nested UDT; scaling, engineering units and range, value deadband; alarms (condition, priority, setpoints, deadband, on-delay); history (provider, sample mode and rate, deadband, max time).
- History settings on a UDT member are collected for every instance and sent to the chosen provider. A tag-history entry on the Historian page for the same path wins.
- **Databases**: **New connection**, **Edit** and **Delete** forms for PostgreSQL, MySQL, MariaDB, SQL Server, SQLite and uploaded drivers. The password is write-only; saving also tests the connection.
- First-run endpoints `GET /api/v1/setup` and `POST /api/v1/setup/admin` (documented in the OpenAPI file).
- **Structures in Structured Text.** Gateway UDT definitions are ST types; `TYPE ... STRUCT`, and `VAR_IN_OUT` and `VAR_INPUT` structure parameters on function blocks, are supported. In Logic, **UDT instances** binds each structure variable to a gateway UDT instance from a list, with per-member direction (auto, in, out, none). The runtime pane shows each structure's members online. See the manual, PLC > Structures and gateway UDTs.
- **Backups: Restore from a backup file** uploads an `.oipbk` file, for example onto a fresh install on another machine. The sign-in page then says which backup was restored.
- **Tag alarms form** in the tag editor, **Alarming > Active alarms** with acknowledge, OPC UA and MQTT sign-in in the device form, and launch links on Home.
- **Required local check before every push and tag:** `scripts/check.sh` (Linux) and `scripts\check.ps1` (Windows) run every test, then the fresh-install test and the new-user UI suite (`web/e2e-ui`) on the built package. See CONTRIBUTING.md and the manual's release process.
- Tests: `StUdtEngineTest` (real compiler and engine, eight UDT instances), `UdtLogicTest`, `PlcLinkLinuxTest`; e2e `logic-udt`, `backup-upload`, `first-run` (/hmi with no projects); the fresh-install test deploys a program.
- Tests: `UdtDeviceMemberTest`, `DeviceRestartTest`, `FirstRunSetupTest`; e2e `first-run`, `udt-editor`, `db-connection-form`.

### Changed

- The history-provider form now starts at **SQL database** with table `oip_history`. The test-data provider is listed last.
- Release notes take the version from the tag (`{{version}}`).
- New Control Canvas projects start blank, without sample views.

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
