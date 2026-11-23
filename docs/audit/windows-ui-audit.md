# Windows UI audit of ERIC 0.1.0-beta

Audited on 22 November 2026 on a Windows laptop, as a new user of the public release. The installers were `ERIC-Gateway-0.1.0-beta-windows-x64.exe` and `Control-Canvas-0.1.0-beta-windows-x64.exe` from the `v0.1.0-beta` release. Both file hashes matched the published release.

The previous install was stopped, copied from `C:\ProgramData\ERIC` to `C:\eric\old-data\ERIC`, and removed, including program files and `C:\ProgramData\ERIC`. The gateway was then installed, then Control Canvas.

Feature checks used Playwright driving the installed Chrome browser. Nothing was configured through the API, a file import, a script, or an environment variable. The installers were run with `/qn` so the wizard did not take over the signed-in desktop. The license screen a person would see is the beta warning followed by the license text installed with the product.

At the end of the audit the ERIC Gateway service is installed, set to start automatically, and running. Health is UP on `http://127.0.0.1:8090`.

## Result

**13 pass, 65 fail.** One check was skipped: a full laptop reboot, because a desktop session is signed in. Service restart, stop/start, and a hard kill of the gateway process were used instead.

Forty of the failures are admin pages that stop at the same wall: the page loads the sign-in form, and a fresh install has no account. The configuration features behind that form were not reachable, so they fail for that reason rather than from a separate click-through.

## Top failures

1. **No administrator can be created or used.** The installer never asks for a password and does not set `ERIC_ADMIN_PASSWORD`. The service environment has no password. The data directory has the built-in roles and no user. The sign-in page has username, password, and Sign in, and nothing else. A sign-in attempt is answered with `invalid credentials`. The process does print `ERIC gateway has no users. Set ERIC_ADMIN_PASSWORD to seed an administrator on first start.` That line goes to the service console, which the shipped service does not capture, so a person never sees it. The manual at `/docs/manual/getting-started-testers` says to sign in with the administrator account created at install, and that there is no built-in default password.
2. **Every admin page and all of Control Canvas stop at that sign-in.** Devices, tags, UDT types and instances, alarms, historian, trends, users, roles, security, HTTPS, projects, backup and restore, Structured Text, scripts, network settings, screen components, bindings, popups, publish, and `/hmi` were not reachable.
3. **Add/Remove Programs leaves the firewall rule behind.** While the service is running, an inbound allow rule named `ERIC Gateway` opens TCP 8090 on all profiles. Stopping the service leaves the rule. Uninstalling with the program entry (`msiexec /x`, no quiet-uninstall command of our own) removes the service and Program Files and keeps `C:\ProgramData\ERIC`, and the firewall rule is still there. `packaging/windows/uninstall.ps1` deletes the rule, but Add/Remove Programs does not run that script, and it does not show the checkbox for deleting `C:\ProgramData\ERIC`.
4. **The shipped service still uses the old environment names.** Startup warns that `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `OIP_DOCS_DIR` are deprecated. The current names are `ERIC_ADMIN_DIST`, `ERIC_WEB_DIST`, and `ERIC_DOCS_DIR`. The PLC compiler path is already `ERIC_PLC_COMPILER`.
5. **The running build does not say 0.1.0-beta.** Add/Remove Programs shows version 0.1.0. The health payload says `0.1.0-SNAPSHOT`. The page banner says ERIC v0.1.0 is beta software.

## Install friction

- Two downloads. The gateway installer is about 309 MB. The repository install notes use different file names (`ERIC Gateway-0.1.0.exe`) than the release assets.
- Add/Remove Programs shows 0.1.0, not 0.1.0-beta.
- The installer does not ask for an administrator password.
- Control Canvas is a second installer. Its shortcut only opens `http://127.0.0.1:8090/designer`. The designer is served by the gateway.
- A folder-cleanup step in the gateway installer returned 1603 and was ignored. The install still finished with exit 0.
- The machine already had a reboot pending before this install. This audit did not reboot it.
- Example projects are not installed. The manual's import path needs an environment variable and a file import.
- The sign-in page warns that session cookies are not marked Secure, and points at a document path instead of a control that turns HTTPS on.

## Lifecycle

