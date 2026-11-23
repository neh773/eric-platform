# ERIC UI-only new-user audit (Linux)

Rule: PASS only if a user can create, view, edit, delete and use the feature through the admin page or Control Canvas
alone (no file edits, imports-only paths, scripts, API calls, env vars or CLI flags beyond launching), and it survives
restart and backup/restore. Suite: `web/e2e-ui` (`pnpm test:ui`), branch `audit/ui-only-e2e` stacked on `fix/0.1.1-beta`.

Status: IN PROGRESS — this file is updated as runs finish.

## 0.1.0-beta baseline (main deecdbb, installDist + bundles, exploration + first suite run, 2026-11-22 ~20:15 CST)

| Feature | Result | Evidence / cause |
| --- | --- | --- |
| Linux tarball serves admin/Canvas/manual | FAIL | tarball had no web bundles (fixed on fix/0.1.1-beta 46461be) |
| First administrator on a fresh install | FAIL | only `ERIC_ADMIN_PASSWORD`; a password containing "admin" crashes startup (fixed: first-run form 46461be) |
| Modbus TCP device add/edit/delete, connects | PASS | New device form; ls01 @ 10.20.40.15:502 Connected |
| Modbus RTU-over-TCP, S7, Logix, DNP3, Simulator, UDP, OPC UA client create/delete | PASS | same form |
| BACnet remote device create | FAIL | form Save never reports "Saved" (validation without a visible message) |
| MQTT device/broker in New device form | FAIL | MQTT module installed but not offered in the form (Config page JSON only) |
| Memory tag create/write/edit/rename/delete | PASS | Tags page forms |
| Device (opc) tag from device browse and typed address | PASS | value flows (raw 4500) |
| Tag scaling / engineering units | PASS | Scaling + Numeric tabs |
| Tag editor Basic tab "Name" | FAIL | Name field shows blank for every tag (fixed on audit branch: hidden, rename box owns it) |
| Tag editor Value tab | FAIL (UX) | shows every type's fields (expression, source path, JSON) for any tag (fixed: only the tag type's fields) |
| Tag alarms | FAIL | JSON textarea only (fixed on audit branch: alarm form) |
| UDT definitions | FAIL | Config/JSON only (fixed on fix/0.1.1-beta 8aea9aa: UDT Definitions page) |
| UDT instances wizard | PASS (needs a definition) | Tags > UDT instances |
| Historian: record a tag (local) | PASS | Historian > Record a tag |
| Historian: Postgres provider | FAIL | no database connection form (fixed 8aea9aa) |
| Active alarms / acknowledge in admin | FAIL | no view; only via an HMI alarm table (fixed on audit branch: Alarming > Active alarms) |
| Users, roles, operator sign-in | PASS | |
| Backups create / download / restore stored backup | PASS | |
| Restore a backup FILE (fresh install, other machine) | FAIL | upload only via API (fixed on audit branch: "Restore from a backup file") |
| Project create (admin) | PASS | |
| New project in Canvas opens with 3 unsaved sample views | FAIL (UX) | sample screens injected into an empty project |
| Canvas: add view, component, tag binding, save | PASS | live value 4500 in binding editor |
| /hmi/<project>/<view> live values | PASS | |
| /hmi with no project | FAIL (UX) | shows a built-in demo screen instead of a project list |
| Home page links to Control Canvas / manual | FAIL | no launch links on Home |
| Session-cookie warning banner on every page | FAIL (UX) | alarming warning for every plain-HTTP install |

## Fix-branch build: audit/ui-only-e2e e5df429 (stacked on fix/0.1.1-beta), Linux app image, fresh data dir, port 8291 — 2026-11-22 evening CST

Automated suite run (482 s): 57 passed, 26 failed, 6 did not run. Most failures were test-side (locators, and Canvas specs
that expected a project tree in a project that now opens empty). Rows below are the discovery pass, one per feature, added as each is checked.

