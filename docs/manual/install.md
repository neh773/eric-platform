# Install and uninstall

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

## Windows

1. Run the ERIC Gateway installer.
2. The license screen starts with the beta warning, then the AGPL-3.0 text, then the third-party notices. Choose accept to continue. If you do not accept, the installer stops.
3. The ERIC Gateway service starts. Control Canvas has a Start-menu shortcut. The manual is served at `http://127.0.0.1:8090/docs`.
4. Example projects are not installed. Import one later if you want it. See [Brownsville](examples/brownsville.md).

The longer Windows notes, including a silent install and the upgrade ids, are in `docs/install/windows.md` in the repository.

## Uninstall on Windows

The package includes `uninstall.ps1`. It removes the service, the firewall rule, the shortcuts, the environment variables, the registry keys, and Program Files. It keeps `C:\ProgramData\ERIC` unless you pass `-DeleteData` or tick the box that deletes that folder. Deleting that folder deletes projects, history, and users.

## Linux

A release has a gateway archive and a Control Canvas archive. They are app images, not deb or rpm packages, and they include their own Java runtime.

1. Download `ERIC-<version>-linux-x64-app-images.tar.gz` from the release page and extract it anywhere you can write, for example your home folder: `tar -xzf ERIC-<version>-linux-x64-app-images.tar.gz`.
2. Start the gateway: `"./image/ERIC Gateway/bin/ERIC Gateway"`. It starts the gateway and opens the admin page in your browser. No environment variables are needed. The archive contains the admin pages, the Control Canvas web app and this manual, and the gateway finds them next to itself.
3. If no browser opens, go to `http://127.0.0.1:8090/admin` on the same machine.
4. The first time, the sign-in page shows **Create the administrator**. Enter an **Administrator username**, a **New password** and **Confirm password**, then choose **Create administrator**. You are signed in. This form only appears while the gateway has no users, and only to a browser on the gateway machine itself.

Data is kept in `~/.local/share/eric/data` (or `$XDG_DATA_HOME/eric/data`). Control Canvas is `"./image/Control Canvas/bin/Control Canvas"`, and it is also served by the gateway at `http://127.0.0.1:8090/designer`. Stop the gateway with Ctrl+C. Devices, tags, UDT definitions, connections and history settings are kept when it restarts.

You can also build from a checkout with JDK 21: `./gradlew :gateway:server:installDist` after `pnpm build` in `web/`. A checkout build looks for `web/admin/dist`, `web/designer/dist` and `docs/manual` through `ERIC_ADMIN_DIST`, `ERIC_WEB_DIST` and `ERIC_DOCS_DIR` when they are not next to the jar.

## Uninstall on Linux

Stop the gateway and delete the extracted folder. Delete `~/.local/share/eric` as well if you want to remove projects, history and users.

## Power user

The gateway reads `ERIC_DATA_DIR`, `ERIC_HTTP_PORT`, and `ERIC_ADMIN_PASSWORD` (the older `OIP_` names still work). These are optional; the first-run form is the normal way to create the administrator. In a container image, set the password at run time. Do not bake it into an image.
