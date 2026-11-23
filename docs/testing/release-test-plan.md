# Release test plan

Every contributor and every agent runs this plan before pushing to `main` and before tagging a release. It has two
halves: the automated gates, which a script runs, and a hands-on new-user pass, which a person or an agent does with a
real mouse and keyboard on fresh installs of **both Linux and Windows**. A push or release that skips either half is not
ready.

ERIC is beta software. Test it with test hardware or simulators in safe conditions, never on a live process.

## 1. Automated gates

Run the full local check from the repository root. Push only when it ends with `check PASSED`.

| Platform | Command |
| --- | --- |
| Linux | `scripts/check.sh` |
| Windows | `scripts\check.ps1` (from an elevated PowerShell) |

The check stops at the first failing step and prints the log folder. In order, it runs:

| Gate | What it covers | Run it alone |
| --- | --- | --- |
| Gradle build and JVM tests | Gateway, drivers, backup, security, tags, logic service | `./gradlew build --no-daemon` |
| `eric-plc` tests | Scan engine, including force and write semantics | `cargo test --manifest-path plc/eric-plc/Cargo.toml --locked` |
| Web lint | ESLint over every web package | `cd web && corepack pnpm lint` |
| Web typecheck | TypeScript, every package | `cd web && corepack pnpm typecheck` |
| Web unit tests | Vitest, admin, Control Canvas, components | `cd web && corepack pnpm test` |
| Web end-to-end | Playwright against a built gateway, including Logic online mode | `cd web && corepack pnpm test:e2e` |
| Fresh-install test | Unpacks the built package into an empty folder, creates the first administrator, opens every page, deploys a PLC program | Linux: `FRESH_PORT=8196 FRESH_GAN_PORT=0 packaging/linux/fresh-install-test.sh` |
| Every-feature UI suite | The new-user path on the built package, forms only | `cd web && ERIC_UI_GATEWAY="<package>/bin/ERIC Gateway" corepack pnpm test:ui` |

Docker: the MQTT broker, Sparkplug and mail delivery tests (`MqttBrokerTest`, `SparkplugEdgeBrokerTest`,
`MqttGatewayTest`, the MQTT case of `CommsLossQualityTest`, `MailpitNotificationTest`) start containers. On a machine
without Docker they are reported as skipped, not failed. Before a release, run the Gradle gate once on a machine with
Docker (the GitHub runners have it) so they actually run.

Port notes: the check uses its own ports. If another gateway is running on the same machine, set
`ERIC_UI_PORT`, `ERIC_UI_OPCUA_PORT`, `ERIC_UI_HTTPS_PORT` and `FRESH_PORT` to free ports instead of stopping it.

## 2. Hands-on new-user pass (Linux and Windows)