| Feature | Result | Evidence / cause |
| --- | --- | --- |
| First administrator (first-run form) on fresh install | PASS | "Create the administrator" form, then signed in |
| OPC UA client to this gateway's own server with username | PASS | suite: selfua Connected; password saved as secret reference |
| MQTT broker in New device form (host, version, TLS, Sparkplug, user/password) | PASS | suite: fields present |
| Memory, device, scaled tags; UDT definition | PASS | suite 20-tags, 30-udt definition |
| Every admin nav page opens (41 pages) | PASS | all 41 Gateway sections links load with an h1, no error alert, no JS error |
| UDT instance wizard; members read live device values | PASS | LS_01 in Stations, WetWellLevel 43.71 Good, Pump1Running Good (minor: member rows show the word 'Diagnostics' before the data type) |
| Canvas: every palette component (78) added, saved, rendered at /hmi | PASS | all Add buttons work, 'All changes saved', /hmi/liftstation/palette2 renders with no JS errors |
| Canvas: multi-view project, live tag binding (numeric), save, reload | PASS | views alarms/detail/station/trends persist after reload; /hmi shows live 55.2 |
| Canvas: Popup action with title, popup shows live value | PASS | Details button opens 'Wet well detail' dialog, value 37.5 |
| Canvas: Navigation action to another view | PASS (minor) | 'Go to alarms' swaps to the alarms view, but the address bar keeps /station, so reload or a shared link goes back |
| Canvas: new components are placed overlapping | FAIL (UX) | each added component lands 16 px below/right of the previous one on a coordinate view and covers it; the user has to set X/Y for each |
| Canvas: new project opens empty | PASS | Blank project shows 'has no views' plus a New view form; no sample views |
| Canvas: live trend bound to a tag | PASS | Trend Value bound to WetWellLevel draws a line on /hmi (64.5) |
| Canvas: time series chart with historian pen | PASS | History pens = LS_01/WetWellLevel; /hmi shows the pen, axis 30-70, time range buttons |
| Tag alarm form: create, edit setpoint, persists | PASS | suite 50-alarms: HighLevel AboveValue 80 High, reload keeps values |
| Alarm goes active, Alarming page Active alarms, acknowledge | PASS | suite 50-alarms |
| Canvas alarm status table on /hmi: shows active alarm, select + Acknowledge | PASS | row 'Active and Unacknowledged' -> 'Active and Acknowledged' with ack time |
| Alarm notification: roster, pipeline, schedule, contact forms create/edit/delete | PASS | suite 50-alarms (roster, pipeline); New schedule and New contact forms present |
| Alarm notification forms: name field | FAIL (UX) | contact, schedule, roster and pipeline forms label their name box 'Alarm name' |
| Alarm -> notification pipeline link | FAIL | tag alarm form has no Active/Clear/Ack pipeline fields; an alarm can only start a pipeline through 'Edit alarms as JSON' (activePipeline) |
| Alarm pipeline actually notifies (email/SMS) | NOT VERIFIED | needs the pipeline link above plus a real SMTP server; Email Profiles form saves (suite) |
| PLC: Structured Text check/compile on the Linux app image | FAIL | 'Structured Text compiler is not available': RuSTy is not bundled, only ERIC_PLC_COMPILER env var finds it (fix branch fad9752 now ships it; not in this build) |
| PLC: create program in Logic workspace, editor, symbol insert, rename/delete buttons | PASS | New program lead_lag opens a template; Insert list has ST keywords and ERIC_* function blocks |
| PLC: check, compile and deploy, start/stop (compiler supplied via ERIC_PLC_COMPILER for the rest of the PLC rows) | PASS | Check clean; deploy -> running, period 10 ms; Stop -> stopped; program text survives a graceful restart |
| PLC: Runtime status when the Logic workspace opens | FAIL (UX) | shows 'stopped' and an empty watch list while the runtime is running; only pressing Start refreshes it |
| PLC: watch list and force/unforce | PASS (minor) | mapped variables listed with live value; Force sets 'true forced' and the 'Forces are active' banner; Unforce clears it. Force/Unforce buttons have no per-variable accessible name |
| PLC: tag mapping (hardware/tag map) data type | FAIL | mapping row has Variable, Address, Tag, Direction, Safe but no data type, so a REAL input (WetWellLevel 56.9) is watched as BOOL 'false' |
| PLC: mapped output writes its tag | FAIL | pumpCmd forced true while running, mapped out to [default]LiftStation/PumpCmd at %QX0.0, tag stays false |
| PLC: exec time stat | FAIL (minor) | Runtime line always reads 'exec 0 ns', jitter 0 ns while running |
| PLC: online mode (live values in the code editor) | FAIL | no online mode: the editor shows no live values; only the separate watch list |
| Canvas: button Script action (Python, actions.writeTag) runs on /hmi | PASS | 'Pump on' wrote [default]LiftStation/PumpCmd = true |
| Gateway timer scripts | FAIL | manual says 'Add a timer script' but no admin page or Canvas form creates a Timer; only Config JSON |
| Canvas: project script hooks (Scripts panel) | PASS (not run) | Add hook form present; hook call path not exercised |
| Canvas: Console tab runs a gateway script | PASS | system.tag.readBlocking printed True |
| Database connection form (Postgres), Create and test | PASS | historian-pg 'saved and is valid' |
| Historian: SQL provider on Postgres + Record a tag to it | PASS | 36 rows in oip_history after ~40 s (1 s periodic, values 53.1...) |
| Canvas Tools: History query | PASS (minor) | returns per-minute rows for last 10 m; minutes before the pg recording started show blank '0 rows' instead of being left out |
| OPC UA server: username sign-in from a client (None policy) | PASS | selfua device Connected with gateway user admin (suite) |
| OPC UA Certificates page: quarantined client certs, Trust, Import and trust | PASS (page) | server URL shown, quarantined list with Trust/Delete, PEM/DER import |
| OPC UA client with Basic256Sha256 SignAndEncrypt | FAIL | the client device makes a new self-signed certificate on every connect attempt (9 different thumbprints quarantined in 1 min), so trusting it never sticks; stays Bad_SecurityChecksFailed |
| MQTT: broker device connects (local Mosquitto) | PASS | broker1 Connected |
| MQTT: plain topic tag (number:plant/level) read and write | PASS | publish 42.5 -> tag 42.5 Good; tag write 61.25 -> published on plant/level |
| MQTT: Sparkplug B auto-created tags | NOT VERIFIED | needs a Sparkplug edge node publishing births; not available on this box |
| Session-cookie warning banner | FAIL (UX) | still on every admin page over plain HTTP in this build (fix agent owns it) |
| HTTPS: turn on from Network page, reach admin over HTTPS | PASS | https://127.0.0.1:8293/admin/ 200 right after Save (self-signed) |
| HTTPS: page state after Save | FAIL (UX) | page still says 'HTTPS is off', 'HTTPS port 8043' and 'waiting for a gateway restart' while HTTPS on 8293 already answers |
| HTTPS: self-signed certificate name | FAIL (minor) | certificate subject is 'CN=OIP Gateway' (old name), not the gateway/system name |
| Gateway restart from the UI | FAIL | settings ask for a gateway restart but no admin page has a Restart button; user must restart the service/process |
| Certificate signing request create/download/install form | PASS (form) | CSR shown with SHA-256 and Download request; install reply not exercised (no CA) |
| Network: redundancy pair form, TLS protocols/ciphers, policy rules | PASS (forms render) | forms present; redundancy needs a second gateway, not exercised |
| Users & Roles: create role, user (operator, viewer), sign in, delete | PASS | suite 60-security + v1 viewer created here |
| Permissions: operator writes tags (admin and HMI), viewer cannot | PASS | op1 wrote HighLevelTest 12 and PumpCmd true; v1 HMI Pump on left PumpCmd false; v1 has no write box |
| Permissions: admin-only pages and Control Canvas refused to operator/viewer | PASS | Users page 'users.admin denied (PERMISSION_DENIED)'; Canvas 'not allowed to use Control Canvas (designer.login)' |
| Permissions: feedback for refused actions | FAIL (UX) | operator/viewer still see all 41 nav links and pages that then deny; viewer's HMI button press is silently ignored (no message) |
| Audit log page: logins and tag writes with user, old/new value | PASS | op1/admin tag writes and v1/op1 logins listed |
| Audit log: refused writes | FAIL (minor) | viewer v1's refused HMI write is not in the audit log |
| Diagnostics page (performance, threads, memory, modules...) | PASS | live CPU/heap/disk/clock drift refreshing every 3 s |
| Logs page: filter, follow, download, set logger level | PASS | entries listed; level form saves (suite 65) |
| About / version / licence | FAIL (minor) | no About or licence page; Overview shows 'Version 0.1.0-SNAPSHOT' on the 0.1.1 build (fix agent f91d225 addresses the version); licence only in the beta banner text |
| Modules page lists drivers RUNNING | PASS | all drivers RUNNING (driver versions read 0.1.0) |
| Canvas style class: create in Styles, set colour, apply to label | PASS | label renders rgb 255,0,0 at /hmi with class oip-sc-hot after save |
| Canvas Pages: customize pages, map / to a view | PASS | /hmi/liftstation/ opens station after save |
| Canvas default page with no page config | FAIL (minor UX) | / opens the alphabetically first view - alarms - not a chosen home view |
| Canvas publish | PASS | no separate publish step: Save makes views live at /hmi immediately; Launch opens them |
| Lifecycle: graceful stop/start | PASS | stops in 3 s, up in 6 s; 5/5 connections Connected, users, UDT, HMI live value, pg historian, saved HTTPS setting applied on 8293 - 200 |
| Lifecycle: PLC program after restart | FAIL | deployed and running program comes back 'stopped'; Start does nothing until Compile and deploy is run again - not resumed, no error shown |
| Lifecycle: kill -9 then start | PASS | stale lock/pid ignored, up in 5 s; connections, users, HMI, historian, HTTPS all back |
| Lifecycle: reinstall app image keeping data dir | PASS | re-extracted tarball over a removed install; all config, users, UDT, connections, historian, HTTPS kept |
| Backups: create encrypted backup with passphrase, download file | PASS (minor) | file created and downloads byte-identical; no 'backup created' message - new row appears at the bottom after a few seconds |
| Lifecycle: fresh install then Restore from a backup file | PASS (minor) | after confirm + restart: 5/5 connections, pg historian with its secret, users and op1 password, UDT, views, style class, pages, alarm rosters, HTTPS 8293 all back |
| Restore: 'live now' message | FAIL (minor UX) | says devices are live now, but modbus ls01 and the pg historian are missing and MQTT 'Not Started' until a restart; no restart prompt |
| Backup/restore: PLC Logic programs | FAIL | logic programs and tag mapping are not in the backup - restored gateway has no lead_lag program |
| Restore form accessibility | FAIL (minor) | file input is not labelled 'Backup file' for screen readers |
| Fresh install default ports | NOTE | gateway network listens on 8060 by default - clashes with a gateway serving HTTP on 8060 |

