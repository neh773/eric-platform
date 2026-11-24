# ERIC Gateway install check. Run in PowerShell (as administrator for the service and port details):
#   powershell -ExecutionPolicy Bypass -File "C:\Program Files\ERIC Gateway\app\check-install.ps1"
# Writes the same report to C:\ProgramData\ERIC\check-install.txt. It contains no passwords or keys.
$ErrorActionPreference = "Continue"
$app = Split-Path -Parent $MyInvocation.MyCommand.Path
$install = Split-Path -Parent $app
$dataDir = "C:\ProgramData\ERIC\gateway"
$report = New-Object System.Collections.Generic.List[string]
$problems = 0
function Line([string]$text) { $report.Add($text); Write-Host $text }
function Ok([string]$text) { Line "  OK    $text" }
function Bad([string]$text) { $script:problems++; Line "  FIX   $text" }

Line "ERIC Gateway install check, $(Get-Date -Format s)"
Line "Install folder: $install"
Line ""
Line "Computer"
$os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
if ($os) { Line "  Windows $($os.Caption) build $($os.BuildNumber), $($os.OSArchitecture)" }
if ([Environment]::Is64BitOperatingSystem) { Ok "64-bit Windows" } else { Bad "ERIC needs 64-bit Windows 10 or 11" }
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($admin) { Ok "running as administrator" } else { Line "  NOTE  not running as administrator: service and port owner details may be missing" }

Line ""
Line "Files"
foreach ($f in @("ERIC Gateway.exe", "ERIC Admin.exe", "runtime\bin\java.dll", "app\admin\index.html", "app\web\index.html", "app\plc\eric-plc.exe", "app\plc\rusty\plc.exe")) {
  if (Test-Path (Join-Path $install $f)) { Ok $f } else { Bad "missing $($f): reinstall ERIC Gateway" }
}

Line ""
Line "Bundled programs start (a missing Microsoft runtime shows here)"
foreach ($exe in @(@("app\plc\eric-plc.exe", "help"), @("app\plc\rusty\plc.exe", "--version"))) {
  $path = Join-Path $install $exe[0]
  if (-not (Test-Path $path)) { continue }
  $p = Start-Process -FilePath $path -ArgumentList $exe[1] -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\eric-check-out.txt" -RedirectStandardError "$env:TEMP\eric-check-err.txt"
  if ($p.ExitCode -eq -1073741515) { Bad "$($exe[0]) cannot start: a Microsoft runtime DLL is missing (STATUS_DLL_NOT_FOUND). Please report this with this file." }
  elseif ($p.ExitCode -eq 0) { Ok "$($exe[0]) starts" }
  else { Line "  NOTE  $($exe[0]) exited with $($p.ExitCode)" }
}

Line ""
Line "Service"
$svc = Get-Service -Name "ERIC Gateway" -ErrorAction SilentlyContinue
if (-not $svc) { Bad "the 'ERIC Gateway' service is not installed: reinstall, and accept the administrator prompt" }
else {
  if ($svc.Status -eq "Running") { Ok "service is Running" } else { Bad "service is $($svc.Status): start it in services.msc, then run this check again" }
  $cfg = Get-CimInstance Win32_Service -Filter "Name='ERIC Gateway'" -ErrorAction SilentlyContinue
  if ($cfg) { Line "  start mode: $($cfg.StartMode), account: $($cfg.StartName)" }
}

Line ""
Line "Ports"
foreach ($port in @(8090, 8060, 4840)) {
  $listen = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($listen) {
    $proc = Get-Process -Id $listen.OwningProcess -ErrorAction SilentlyContinue
    Line "  port $port is used by $($proc.ProcessName) (pid $($listen.OwningProcess))"
  } else { Line "  port $port is free" }
}
try {
  $health = Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/v1/health" -TimeoutSec 5
  Ok "gateway answers on http://127.0.0.1:8090 (version $($health.version))"
} catch { Bad "nothing answers on http://127.0.0.1:8090/api/v1/health: see the service log below" }

Line ""
Line "PLC deploy"
# Deploy links programs with the bundled LLVM linker; no Visual Studio or Build Tools are needed.
$lld = Join-Path $install "app\plc\llvm\lld-link.exe"
if (-not (Test-Path $lld)) { Bad "missing app\plc\llvm\lld-link.exe (the PLC linker): reinstall ERIC Gateway" }
else {
  $p = Start-Process -FilePath $lld -ArgumentList "--version" -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\eric-check-out.txt" -RedirectStandardError "$env:TEMP\eric-check-err.txt"
  if ($p.ExitCode -eq 0) { Ok "PLC linker starts ($((Get-Content "$env:TEMP\eric-check-out.txt" -ErrorAction SilentlyContinue | Select-Object -First 1)))" }
  else { Bad "app\plc\llvm\lld-link.exe exited with $($p.ExitCode): reinstall ERIC Gateway" }
}
foreach ($f in @("app\plc\rusty\iec61131std.dll", "app\plc\rusty\stdlib\include")) {
  if (Test-Path (Join-Path $install $f)) { Ok $f } else { Bad "missing $($f): reinstall ERIC Gateway" }
}

Line ""
Line "Install log"
$installLog = "C:\ProgramData\ERIC\logs\install.log"
if (Test-Path $installLog) {
  $last = Select-String -Path $installLog -Pattern "Installation (success|failed)|completed successfully|Return value 3" | Select-Object -Last 3
  Ok "install log kept at $installLog"
  $last | ForEach-Object { Line "  | $($_.Line)" }
} else { Line "  NOTE  no install log at $installLog (a failed install leaves its log in %TEMP% as MSI*.LOG)" }

Line ""
Line "Service log (last 30 lines): $install\service-stdout.log"
$svcLog = Join-Path $install "service-stdout.log"
if (Test-Path $svcLog) { Get-Content $svcLog -Tail 30 | ForEach-Object { Line "  | $_" } } else { Line "  (no service log yet)" }
$startupLog = Join-Path $dataDir "logs\startup.log"
Line ""
Line "Startup log: $startupLog"
if (Test-Path $startupLog) {
  Get-Content $startupLog -Tail 40 | ForEach-Object { Line "  | $_" }
  if (Select-String -Path $startupLog -Pattern "FAILED" -Quiet) { Bad "startup reported a failure: see the FAILED lines above" }
} else { Line "  (no startup log yet: the gateway has not started with 0.1.1 or later)" }
$logDir = Join-Path $dataDir "logs"
$newest = Get-ChildItem $logDir -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Line ""
Line "Gateway log (last 30 lines): $(if ($newest) { $newest.FullName } else { $logDir })"
if ($newest) { Get-Content $newest.FullName -Tail 30 | ForEach-Object { Line "  | $_" } }

Line ""
if ($problems -eq 0) { Line "No problems found. Open http://127.0.0.1:8090/admin" } else { Line "$problems thing(s) to fix, marked FIX above." }
$out = "C:\ProgramData\ERIC\check-install.txt"
try {
  New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
  $report | Set-Content -Path $out -Encoding UTF8
  Write-Host "Report saved to $out"
} catch { Write-Host "Could not save the report to $out ($($_.Exception.Message))" }