| Check | Result | What happened |
| --- | --- | --- |
| Restart the service | PASS | Health returned in a few seconds. `gateway.yaml` was unchanged. The three built-in roles were still there. No user directory appeared. |
| Stop, then start | PASS | While stopped, port 8090 did not answer. After start, health was UP. |
| Hard kill of both `ERIC Gateway` processes | PASS | Within 8 seconds the service was running again and health was UP, with a new start time. `gateway.yaml` was unchanged. Recovery actions on the service are empty; the service manager restarted the application. |
| Laptop reboot | Skipped | A desktop session is signed in. |
| Upgrade by running the same gateway installer again | PASS | Exit 0. Service running. `gateway.yaml` unchanged. |
| Uninstall from Add/Remove, keep data, reinstall | PASS for data | Programs, service, and Start menu were removed. `C:\ProgramData\ERIC` remained, including a marker file written for this check. Reinstall brought the service back and the marker and `gateway.yaml` were still there. The firewall rule was present the whole time, including while the product was uninstalled. |
| Create a backup in the UI, uninstall, restore onto a fresh data directory | FAIL | The Backups page never opened. |

Postgres is not installed (no service and no `psql`). A local Modbus TCP server was bound to the Wi-Fi address `192.168.68.56` port 1502 with pymodbus, and a client read holding register 10 as 42. That server was stopped. The Connections page could not be used to point ERIC at it.

## Matrix

