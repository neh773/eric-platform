# Windows UI audit of ERIC 0.1.0-beta

Audited on 22 November 2026 from the `v0.1.0-beta` Windows installers. The first pass, with no account, is summarized below. This pass signed in after a random password was written to `C:\eric\audit\secrets\admin.txt` and set on the installed service only, then the service was restarted. The password is not in this report. Feature checks used Playwright driving the installed Chrome browser. Configuration was done through the pages, not through API calls or file import, except the one backup file copied into a fresh data directory because the Backups page has no upload control.

The gateway is installed and running at the end.

## Result

**61 pass, 8 fail.** The 40 admin pages are counted one by one. The other 21 passes are the signed-in actions and the lifecycle checks in the table.

The missing first-run administrator screen stays a failure even though this pass could sign in. The password was applied to the service from outside the UI.

## Prioritized failures

1. **No first-run administrator screen.** The installer never asks for a password. The sign-in page has username, password, and Sign in. A new gateway cannot be used until something outside the UI sets `ERIC_ADMIN_PASSWORD` before the first start.
2. **A Modbus device does not come back in the UI after a restart.** `bench-tcp` was created, edited, connected to `192.168.68.56:1502`, and a tag read holding register `HR11` as 42. After a service restart the device file is still under the data directory, but Connections says "No device connections yet" and the tag quality is `Bad_NotConnected`. The same empty device list remains after uninstall and reinstall that keeps data. The memory tag and the project do survive.
3. **No way to create a UDT definition.** The UDT tab says no definitions exist and tells you to create one in Config. Config only lists resources that already exist. The instance wizard is hidden until a definition exists.
4. **No form to add an alarm to a tag.** Alarming can create, edit, and delete a roster, a pipeline, a schedule, and a contact. The tag editor shows an alarm summary and JSON, not an add-alarm form.
5. **No form for a database connection.** Databases lists connections and can test one, and it can upload a JDBC JAR. It has no fields for a host, port, or database. Postgres was not installed. Marked **FAIL: no UI**.
6. **The firewall rule survives stop and Add/Remove.** Inbound TCP 8090 stays allowed while the service is stopped and after `msiexec` has removed the programs. `uninstall.ps1` deletes the rule, but Add/Remove does not run that script.
7. **A fresh gateway cannot receive a backup through the UI.** Create, download, and restore work. Restore lists only archives already in the data directory. There is no upload control. Copying the `.oipbk` into that folder and then clicking Restore did bring `audit-plant` back.

## What passed once signed in

All 40 admin pages showed their headings: Home, Overview, Gateway Settings, PLC / ERIC resource split, Modules, Backups, Canvas sessions, Projects, Logs, Diagnostics, Metrics Dashboards, Store and Forward, Deployment, Users & Roles, API Keys, Identity Providers, User Sources, Security Zones, Security Levels, Security Settings, Secrets, Audit, OPC UA Certificates, Outbound TLS Certificates, OAuth2 Clients, Tags, Historian, Alarming, Event Streams, Email Profiles, Charts, Reports, HTTP Endpoints, Config, Connections, Databases, Network, Enterprise Admin, OPC UA Connections, Architectures.

