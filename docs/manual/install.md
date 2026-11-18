# Install and uninstall

**ERIC v0.1.0 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

## Windows

1. Run the ERIC Gateway installer.
2. The license screen starts with the beta warning, then the AGPL-3.0 text, then the third-party notices. Choose accept to continue. If you do not accept, the installer stops.
3. The ERIC Gateway service starts. Control Canvas has a Start-menu shortcut. The manual is served at `http://127.0.0.1:8090/docs`.
4. Example projects are not installed. Import one later if you want it. See [Brownsville](examples/brownsville.md).

The longer Windows notes, including a silent install and the upgrade ids, are in `docs/install/windows.md` in the repository.

## Uninstall on Windows

The package includes `uninstall.ps1`. It removes the service, the firewall rule, the shortcuts, the environment variables, the registry keys, and Program Files. It keeps `C:\ProgramData\ERIC` unless you pass `-DeleteData` or tick the box that deletes that folder. Deleting that folder deletes projects, history, and users.

## Linux

A tag build produces a jpackage app image of the gateway and of Control Canvas. It is an archive, not a deb or rpm. You can also build from a checkout with JDK 21 and `./gradlew :gateway:server:installDist`, then start the gateway with `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `OIP_DOCS_DIR` set. `OIP_DOCS_DIR` is this manual directory, which is what `/docs` serves.

## Power user

The gateway image reads `OIP_DATA_DIR`, `OIP_HTTP_PORT`, and `OIP_ADMIN_PASSWORD`. Set the password at run time. Do not bake it into an image.
