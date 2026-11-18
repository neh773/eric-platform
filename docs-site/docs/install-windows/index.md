---
title: Install on Windows
sidebar_label: Install on Windows
---

# Install on Windows

These installers are for a PC. No compiler and no Java install are required. The gateway installer includes the admin pages, Control Canvas (HMI and the studio), `eric-plc`, the Structured Text block library, and the RuSTy compiler as a separate program.

## Install

1. Run `ERIC Gateway-0.1.0.exe`. The license screen starts with the beta warning. ERIC v0.1.0 is beta software for test hardware under safe conditions, not for live production, life-safety, or equipment protection, and it is provided as is with no warranty. Accepting the screen acknowledges that warning and the AGPL-3.0 license. The screen also names the RuSTy LGPL and the other third-party notices. If you do not accept, the installer stops.
2. The installer adds a Start-menu shortcut, a desktop shortcut, and an Add/Remove Programs entry (publisher, version, and uninstall command). It registers the **ERIC Gateway** service and starts it. The service adds a firewall allow rule for TCP port 8090.
3. Run `Control Canvas-0.1.0.exe`. Its shortcut opens `http://127.0.0.1:8090/designer`, which the gateway serves. `/studio` is the same studio.
4. Open `http://127.0.0.1:8090/admin` and sign in. `/hmi` is the operator interface.

Projects, history, and logs are stored in `C:\ProgramData\ERIC`.

## Upgrade

Run a newer installer with the same product. The upgrade ids are fixed, so the new build replaces the old one in place and keeps `C:\ProgramData\ERIC`.

## Uninstall

Add/Remove Programs removes the service, the shortcuts, and the Program Files folders. It keeps `C:\ProgramData\ERIC`. The uninstall script also removes the firewall rule.

The Start-menu program does not show a data checkbox. Run the installed helper, or the copy from a checkout:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall.ps1
```

The window has one checkbox, **unchecked**: “Also delete C:\ProgramData\ERIC (projects, history, logs)”. Cancel leaves the install in place. Uninstall always removes PLC core pinning and the gateway `-Xmx` note, even when that folder is kept.

A silent uninstall keeps the folder:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall.ps1 -Silent
```

To remove it as well:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall.ps1 -Silent -DeleteData
```

Add/Remove Programs keeps `C:\ProgramData\ERIC`. The uninstall script is what offers the checkbox and `-DeleteData`.

What always goes away: the Windows service, the port 8090 firewall rule, Start-menu and desktop shortcuts, the `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `ERIC_PLC_RESOURCES` machine variables, the `HKLM\Software\ERIC` key, scheduled tasks whose names contain ERIC, Program Files for the gateway and Control Canvas, and `plc-resources.json` plus `gateway-jvm.txt`.