| Feature | UI path | Result | Detail | Suspected cause |
| --- | --- | --- | --- | --- |
| First-run administrator | installer, `/admin/login` | FAIL | No create-account control. This pass signed in only after the service environment was set outside the UI. | The sign-in page is shared and does not offer a first account. The installer does not ask for one. |
| Sign in | `/admin/login` | PASS | The gateway shell opened for `admin`. | |
| Admin pages (40) | `/admin/<section>` | PASS | Each section heading rendered. No page-level error alert. | |
| Modbus device create, view, edit, delete | Connections → New device | PASS | `bench-tcp` at `192.168.68.56:1502`, unit id edited and set back. A second device was created and deleted. The row reported Connected. | |
| Modbus tag use | Tags → New tag, type opc, item `HR11` | PASS | `BenchRegister` showed 42 while the test server was running. | One-based `HR11` is protocol offset 10, where the server held 42. |
| Modbus device after restart | Connections, then Tags | FAIL | The device file is still on disk. The page says "No device connections yet". `BenchRegister` quality is `Bad_NotConnected`. Same after reinstall that keeps data. | The driver UI does not reload the saved device. The list call fails quietly and the wizard renders the empty state. |
| Memory tag | Tags → New tag | PASS | `BenchLevel` showed 7, including after restart and after reinstall that keeps data. | |
| UDT type | Tags → UDT tab, Config | FAIL | "No UDT definitions exist yet. Create one in Config (module tags, kind Udt)." Config has no New control. | A definition cannot be created from the UI. |
| UDT instance | Tags → Create UDT instances | FAIL | The wizard is not shown when there are no definitions. | Blocked by the missing type form. |
| Alarm roster | Alarming → New roster | PASS | Created, edited, and deleted `bench-roster`. | |
| Alarm on a tag | Alarming, Tags | FAIL | No "new alarm" form. The tag editor shows a summary and JSON. | Tag alarms are not a form. |
| Embedded historian | Historian → Record a tag | PASS | The page names the built-in `local` provider. Recording `[default]BenchLevel` saved. | |
| Postgres | Databases | FAIL | No connection form. Postgres was not installed. | FAIL: no UI. |
| User and role | Users & Roles | PASS | Role `bench` and user `benchop` were created and the display name was saved. The extra password is only in the secrets folder. | |
| Project | Projects | PASS | `audit-plant` created, Public HMI turned on. It was still there after restart and after reinstall that keeps data. | |
| Network form | Network → Web server | PASS | HTTP port shows 8090. Serve HTTPS and the redirect checkbox are on the form. | |
| HTTPS | Network → Save web server settings, then service restart | PASS | Port 8043, redirect left off. After restart, `https://127.0.0.1:8043` returned 200 and the Network page loaded in the browser. While the service was stopped, `server.key` could be renamed and renamed back. | The known "file stays locked after the listener stops" case was not hit: the whole process had exited. |
| Canvas components | `/designer?project=audit-plant` palette | PASS | All 77 palette entries were clicked onto a view. | |
| Tag binding | Inspector → Bind Value | PASS | A numeric component was bound to `[default]BenchLevel` and the view was saved. | |
| Popup | Events → onClick → Popup | PASS | A popup action was added with id `detail` and view `Main`. | |
| Save and `/hmi` | Save, then `/hmi/audit-plant/` | PASS | Save did not report a failure. The HMI URL rendered the project with Public HMI on. | |
| Trend | Palette → Add trend | PASS | The trend entry is in the 77 palette entries that were added. | |
| Structured Text | Control Canvas → Logic | PASS | Program `BenchScan` was created, checked, compiled and deployed, and Start reported a running status. | Windows soft-PLC. The new program is an empty `PROGRAM` block. |
| Script | Control Canvas console | PASS | `print(6 * 7)` showed 42. | |
| Backup create | Backups | PASS | An encrypted `.oipbk` was created. The passphrase is only in the secrets folder. | |
| Backup restore | Backups → Confirm restore | PASS | Restore on the original gateway was not finished before the device-list check. On a fresh data directory, after the archive was copied into `backups` because the page cannot upload, Confirm restore reported restored and `audit-plant` was listed again. | |
| Upload a backup onto a fresh install | Backups | FAIL | The page has create, download, and restore. It has no upload or file picker. | Restore only sees files already in the data directory. |
| Service restart, stop/start, and process kill | service control | PASS | Health returned each time, including HTTPS after it was enabled. A hard kill of both gateway processes came back on its own within 8 seconds. | |
| Reinstall keeping data | Add/Remove, then the same installers | PASS | Programs were removed, `C:\ProgramData\ERIC` stayed, reinstall came up, and sign-in, `audit-plant`, and both tags were still there. | |
| Firewall rule removed when the service stops or is uninstalled | `Stop-Service`, then `msiexec /x` | FAIL | The TCP 8090 allow rule was still present while stopped and after the programs were gone. | The shipped process adds the rule and does not delete it. Add/Remove does not run `uninstall.ps1`. |

## Install notes that still apply

The installers match the published release hashes. Both complete with exit 0. Shortcuts open `/admin` and `/designer`. The manual at `/docs` loads without signing in. Add/Remove shows version 0.1.0 while health reports `0.1.0-SNAPSHOT`. The service environment on the release build still sets the deprecated `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `OIP_DOCS_DIR` names. A laptop reboot was not done because a desktop session is signed in.

## Fixes already on this branch

Not in the running 0.1.0-beta installers: the service fragment uses the `ERIC_` path names, captures the console log, waits 15 seconds on stop, and the process deletes its firewall rule on shutdown. `smoke.ps1`, `LayoutTest`, and `WindowsFirewallTest` cover that. The first-run screen is not in this branch.