**Discovery tally (2026-11-22 evening, America/Chicago):** 48 PASS (5 minor, 1 not run), 23 FAIL (10 functional, 13 UX/minor), 2 NOT VERIFIED, 1 NOTE. No product code changed after the role change.

## Status on fix/0.1.1-beta (2026-11-22, late evening CST)

| FAIL from the tally above | Now |
| --- | --- |
| Canvas components overlap | Fixed: new components land on a free spot (eed23bd) |
| Notification forms say 'Alarm name' | Fixed: 'Roster name', 'Pipeline name', 'Schedule name', 'Contact name' |
| Alarm -> pipeline link | Fixed: tag alarm form has Active, Clear and Ack pipeline fields |
| ST compiler missing from the Linux image | Fixed: bundle ships eric-plc and the compiler (fad9752) |
| PLC status on open | Fixed: Logic workspace reads the runtime status when it opens |
| Tag map data type | Fixed: Type column, set from the address (%IX BOOL, %IW INT, %ID REAL) |
| Mapped output writes its tag | Fixed: e2e logic-online checks the tag and the Modbus register |
| Exec time 0 ns | Fixed: managed engine reports real scan stats |
| Online mode | Added: Go online, live values in the code, right-click force/write/release, forced banner, watch table |
| Gateway timer scripts | Fixed: Gateway Scripts page (timer, tag change, scheduled, startup, shutdown; Python) |
| OPC UA client certificate changes on each connect | Fixed: kept in the data dir pki/own |
| Session-cookie banner | Fixed: no warning for a browser on the gateway's own machine; a plain sentence for other browsers |
| HTTPS page state after Save | Fixed: page reloads live state (3bb28c6); UI suite asserts it |
| Certificate name | Fixed: CN=ERIC Gateway |
| No Restart button | Added: Overview, Restart gateway (gateway.write, audited) |
| Nav shows pages the role cannot use; silent HMI refusal | Fixed: nav follows permissions; HMI shows 'Not written to ...' |
| Refused HMI write not audited | Fixed: audit FAILURE tag.write, context hmi |
| About / licence | Added: About section on Overview (version, beta, AGPL-3.0, source, manual) |
| Canvas home view | Added: Home view picker in Pages |
| PLC program stopped after restart | Fixed: resumes, Stop/Start work |
| Restore 'live now' message | Fixed: accurate message plus 'Restart gateway now' |
| PLC programs not in backups | Fixed: logic programs and the tag map are backed up and restored |
| Restore file input label | Fixed: 'Backup file' |
| Port 8060 clash (NOTE) | The gateway starts anyway and the Network page shows an alert naming the setting to change |

Still open (minor): Navigation action keeps the old URL; History query shows empty minutes; UDT member rows show the word 'Diagnostics'; no 'backup created' message. Not verified: email delivery from a pipeline, Sparkplug births.
