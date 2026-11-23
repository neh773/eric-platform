# Windows installers

The display name, vendor, and executable names come from one Gradle property, `oip.productName` in `gradle.properties`. The default is `ERIC`. The gateway installer is named `{product} Gateway` and the screen designer installer is named `Control Canvas`. Two upgrade UUIDs in that same file (`oip.gatewayUpgradeUuid`, `oip.designerUpgradeUuid`) stay fixed so a later build upgrades in place and keeps `C:\ProgramData\ERIC`.

jpackage builds an `.exe` with a runtime produced by jlink. A machine does not need a system Java. jpackage only builds an installer for the OS it is running on, and the `.exe` needs [WiX 3](https://wixtoolset.org/) (`candle.exe` and `light.exe`) on `PATH`.

The no-code guide for operators is on the docs site (`Install on Windows` and `PLC on Windows`). This page is the build and the contents.

## What you install

Build the admin UI and Control Canvas first (`pnpm --filter @oip/admin build` and `pnpm --filter @oip/designer build` from `web/`), then:

```
gradlew :packaging:windowsInstallers
```

That writes:

- `packaging/build/dist/ERIC Gateway-0.1.0.exe`
- `packaging/build/dist/Control Canvas-0.1.0.exe`

The gateway package contains the admin bundle, the designer bundle, `eric-plc.exe`, the Structured Text block sources, and RuSTy `plc.exe` plus `iec61131std.dll`, and the LLVM linker `plc\llvm\lld-link.exe` (Apache-2.0 WITH LLVM-exception) that PLC deploy uses, so no Visual Studio or Build Tools are needed. RuSTy is LGPLv3, not linked into ERIC. The license screen opens with the beta warning (bench hardware only, not production, life-safety, or equipment protection, AS IS, no warranty) and then the AGPL-3.0 notice. The installer does not continue until that screen is accepted. A source offer is installed under `app\plc\licenses`.

The service environment sets `OIP_ADMIN_DIST` and `OIP_WEB_DIST` to the installed bundles, so `/admin`, `/hmi`, `/studio`, and `/designer` are served. The Control Canvas shortcut opens `http://127.0.0.1:8090/designer`.

Each installer has an application icon, Start-menu and desktop shortcuts, version information, and an Add/Remove Programs entry with publisher, version, and an uninstall command. The gateway service adds a firewall allow rule for its TCP port when it starts. The uninstaller removes that rule. The installer does not write the firewall registry itself, because that stalled the silent install. NSSM still hosts the service. NSSM 2.24 is public domain and is downloaded when the exe is built.

## Example projects

Example projects are not part of the product and are not installed by default. The gateway installer has no checked option that loads one. To offer them, set `OIP_EXAMPLES` to a directory of example folders and use **Import example project** on the admin Projects page, or File → New from example in Control Canvas. Leaving `OIP_EXAMPLES` unset and not copying `examples\` into the install leaves the list empty.

## Uninstall

`packaging/windows/uninstall.ps1` stops and deletes the service, deletes the firewall rule, removes Start-menu and desktop shortcuts, clears `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `ERIC_PLC_RESOURCES`, deletes `HKLM\Software\ERIC` and any leftover service key, removes scheduled tasks named for the product, removes both Program Files trees, and deletes `plc-resources.json` and `gateway-jvm.txt`.

The checkbox **Also delete C:\ProgramData\ERIC** is off unless the person checks it. Silent mode keeps the folder unless `-DeleteData` is passed. Add/Remove Programs uses the package uninstall and keeps ProgramData. The package itself cannot schedule that deletion: jpackage places this fragment inside a WiX `Fragment`, which cannot hold an install sequence.

## PLC on Windows

The scan engine uses a high-resolution waitable timer, a named file mapping, and a named pipe. Core and memory choices on the resource page are affinity, a locked working set, and the gateway `-Xmx`. Windows cannot fully isolate a core. The Logic editor, PLC status, and that page show a banner that cannot be dismissed while the gateway is on Windows, driven by `GET /api/v1/platform`. A timing sample from the installer workflow is labeled `CI Windows runner, not reference`.

## Check without Windows

On Linux or macOS, jpackage can still build the app images and a bundled runtime:

```
gradlew :packaging:smokeAppImages
```

That starts the gateway image on port 18090 and requests `/api/v1/health`, and runs the designer launcher with `--print-only`. It does not build an `.exe` or register a service.

## CI

`.github/workflows/windows-installers.yml` runs on version tags (`v*`) and `workflow_dispatch`. It installs WiX, builds the web bundles and both exes, uploads them as the `windows-installers` artifact, and runs `packaging/windows/smoke.ps1`: install, service running, health UP, admin and Canvas pages return 200, an idle timing sample, an ST program compiled with RuSTy and run on `eric-plc`, then uninstall with ProgramData kept and again with it removed. It is not part of the pull-request jobs.
