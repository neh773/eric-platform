# Testing the 0.1.0-beta

This build is for controls engineers who want to try ERIC on a bench. It is not a production release.

## System requirements

| | Windows | Linux |
| --- | --- | --- |
| OS | Windows 10 or 11, 64-bit | A current Debian or Ubuntu, 64-bit |
| CPU | 4 cores | 4 cores |
| Memory | 8 GB, 16 GB if you import the example and run the soft-PLC | 8 GB |
| Disk | 2 GB for the install, more for historian history | 2 GB |
| Runtime | The Windows installer includes a JDK | JDK 21 (Temurin or another build with the Classpath Exception) |

The Windows soft-PLC shares the PC with Windows. It does not hold a 1 ms cycle. Do not pin a safety function to it.

## Install

Windows: run the installer. It creates the ERIC Gateway service and a Control Canvas shortcut. Set a password with `ERIC_ADMIN_PASSWORD` if you are installing from a script. The installer does not create a project and does not install example projects.

Linux: install JDK 21, build with `./gradlew :gateway:server:installDist`, and point `OIP_DOCS_DIR`, `OIP_ADMIN_DIST`, and `OIP_WEB_DIST` at the built directories. See `docs/install`.

## First hour

1. Open `http://127.0.0.1:8090/admin` and sign in.
2. Create a project. Leave Public HMI off.
3. Open Control Canvas at `/designer`.
4. Optional: on the admin Projects page, import the Brownsville wastewater example. That project's own settings turn Public HMI on. A project you create yourself does not.
5. Add a Modbus connection and one tag. Confirm the value moves.
6. Write a short Structured Text program, check it, and deploy it. On Windows the banner appears while the soft-PLC is running.
7. Build one screen: a numeric display bound to the tag, and a button that writes it.

The same path, with pictures, is in the manual under Getting started for testers.

## What to send back

Open an issue with the template that matches:

- Something broke: bug report. Attach the gateway log with passwords removed.
- Something is missing: feature request.
- You finished the first hour: feedback survey. Say what you tried and where you got stuck.

## Known limitations

- Windows soft-PLC is a normal process. Cycle time moves when Windows is busy. The banner is there so a bench PC is not mistaken for a controller.
- Public HMI, when a project enables it, can read and write tags. It cannot open the admin UI. Do not enable it on a gateway that reaches real equipment.
- The example is not installed by default. Set `OIP_EXAMPLES` to the `examples` directory, or run the gateway from a checkout that contains `examples/`.
- Alarm notification in this build is on-screen and by the configured pipeline actions that are implemented. SMS and voice are not in this build.
- The pipe router makes one or two right-angle bends. A route you do not like can be nudged with a waypoint.
- RuSTy is LGPL and runs as its own program. It is not part of the AGPL grant.

## Contributing a fix or feature

If you want to change the code, follow [CONTRIBUTING.md](CONTRIBUTING.md). Before a pull request, run `scripts/check.sh` (or `pwsh scripts/check.ps1` on Windows). It runs the same build, lint, and tests CI runs, and prints a summary to paste into the pull request.

## Logs

The Windows service writes under `C:\ProgramData\ERIC\gateway`. Remove passwords and plant addresses before you attach a log to an issue.