Do this on a machine, or a clean user account, that has never had ERIC installed. Use only the browser: the admin
pages (`/admin`) and Control Canvas (`/studio`). Record a result for every row in the [matrix](#5-result-matrix).

1. **Download and install.** Download the installer for the release under test. Install it with the defaults.
2. **First administrator.** Open the gateway. The first screen asks you to create the administrator account. Create it
   and sign in.
3. **Devices.** Add one device of each type from **Devices > New device**: Modbus TCP, OPC UA, BACnet/IP, DNP3, S7,
   EtherNet/IP, MQTT. Use a test server or simulator. Check each shows connected, then edit and delete one.
4. **Tags.** Create memory, device and expression tags. Set scaling, engineering units and deadband. Add alarms on a tag
   (high, high-high, digital). Write a value and see it change.
5. **UDT definitions and instances.** Build a UDT with members, alarms and history from **UDT Definitions**. Create
   instances, change a parameter, and check the live values.
6. **Database connection and historian.** Add a database connection from its form. Turn on history for a tag. Check
   rows arrive and a trend shows them.
7. **Control Canvas.** Create a project and a view. Place components, bind them to tags and UDT members, add a popup,
   add a trend, publish, and open the published screen in the HMI. Set the home view.
8. **PLC (Structured Text).** In **Logic**: create a program, map variables to tags with their data types, check it,
   compile and deploy, then:
   - **Go online.** Every variable shows its live value; BOOLs show TRUE in green and FALSE in grey.
   - **Write once.** Write a value from the right-click menu or the watch table. The next scan may overwrite it.
   - **Force.** Force a BOOL TRUE and a number to a value. The F marker and the "N values forced" banner show. The
     tag, the field device register and the HMI follow the forced value every scan.
   - **Release.** Release one force, then **Release all**. Real values return.
   - Check the forces and releases are in the audit log, and that Stop and a redeploy release forces.
   - Check a viewer or operator account is refused a force or a write.
9. **Alarms, pipelines and notifications.** Set up an email or other notification profile, build a pipeline, link an
   alarm to it from the alarm form, trigger the alarm, and check the notification arrives. Acknowledge and clear it.
10. **Users and roles.** Create an operator and a viewer. Sign in as each. Check the navigation matches the role, a
    viewer's HMI button press is refused with a visible message, and the refused write is in the audit log.
11. **HTTPS and network.** Turn HTTPS on and off from **Network**. Check the page state and the certificate name are
    right, and the change applies without a restart.
12. **Scripts.** Create a gateway timer script and a tag change script from their forms. Check they run.
13. **Backup.** Download a backup file from **Backup**.
14. **Restore on a fresh install.** Install ERIC on a second clean machine or folder, upload the backup file, and
    check devices, tags, UDTs, projects, PLC programs and the tag map, users and history settings all came back and
    work.

### 2W. Clean Windows VM install, no developer tools (required for every Windows release)

The developer laptop and the CI runner both have Java, Visual C++ runtimes and build tools, which hide missing
dependencies. Before a Windows release, also install on a **clean Windows 10 or 11 x64 virtual machine** made from a
fresh evaluation image: no Java, no Visual C++ redistributables, no Visual Studio, no Node, no Python, no Git, and a
standard Windows Defender/SmartScreen setup. Take a snapshot first so the pass can be repeated.

1. Download the installer in the VM's browser from the release page (so it carries the downloaded-file mark), and
   double-click it. Record the SmartScreen prompt and that **More info > Run anyway** gets past it.
2. Approve the administrator prompt and finish with the defaults. If anything fails, rerun with
   `/L*v "%USERPROFILE%\Desktop\eric-install.log"` and attach that log.
3. The installer's last page must show `http://localhost:8090/admin`, the **ERIC Admin** shortcut name and the install
   log path, with **Open the admin page now** ticked. Finish: the browser must open the "Create administrator account"
   screen at `http://localhost:8090/admin` within a minute, over plain HTTP with no certificate warning. If it does not,
   the ERIC Admin window must say what to check. Record what the VM's browser shows.
4. Check `C:\ProgramData\ERIC\logs\install.log` exists after this successful install.
5. Close the browser and open **ERIC Admin** from the Start menu: the admin page opens again.
6. From the VM host or another computer on the same network (Windows Home, network set to Public), open
   `http://<vm address>:8090/admin`: the sign-in page loads (the firewall rule covers the Public profile).
7. Run `powershell -ExecutionPolicy Bypass -File "C:\Program Files\ERIC Gateway\app\check-install.ps1"` as
   administrator. It must report no FIX lines. Attach `C:\ProgramData\ERIC\check-install.txt`.
8. Do steps 2.2 to 2.7 above in that VM. For 2.8, **Compile and deploy** must work with no Visual Studio or Build
   Tools installed: the program runs, watched values update, and a mapped output writes its tag.
9. Do a silent install on a second clean snapshot from an administrator Command Prompt:
   `"<installer>.exe" /qn /norestart /L*v "C:\ERIC-install.log"`. Exit code 0 or 3010; the service runs; the log is there.
10. Uninstall from Add/Remove Programs. No service, firewall rule or Program Files folder is left.

## 3. Lifecycle

On each platform, after the new-user pass:

| Step | Check |
| --- | --- |
| Restart button | **Gateway > Restart** in admin. The gateway comes back; the PLC program is running; devices reconnect. |
| Service stop and start | Stop and start the service (`systemctl` on Linux, Services on Windows). Same checks. |
| Crash | Kill the gateway process (`kill -9` on Linux, End task on Windows). The service restarts it; nothing is lost. |
| Reinstall keeping data | Reinstall the same version over the top. Configuration, projects and PLC programs are kept. |
| Uninstall | Uninstall. The service, the firewall rules and the program folder are gone. Data is kept or removed as the uninstaller says. |

## 4. Forms-only rule

Every feature must be doable from the admin pages or Control Canvas forms. A feature that can only be reached through a
file import, an API call or a script is a **FAIL** for that row, even when it works. Record it as a product gap in the
matrix and open an issue.

Automated tests may use the API to set up data that another test already covers through the form. The test plan rows
themselves may not.

## 5. Result matrix

Copy this table into the pull request or release notes, one copy per platform, and fill every row. Evidence is a
screenshot, a short recording, or a log path.

```markdown
Platform: Linux / Windows    Build: <version or commit>    Tester: <name or agent>    Date: <date>

| # | Feature | Steps | Result (PASS/FAIL) | Evidence |
| --- | --- | --- | --- | --- |
| A1 | Automated gates | scripts/check.sh or check.ps1 | | log folder |
| 1 | Download and install | 2.1 | | |
| W | Clean Windows VM (no dev tools): SmartScreen, finish page opens admin, ERIC Admin shortcut, install.log kept, remote access on Public network, check-install, silent install, uninstall | 2W | | install.log, check-install.txt |
| 2 | First administrator | 2.2 | | |
| 3 | Devices (each type) | 2.3 | | |
| 4 | Tags, scaling, alarms | 2.4 | | |
| 5 | UDT definitions and instances | 2.5 | | |
| 6 | Database connection and historian | 2.6 | | |
| 7 | Control Canvas build, bind, popups, trends, publish | 2.7 | | |
| 8 | PLC create, check, deploy, online, write, force, release | 2.8 | | |
| 9 | Alarms, pipelines, notifications | 2.9 | | |
| 10 | Users and roles | 2.10 | | |
| 11 | HTTPS and network | 2.11 | | |
| 12 | Scripts | 2.12 | | |
| 13 | Backup to file | 2.13 | | |
| 14 | Restore on a fresh install | 2.14 | | |
| L1 | Restart button | 3 | | |
| L2 | Service stop and start | 3 | | |
| L3 | Crash | 3 | | |
| L4 | Reinstall keeping data | 3 | | |
| L5 | Uninstall cleanup | 3 | | |
| G | Forms-only gaps found | 4 | | issues |
```
