<#
Local equivalent of the Windows installers workflow, plus restart and reboot checks.
Run from an elevated PowerShell in the repository root, after building the installers:
  .\gradlew.bat :packaging:windowsInstallers --no-daemon

  scripts\windows\check-installer.ps1 -Stage Install      silent install, service Running, health UP, shortcut, data dir
  scripts\windows\check-installer.ps1 -Stage Restart      Restart-Service, health UP again
  scripts\windows\check-installer.ps1 -Stage AfterReboot  run after a reboot and sign-in: service came up by itself
  scripts\windows\check-installer.ps1 -Stage Reinstall    install again over the installed product, service still Running
  scripts\windows\check-installer.ps1 -Stage Uninstall    uninstall, service gone, data dir kept
  scripts\windows\check-installer.ps1 -Stage All          Install, Restart, Reinstall, Uninstall (no reboot)

Names, health URL and paths come from packaging\build\layout.properties, as packaging\windows\smoke.ps1 does.
Writes a transcript to bench\results\laptop\<date>\installer\<stage>.txt. No password is needed or used.
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet("Install", "Restart", "AfterReboot", "Reinstall", "Uninstall", "All")][string]$Stage,
    [string]$Date = (Get-Date -Format "yyyy-MM-dd")
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw "run from an elevated PowerShell" }
$layoutPath = Join-Path $root "packaging\build\layout.properties"
if (-not (Test-Path $layoutPath)) { throw "missing $layoutPath; build with .\gradlew.bat :packaging:windowsInstallers" }
$layout = @{}
Get-Content $layoutPath | ForEach-Object { if ($_ -match '^(.*?)=(.*)$') { $layout[$Matches[1]] = $Matches[2] } }
$installer = Join-Path $root "packaging\build\dist\$($layout.gatewayInstaller)"
if (-not (Test-Path $installer)) { throw "missing $installer" }
$outDir = Join-Path $root "bench\results\laptop\$Date\installer"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Start-Transcript -Path (Join-Path $outDir "$Stage.txt") -Force | Out-Null
$label = "laptop (4 cores, 16 GB, Windows, Docker Desktop)"
Write-Host "label: $label; stage: $Stage; $(Get-Date -Format o)"
Write-Host "installer: $installer"
Write-Host "sha256: $((Get-FileHash $installer -Algorithm SHA256).Hash.ToLower())"

function Wait-Service([string]$name, [string]$status, [int]$minutes = 3) {
    $deadline = (Get-Date).AddMinutes($minutes)
    while ((Get-Date) -lt $deadline) {
        $s = Get-Service -Name $name -ErrorAction SilentlyContinue
        if ($status -eq "Absent") { if ($null -eq $s) { return } }
        elseif ($null -ne $s -and $s.Status -eq $status) { return }
        Start-Sleep -Seconds 2
    }
    throw "service '$name' did not reach $status within $minutes min"
}
function Wait-Health {
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $layout.healthUrl -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200 -and $r.Content -match '"status"\s*:\s*"UP"') { Write-Host "health UP: $($layout.healthUrl)"; return }
        } catch {}
        Start-Sleep -Seconds 2
    }
    throw "no UP response from $($layout.healthUrl)"
}
function Start-Quoted($path, [string[]]$arguments) {
    $rendered = foreach ($arg in $arguments) {
        if ($arg -match '[\s"]') { '"' + ($arg -replace '"', '\"') + '"' } else { $arg }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $path
    $psi.Arguments = ($rendered -join ' ')
    $psi.UseShellExecute = $false
    return [System.Diagnostics.Process]::Start($psi)
}
function Install-Gateway {
    $log = Join-Path $outDir "msi-install.log"
    $p = Start-Quoted $installer @("/qn", "/norestart", "/l*v", $log)
    $p.WaitForExit() | Out-Null
    if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { Get-Content $log -Tail 80; throw "install exited $($p.ExitCode)" }
    Write-Host "install exit code $($p.ExitCode)"
    Wait-Service $layout.serviceName "Running"
    Wait-Health
}
function Show-Service {
    $s = Get-CimInstance Win32_Service -Filter "Name='$($layout.serviceName)'"
    Write-Host "service: $($s.Name) state=$($s.State) start=$($s.StartMode) pid=$($s.ProcessId) account=$($s.StartName)"
}
function Check-Install {
    Show-Service
    $menu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$($layout.menuGroup)"
    $lnk = @(); if (Test-Path $menu) { $lnk = @(Get-ChildItem $menu -Filter *.lnk) }
    if ($lnk.Count -lt 1) { throw "no Start-menu shortcut under $menu" }
    Write-Host "start menu: $($lnk.Name -join ', ')"
    if (-not (Test-Path (Join-Path $layout.dataDir "gateway.yaml"))) { throw "data dir not created at $($layout.dataDir)" }
    Write-Host "data dir: $($layout.dataDir)"
}
function Uninstall-Gateway {
    $p = Start-Process -FilePath $installer -ArgumentList @("uninstall") -Wait -PassThru
    if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { throw "uninstall exited $($p.ExitCode)" }
    Wait-Service $layout.serviceName "Absent" 1
    if (-not (Test-Path $layout.dataDir)) { throw "uninstall removed $($layout.dataDir)" }
    $sc = & sc.exe query $layout.serviceName 2>&1 | Out-String
    Write-Host "sc query after uninstall: $($sc.Trim())"
    Write-Host "uninstalled; no service; data dir kept at $($layout.dataDir)"
}

try {
    if ($Stage -in "Install", "All") { Install-Gateway; Check-Install }
    if ($Stage -in "Restart", "All") {
        Restart-Service -Name $layout.serviceName -Force
        Wait-Service $layout.serviceName "Running"; Wait-Health; Show-Service
    }
    if ($Stage -eq "AfterReboot") {
        $boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
        Write-Host "last boot: $boot"
        Wait-Service $layout.serviceName "Running"; Wait-Health; Show-Service
    }
    if ($Stage -in "Reinstall", "All") {
        $before = (Get-CimInstance Win32_Service -Filter "Name='$($layout.serviceName)'").ProcessId
        Install-Gateway; Check-Install
        Write-Host "pid before $before, after $((Get-CimInstance Win32_Service -Filter "Name='$($layout.serviceName)'").ProcessId)"
    }
    if ($Stage -in "Uninstall", "All") { Uninstall-Gateway }
    Write-Host "PASS $Stage"
} catch {
    Write-Host "FAIL $Stage : $($_.Exception.Message)"
    Stop-Transcript | Out-Null
    exit 1
}
Stop-Transcript | Out-Null