| Feature | UI path | Result | Detail | Suspected cause |
| --- | --- | --- | --- | --- |
| Gateway installer | release `ERIC-Gateway-0.1.0-beta-windows-x64.exe` | PASS | Exit 0. Service `ERIC Gateway` is Automatic and Running. Files under `C:\Program Files\ERIC Gateway`. | |
| Control Canvas installer | release `Control-Canvas-0.1.0-beta-windows-x64.exe` | PASS | Exit 0. Files under `C:\Program Files\Control Canvas`. | |
| Start menu and desktop shortcuts | Start menu `ERIC`, desktop | PASS | Both shortcuts exist after install. | |
| Gateway shortcut target | `ERIC Gateway.exe` with the admin print flag | PASS | Prints `http://127.0.0.1:8090/admin` and exits 0. Does not start a second gateway. | |
| Control Canvas shortcut target | `Control Canvas.exe --print-only` | PASS | Prints `http://127.0.0.1:8090/designer` and exits 0. | |
| Open the admin UI | `/` and `/admin/login` | PASS | `/` lands on `/admin/home`, which shows the beta banner, Gateway UP, and Sign in. | |
| Manual | `/docs` | PASS | The manual home loads without signing in. | |
| Firewall rule while the service is up | service start | PASS | Inbound allow, TCP, local port 8090, all profiles. | The process adds the rule after it is listening. The installer does not, because that stalled a silent install. |
| Create the first administrator | installer, then `/admin/login` | FAIL | No password prompt. No user records on disk. The form has no create-account control. | `ERIC_ADMIN_PASSWORD` is the only seed, and only when it is set before the first start. The Windows service never sets it. |
| Sign in | `/admin/login` | FAIL | Stays on Sign in. Alert text: `invalid credentials`. | There is no account. The form does not say that the account was never created. |
| Manual instructions for the first password | `/docs/manual/getting-started-testers` | FAIL | Step 4 says to sign in with the administrator account created at install, and that there is no built-in default password. | The installer does not create that account, and the page does not say how to. |
| Home | `/admin/home` | FAIL | Sign-in wall. The shell returns HTTP 200, then the page is the sign-in form. | No account exists. |
| Overview | `/admin/overview` | FAIL | Sign-in wall. | No account exists. |
| Gateway Settings | `/admin/gateway-settings` | FAIL | Sign-in wall. | No account exists. |
| PLC / ERIC resource split | `/admin/plc-resources` | FAIL | Sign-in wall. | No account exists. |
| Modules | `/admin/modules` | FAIL | Sign-in wall. | No account exists. |
| Backups | `/admin/backups` | FAIL | Sign-in wall. | No account exists. |
| Canvas sessions | `/admin/designer-sessions` | FAIL | Sign-in wall. | No account exists. |
| Projects | `/admin/projects` | FAIL | Sign-in wall. | No account exists. |
| Logs | `/admin/logs` | FAIL | Sign-in wall. | No account exists. |
| Diagnostics | `/admin/diagnostics` | FAIL | Sign-in wall. | No account exists. |
| Metrics Dashboards | `/admin/metrics` | FAIL | Sign-in wall. | No account exists. |
| Store and Forward | `/admin/store-and-forward` | FAIL | Sign-in wall. | No account exists. |
| Deployment | `/admin/deployment` | FAIL | Sign-in wall. The installed bundle's deployment text tells a signed-in person that `ERIC_ADMIN_PASSWORD` seeds the administrator when no users exist. | That page is behind the account it describes. |
| Users & Roles | `/admin/users` | FAIL | Sign-in wall. | No account exists. |
| API Keys | `/admin/api-keys` | FAIL | Sign-in wall. | No account exists. |
| Identity Providers | `/admin/identity-providers` | FAIL | Sign-in wall. | No account exists. |
| User Sources | `/admin/user-sources` | FAIL | Sign-in wall. | No account exists. |
| Security Zones | `/admin/zones` | FAIL | Sign-in wall. | No account exists. |
| Security Levels | `/admin/security-levels` | FAIL | Sign-in wall. | No account exists. |
| Security Settings | `/admin/security-settings` | FAIL | Sign-in wall. | No account exists. |
| Secrets | `/admin/secrets` | FAIL | Sign-in wall. | No account exists. |
| Audit | `/admin/audit` | FAIL | Sign-in wall. | No account exists. |
| OPC UA Certificates | `/admin/opcua` | FAIL | Sign-in wall. OPC UA itself is listening on `127.0.0.1:4840`. | No account exists. |
| Outbound TLS Certificates | `/admin/outbound-tls` | FAIL | Sign-in wall. | No account exists. |
| OAuth2 Clients | `/admin/oauth2-clients` | FAIL | Sign-in wall. | No account exists. |
| Tags | `/admin/tags` | FAIL | Sign-in wall. | No account exists. |
| Historian | `/admin/historian` | FAIL | Sign-in wall. | No account exists. |
| Alarming | `/admin/alarming` | FAIL | Sign-in wall. | No account exists. |
| Event Streams | `/admin/event-streams` | FAIL | Sign-in wall. | No account exists. |
| Email Profiles | `/admin/email-profiles` | FAIL | Sign-in wall. | No account exists. |
| Charts | `/admin/charts` | FAIL | Sign-in wall. | No account exists. |
| Reports | `/admin/reports` | FAIL | Sign-in wall. | No account exists. |
| HTTP Endpoints | `/admin/http-endpoints` | FAIL | Sign-in wall. | No account exists. |
| Config | `/admin/config` | FAIL | Sign-in wall. | No account exists. |
| Connections | `/admin/connections` | FAIL | Sign-in wall. | No account exists. |
| Databases | `/admin/databases` | FAIL | Sign-in wall. | No account exists. |
| Network | `/admin/network` | FAIL | Sign-in wall. | No account exists. |
| Enterprise Admin | `/admin/enterprise-admin` | FAIL | Sign-in wall. | No account exists. |
| OPC UA Connections | `/admin/opc-connections` | FAIL | Sign-in wall. | No account exists. |
| Architectures | `/admin/architectures` | FAIL | Sign-in wall. | No account exists. |
| Control Canvas | `/designer` | FAIL | Beta banner, then "Sign in to open project default." No palette, no logic editor, no project list. | Same missing account. The shortcut only opens this URL. |
| Operator view | `/hmi`, `/hmi/`, `/hmi/default/` | FAIL | Each URL ends on a sign-in form. Nothing was published. | No project could be created. |
| Modbus TCP device create, edit, delete, and use | Connections, New device | FAIL | Not reached. A server on `192.168.68.56:1502` answered a holding-register read, then was stopped. | The device form is behind sign-in. |
| Tags create, view, edit, delete | Tags, New tag | FAIL | Not reached. | Behind sign-in. |
| UDT type and instance | Tags | FAIL | Not reached. | Behind sign-in. |
| Alarms create, edit, delete | Alarming | FAIL | Not reached. | Behind sign-in. |
| Embedded historian | Historian | FAIL | Not reached. The page copy says a built-in provider named local records history on this gateway. That was not confirmed in the UI. | Behind sign-in. |
| Postgres historian | Historian or Databases | FAIL | Postgres is not installed on this laptop. The page that would add a database provider was not opened. | No database service, and the UI is behind sign-in. |
| Trends | Control Canvas trend, or Historian | FAIL | Not reached. | Behind sign-in. |
| Users, roles, password policy | Users & Roles, Security Settings | FAIL | Not reached. Built-in role files exist on disk (administrator, operator, viewer) and no user file does. | Behind sign-in. The first user cannot be created from this page until someone is already signed in. |
| HTTPS | Network | FAIL | Not reached. The sign-in banner says cookies are not Secure because the listener is plain HTTP. The release note about a certificate file staying locked after HTTPS stops was not retested. | HTTPS cannot be turned on from the UI without an account. |
| Projects | Projects | FAIL | Not reached. The manual's first project starts on this page. | Behind sign-in. |
| Backup and restore | Backups | FAIL | Not reached. Create backup, download, and restore were not exercised. | Behind sign-in. |
| Structured Text create, check, deploy, run | Control Canvas logic editor | FAIL | Not reached. The soft-PLC program is installed under `app\plc`, including `eric-plc.exe` and the compiler. | The editor is behind sign-in. This audit did not drive the compiler outside the UI. |
| Scripts | Control Canvas console, HTTP Endpoints | FAIL | Not reached. | Behind sign-in. |
| Network and service settings | Network, Gateway Settings | FAIL | Not reached. Ports in use after a fresh start: HTTP `8090` on all addresses, gateway network `8060`, OPC UA `4840` on loopback only. | Behind sign-in. |
| Screens, every component, bindings, popups, publish, `/hmi` | `/designer` then `/hmi` | FAIL | Not reached. The palette groups (chart, container, display, input, navigation) were never shown. | Behind sign-in. |
| Service restart keeps an empty gateway | `Restart-Service` | PASS | Health UP. `gateway.yaml` hash unchanged. | |
| Service stop and start | `Stop-Service`, `Start-Service` | PASS | Port closed while stopped, then health UP. | |
| Process kill | End both `ERIC Gateway` processes | PASS | Service returned by itself. Data unchanged. | |
| Upgrade in place | run the 0.1.0-beta gateway installer again | PASS | Exit 0. Data kept. Only one Windows release exists, so this is a same-version upgrade. | |
| Uninstall keeps data, reinstall uses it | Add/Remove, then install again | PASS | Marker file and `gateway.yaml` survived. | The package uninstall does not delete `C:\ProgramData\ERIC`. |
| Firewall rule removed on service stop | `Stop-Service` on the shipped build | FAIL | Rule still present, local port 8090, while the service was Stopped. | The shipped process adds the rule and never deletes it. Shutdown of the shipped build does not remove it. |
| Firewall rule removed by Add/Remove | `msiexec /x` of both products | FAIL | After programs and the service were gone, the rule was still present. | Add/Remove does not run `uninstall.ps1`. There is no quiet-uninstall command. |
| No-users message visible to the person installing | service console | FAIL | The line is printed on the process console. The shipped service registry has no stdout path. It is not in `gateway.log`. | The service manager discards the console unless `AppStdout` is set. The data directory's log folder also does not exist until the process has started, so a log path inside that folder is late for the first line. |
| Service environment names | service Parameters | FAIL | `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, `OIP_DOCS_DIR`, and `ERIC_PLC_COMPILER`. Startup warns that the three `OIP_` names are deprecated. | The installer fragment was not updated when the `ERIC_` names became current. |
| Reported version | health payload and Add/Remove | FAIL | Health: `0.1.0-SNAPSHOT`. Add/Remove: `0.1.0`. Banner: v0.1.0 beta. | The installer version is the numeric prefix. The jar still carries the snapshot version. |

## Windows-only fixes on this branch

These are in source. They are not in the 0.1.0-beta installers, and this laptop is still running that release.

- The service environment now sets `ERIC_ADMIN_DIST`, `ERIC_WEB_DIST`, `ERIC_DOCS_DIR`, and `ERIC_PLC_COMPILER`. The deprecated `OIP_` copies are not set. `LegacyEnv` still maps the `ERIC_` names onto the names the rest of the process reads.
- Service stdout and stderr go to `service-stdout.log` in the install directory, which exists before the first start, so the no-users line is kept. The file is removed with Program Files.
- The service waits 15 seconds on stop so a clean exit can finish.
- When the gateway process shuts down, it deletes the firewall rule it added. A late add after shutdown does not recreate the rule. A stop of a console program launched the same way does run its cleanup; the shipped gateway binary does not contain this change, so it was not rechecked against the installed service.
- `uninstall.ps1` also clears machine-level `ERIC_ADMIN_DIST`, `ERIC_WEB_DIST`, and `ERIC_DOCS_DIR` if they were set.
- `packaging/windows/smoke.ps1` checks the new environment, the stdout path, the stop wait, and that a service stop removes the firewall rule.
- Unit coverage: `LayoutTest.serviceConfigPointsAtProgramDataAndDoesNotRemoveIt` and `WindowsFirewallTest`.

The missing first-run administrator is not fixed here. It is the same gap on every install path that does not set the password, and the sign-in page is shared. Giving the Windows installer its own password dialog would still leave the manual and the sign-in page wrong. That belongs with the work that makes the product usable from the UI.

Creating an account was not done through an environment variable during this audit, so the features behind sign-in were not given a second, non-UI pass.

## How to re-run the UI walk

`packaging/windows/e2e/fresh-install-ui.mjs` opens the installed gateway in Chrome through Playwright and writes `ui-audit.json`. It does not start a gateway and it does not call configuration APIs.

```
node packaging/windows/e2e/fresh-install-ui.mjs
```
