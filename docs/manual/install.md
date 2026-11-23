# Install and uninstall

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

## Windows

### What a Windows computer needs

- 64-bit Windows 10 or 11, and an account that can approve the administrator prompt. The gateway installs as a Windows service, so the installer asks for administrator rights.
- Nothing else for the gateway, the admin pages, Control Canvas and the manual. The installer carries its own Java runtime and the Microsoft Visual C++ runtime files the PLC tools need. You do not need Java, Visual C++ redistributables, WebView2 or developer tools. Control Canvas and the admin pages open in your normal browser.
- Nothing extra for PLC deploy either. The installer includes the Structured Text compiler, the scan engine and the LLVM linker (`plc\llvm\lld-link.exe`) that builds the program file. You do not need Visual Studio or Build Tools.

### Install

1. Download `ERIC-Gateway-<version>-windows-x64.exe` from the release page.
2. The installers are not code-signed yet (beta). Windows SmartScreen may say "Windows protected your PC". Choose **More info**, check the file name, then **Run anyway**.
3. Approve the administrator prompt. The license screen starts with the beta warning, then the AGPL-3.0 text, then the third-party notices. Choose accept to continue.
4. The last page shows the address, `http://localhost:8090/admin`, and has **Open the admin page now** ticked. Choose **Finish**. A small window waits for the ERIC Gateway service (the first start can take a minute), then your browser opens the "Create administrator account" screen. Create the first account **on this computer**: for safety, the first account cannot be created from another computer.
5. Later, open **ERIC Admin** from the Start menu, or go to `http://localhost:8090/admin` (also `http://127.0.0.1:8090/admin`). The address is plain HTTP, so there is no certificate warning. Turn on HTTPS in Network before other computers sign in.
6. Control Canvas has its own installer and Start-menu shortcut. The manual is served at `http://127.0.0.1:8090/docs`. Example projects are not installed. See [Brownsville](examples/brownsville.md).

Every install keeps its log at `C:\ProgramData\ERIC\logs\install.log`, also when it succeeds. If the gateway does not open, see [Troubleshooting on Windows](troubleshooting-windows.md).

### Silent install

From an administrator Command Prompt (a silent install cannot show the administrator prompt):

`"ERIC-Gateway-<version>-windows-x64.exe" /qn /norestart /L*v "C:\ERIC-install.log"`

Quote the installer path when it contains spaces. Exit code 0 or 3010 means it installed. A copy of the log is also kept at `C:\ProgramData\ERIC\logs\install.log`. A silent install does not open the browser. The longer notes, including the upgrade ids, are in `docs/install/windows.md` in the repository.

## Uninstall on Windows

The package includes `uninstall.ps1`. It removes the service, the firewall rule, the shortcuts, the environment variables, the registry keys, and Program Files. It keeps `C:\ProgramData\ERIC` unless you pass `-DeleteData` or tick the box that deletes that folder. Deleting that folder deletes projects, history, and users.

## Linux

A release has a gateway archive and a Control Canvas archive. They are app images, not deb or rpm packages, and they include their own Java runtime.

1. Download `ERIC-<version>-linux-x64-app-images.tar.gz` from the release page and extract it anywhere you can write, for example your home folder: `tar -xzf ERIC-<version>-linux-x64-app-images.tar.gz`.
2. Start the gateway: `"./image/ERIC Gateway/bin/ERIC Gateway"`. It starts the gateway and opens the admin page in your browser. No environment variables are needed. The archive contains the admin pages, the Control Canvas web app and this manual, and the gateway finds them next to itself.
3. If no browser opens, go to `http://127.0.0.1:8090/admin` on the same machine.
4. The first time, the sign-in page shows **Create the administrator**. Enter an **Administrator username**, a **New password** and **Confirm password**, then choose **Create administrator**. You are signed in. This form only appears while the gateway has no users, and only to a browser on the gateway machine itself.

PLC deploy needs nothing extra: the archive includes the Structured Text compiler, the scan engine and the LLVM linker (`lib/app/plc/llvm/ld.lld`). You do not need gcc or other developer tools.

Data is kept in `~/.local/share/eric/data` (or `$XDG_DATA_HOME/eric/data`). Control Canvas is `"./image/Control Canvas/bin/Control Canvas"`, and it is also served by the gateway at `http://127.0.0.1:8090/designer`. Stop the gateway with Ctrl+C. Devices, tags, UDT definitions, connections and history settings are kept when it restarts.

You can also build from a checkout with JDK 21: `./gradlew :gateway:server:installDist` after `pnpm build` in `web/`. A checkout build looks for `web/admin/dist`, `web/designer/dist` and `docs/manual` through `ERIC_ADMIN_DIST`, `ERIC_WEB_DIST` and `ERIC_DOCS_DIR` when they are not next to the jar.

## Uninstall on Linux

Stop the gateway and delete the extracted folder. Delete `~/.local/share/eric` as well if you want to remove projects, history and users.

## Power user

The gateway reads `ERIC_DATA_DIR`, `ERIC_HTTP_PORT`, and `ERIC_ADMIN_PASSWORD` (the older `OIP_` names still work). These are optional; the first-run form is the normal way to create the administrator. In a container image, set the password at run time. Do not bake it into an image.
