# Troubleshooting on Windows

**ERIC is beta software. Use it only with hardware for testing purposes under safe conditions.**

## Where the logs are

| What | Path |
| --- | --- |
| Install log (kept after every install, also a successful one) | `C:\ProgramData\ERIC\logs\install.log` |
| Install log of a failed install | `%TEMP%`, the newest `MSI*.LOG` file. Or run the installer again with `/L*v "%USERPROFILE%\Desktop\eric-install.log"`. |
| Startup log (each startup step, and the error if startup stopped) | `C:\ProgramData\ERIC\gateway\logs\startup.log` (the start before is in `startup.previous.log`) |
| Service log (what the gateway prints, including why it stopped) | `C:\Program Files\ERIC Gateway\service-stdout.log` |
| Gateway logs | `C:\ProgramData\ERIC\gateway\logs` (also on the admin **Logs** page) |
| Install check report | `C:\ProgramData\ERIC\check-install.txt` |

Windows also records installer errors in Event Viewer, under Windows Logs, Application, with source **MsiInstaller**.

## Run the install check

In PowerShell as administrator:

`powershell -ExecutionPolicy Bypass -File "C:\Program Files\ERIC Gateway\app\check-install.ps1"`

It checks the installed files, that the bundled programs start, the service, ports 8090, 8060 and 4840, the health page, the PLC tools and the install log. It prints the last lines of the service and gateway logs and saves everything to `C:\ProgramData\ERIC\check-install.txt`. That file has no passwords or keys. Attach it when you report a problem.

## The service runs but the admin page does not open

1. Open the page **on the gateway computer**: `http://localhost:8090/admin` or `http://127.0.0.1:8090/admin`. The web port is **8090**. Port 8060 is the gateway-to-gateway network port and does not serve web pages; 8043 is HTTPS, which is off until you turn it on. Type `http://`, not `https://`. A new install serves plain HTTP only, so there is no certificate warning. HTTPS is off until you turn it on in Network.
2. The first start can take up to a minute. **ERIC Admin** in the Start menu waits for the service and then opens the page. If the service never answers, its window lists what to check.
3. The first administrator account can only be created from a browser on the gateway computer. From another computer, the sign-in page shows but there is no account yet. Create the account on the gateway computer first.
4. From another computer, use `http://<gateway computer name or address>:8090/admin`. The gateway adds a Windows Firewall rule for TCP port 8090 on all network profiles (Domain, Private and Public; Windows Home usually uses Public). Check it in Windows Defender Firewall, Advanced settings, Inbound Rules, **ERIC Gateway**. A different firewall or antivirus product may block the port.
5. Look at the end of the service log, `C:\Program Files\ERIC Gateway\service-stdout.log`.

## The service does not stay running

If startup fails, the gateway writes the error to `startup.log` and exits, so services.msc shows the service as stopped (it does not stay half-started). If an optional part such as the OPC UA server, scripts or the gateway network fails, the gateway still opens the web pages and the Overview page lists the problem. If startup takes longer than 2 minutes, `startup.log` records where it is waiting; after 10 minutes the gateway stops with that message.

- **Port in use.** If another program uses port 8090, the service log says the port is already used. Stop the other program, or set `http: port:` in `C:\ProgramData\ERIC\gateway\gateway.yaml`, then start **ERIC Gateway** in services.msc.
- **Gateway network port 8060.** If another program uses 8060, the gateway still starts. The Network page shows an alert with the setting to change.

## The installer does not start or stops

- **SmartScreen.** The beta installers are not code-signed. On "Windows protected your PC", choose **More info**, then **Run anyway**.
- **Administrator prompt.** The gateway installs a Windows service, so the installer needs administrator rights. A silent install (`/qn`) must run from an administrator Command Prompt.
- **Nothing else is needed.** The installer carries its own Java runtime and the Microsoft Visual C++ runtime files. Java, redistributables, WebView2 and developer tools are not required. PLC deploy alone needs Visual Studio Build Tools; see [Install and uninstall](install.md).
