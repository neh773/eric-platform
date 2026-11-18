<#
Runs the full-size PRF-008 historian soak from Windows PowerShell and keeps the laptop awake.

The soak JVM reads /proc for memory, so Gradle must run in a WSL2 Linux shell. Docker Desktop's WSL2
integration lets that shell use the Docker engine. This script checks the prerequisites, switches to a
temporary power plan, runs Gradle inside WSL, writes a log, and restores the power plan on exit.

  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows\run-prf008-soak.ps1 -Distro Ubuntu -RepoInWsl ~/open-industrial-platform
  Add -CheckOnly to run the checks and stop.

No secrets are used. The database password in the harness is a throwaway for a container on a private bridge.
#>
param(
    [Parameter(Mandatory = $true)][string]$Distro,
    [Parameter(Mandatory = $true)][string]$RepoInWsl,
    [string]$Date = (Get-Date -Format "yyyy-MM-dd"),
    [switch]$CheckOnly
)
$ErrorActionPreference = "Stop"
$repoWin = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$label = "laptop (4 cores, 16 GB, Windows, Docker Desktop)"

function Fail($m) { Write-Host "FAIL: $m" -ForegroundColor Red; exit 1 }
function Wsl([string]$cmd) { (& wsl.exe -d $Distro -e bash -lc $cmd | Out-String).Trim() }

# 1. Checks
if ($env:OS -ne "Windows_NT") { Fail "run this on Windows" }
$mem = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$cores = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
Write-Host "host: $cores logical processors, $mem GB RAM"
$onAc = (Get-CimInstance -ClassName BatteryStatus -Namespace root\wmi -ErrorAction SilentlyContinue | Select-Object -First 1)
if ($onAc -and -not $onAc.PowerOnline) { Fail "laptop is on battery; plug it in" }
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { Fail "wsl.exe not found" }
$wslMemKb = Wsl "awk '/MemTotal/ {print `$2}' /proc/meminfo"
$wslCpu = Wsl "nproc"
$wslGb = [math]::Round([double]$wslMemKb / 1MB, 1)
Write-Host "WSL '$Distro': $wslCpu processors, $wslGb GB (MemTotal)"
if ([int]$wslCpu -lt 4) { Fail "WSL sees $wslCpu processors; set processors=4 in %UserProfile%\.wslconfig and run wsl --shutdown" }
if ($wslGb -lt 11) { Fail "WSL has $wslGb GB; set memory=14GB in %UserProfile%\.wslconfig and run wsl --shutdown" }
$java = Wsl "java -version 2>&1 | head -1"
Write-Host "java in WSL: $java"
if ($java -notmatch '"21\.') { Fail "JDK 21 is required in WSL" }
$dockerOs = Wsl "docker version --format '{{.Server.Os}}' 2>&1"
if ($dockerOs -ne "linux") { Fail "docker in WSL does not reach a Linux engine (got: $dockerOs); enable WSL integration for '$Distro' in Docker Desktop" }
$gitHead = Wsl "cd $RepoInWsl && git rev-parse HEAD && git status --porcelain | wc -l"
Write-Host "repo in WSL (HEAD, then count of changed files):`n$gitHead"
$heavy = Get-Process | Where-Object { $_.WorkingSet64 -gt 800MB -and $_.ProcessName -notmatch '^(vmmem|vmmemWSL|com.docker.backend|Docker Desktop|wslhost|powershell|pwsh)' }
if ($heavy) { Write-Host "WARNING: close these first:"; $heavy | Sort-Object WorkingSet64 -Descending | Format-Table ProcessName, @{n="MB";e={[int]($_.WorkingSet64/1MB)}} -AutoSize | Out-String | Write-Host }
if ($CheckOnly) { Write-Host "checks passed"; exit 0 }

# 2. Output folders
$out = Join-Path $repoWin "bench\results\laptop\$Date\prf-008"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$outWsl = Wsl "wslpath -a '$($out -replace '\\','/')'"
$log = Join-Path $out "gradle.log"
@(
    "label: $label",
    "started: $((Get-Date).ToString('o'))",
    "host: $cores logical processors, $mem GB RAM",
    "wsl: $wslCpu processors, $wslGb GB",
    "java: $java",
    "repo head: $(($gitHead -split "`n")[0])",
    "docker: $(Wsl 'docker version --format "{{.Server.Version}} {{.Server.Os}}"')"
) | Set-Content (Join-Path $out "environment.txt")

# 3. Temporary power plan, restored in finally
$original = ((powercfg /getactivescheme) -join " ") -replace '.*([0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}).*', '$1'
$dup = ((powercfg /duplicatescheme $original) -join " ") -replace '.*([0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}).*', '$1'
$exit = 1
try {
    powercfg /setactive $dup
    powercfg /change standby-timeout-ac 0
    powercfg /change hibernate-timeout-ac 0
    powercfg /change monitor-timeout-ac 0
    powercfg /setacvalueindex $dup SUB_BUTTONS LIDACTION 0
    powercfg /setactive $dup
    Write-Host "power plan $dup active (original $original)"

    # 4. Soak, in WSL. Gradle exit code is the verdict gate.
    $cmd = "cd $RepoInWsl && ./gradlew :test-infra:load-soak:historianSoakTest -PloadSoakSize=full -PloadSoakHeap=8g -PloadSoakReport='$outWsl' --no-daemon 2>&1 | tee '$outWsl/gradle.log'; exit `${PIPESTATUS[0]}"
    Write-Host "soak started $(Get-Date -Format o); about 3.6 h"
    & wsl.exe -d $Distro -e bash -lc $cmd
    $exit = $LASTEXITCODE
} finally {
    powercfg /setactive $original
    powercfg /delete $dup
    "finished: $((Get-Date).ToString('o')) gradle exit code: $exit" | Add-Content (Join-Path $out "environment.txt")
    Wsl "docker ps -a --filter name=oip-prf008 --format '{{.Names}} {{.Status}}'; docker network ls --filter name=oip-prf008 --format '{{.Name}}'" | Set-Content (Join-Path $out "leftovers.txt")
}
Write-Host "gradle exit code $exit (0 = test passed). Results in $out"
exit $exit
