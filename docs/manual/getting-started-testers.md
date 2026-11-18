# Getting started for testers

**ERIC v0.1.0 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).** Read [Safety and disclaimer](safety.md) before you connect a device.

This is the path for the 0.1.0-beta. It assumes you have the installer or a built checkout, and a bench device or no device at all.

## Install on Windows

1. Run the ERIC Gateway installer.
2. When it finishes, the ERIC Gateway service is running and Control Canvas has a Start-menu shortcut.
3. Open a browser to `http://127.0.0.1:8090/admin`.
4. Sign in with the administrator account created at install. There is no built-in default password.

The installer does not create a project and does not load example projects.

## Install on Linux

1. Install JDK 21 from a build that carries the Classpath Exception (Temurin is the one the Windows package uses).
2. From a checkout, run `./gradlew :gateway:server:installDist`.
3. Build the admin UI and Control Canvas with `corepack pnpm --filter @oip/admin build` and `corepack pnpm --filter @oip/designer build`.
4. Start the gateway with `OIP_ADMIN_DIST`, `OIP_WEB_DIST`, and `OIP_DOCS_DIR` pointing at those directories. `OIP_DOCS_DIR` is `docs/manual`.
5. Open `http://127.0.0.1:8090/admin`.

## First project

1. In the admin UI, open Projects and create a project. Leave **Public HMI** unchecked.
2. Open Control Canvas at `http://127.0.0.1:8090/designer` and choose that project.
3. You now have an empty project. The HMI URL is `http://127.0.0.1:8090/hmi/<project>/`. It asks you to sign in, because Public HMI is off.

## Import the Brownsville example

The example is a fictional collection and treatment system. It is optional.

1. Set `OIP_EXAMPLES` to the `examples` directory in the checkout, or start the gateway with the checkout as its working directory.
2. In the admin UI, on the Projects page, choose **Import example** and pick Brownsville wastewater. You can also use Control Canvas, File → New from example, or run `examples/brownsville-wastewater/import.ps1`.
3. That project turns Public HMI on for itself. Opening `http://127.0.0.1:8090/hmi/brownsville/` does not ask for a password. The admin UI still does.
4. To remove it, delete the project in the admin UI. Gateway tags that the example added are removed by importing again with replace, or by deleting those tags in the tag browser.

## Connect a Modbus device

1. In the admin UI, open Connections and add a Modbus TCP device. You need the host, the port (usually 502), and the unit id.
2. Add a tag whose address is a holding register on that device.
3. Open the tag browser and confirm the value changes when the device changes. A bad quality code means the host did not answer; check the address and that the PC can reach that port.

## Write a Structured Text program

1. In Control Canvas, open the logic editor.
2. Create a program. A minimal one reads a tag and writes another.
3. Choose Check. The editor calls the bundled RuSTy compiler. Fix the line it names.
4. Choose Deploy. On Windows a banner appears while the soft-PLC is running. That banner means the logic is in a normal Windows process, not on a real-time controller.

## Build a screen

1. In Control Canvas, add a view.
2. Place a numeric display. Bind its value to the tag you created.
3. Place a button. Set its action to write a value to that tag, or to open a popup view.
4. Put the view on a page.
5. Open the HMI URL and confirm the number matches the tag, and that the button writes.

Pipes, faceplates, trends, and the map are in the Control Canvas section of this manual. Use them after this path works.
