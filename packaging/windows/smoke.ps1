# Install both Windows packages, exercise the gateway and eric-plc, then uninstall.
# ProgramData stays when the checkbox is off, and is removed when -DeleteData is passed.
# Run on Windows after :packaging:windowsInstallers.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$layoutPath = Join-Path $root "packaging\build\layout.properties"
$dist = Join-Path $root "packaging\build\dist"
if (-not (Test-Path $layoutPath)) { throw "missing $layoutPath" }
$layout = @{}
Get-Content $layoutPath | ForEach-Object {
    if ($_ -match '^(.*?)=(.*)$') { $layout[$Matches[1]] = $Matches[2] }
}
$installer = Join-Path $dist $layout.gatewayInstaller
$designer = Join-Path $dist $layout.designerInstaller
if (-not (Test-Path $installer)) { throw "missing $installer" }
if (-not (Test-Path $designer)) { throw "missing $designer" }
$uninstall = Join-Path $PSScriptRoot "uninstall.ps1"
$product = $layout.productName
if (-not $product) { $product = "ERIC" }

# Windows PowerShell 5.1 joins a Start-Process argument array without quotes.
# The log path includes the installer file name, and that name contains a space.
# msiexec then prints its help and waits on OK. /qn hides the dialog, so the
# install never returns and the log file is never created.
function Start-Quoted($path, [string[]]$arguments, $workingDirectory) {
    $rendered = foreach ($arg in $arguments) {
        if ($arg -match '[\s"]') { '"' + ($arg -replace '"', '\"') + '"' } else { $arg }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $path
    $psi.Arguments = ($rendered -join ' ')
    $psi.UseShellExecute = $false
    if ($workingDirectory) { $psi.WorkingDirectory = $workingDirectory }
    return [System.Diagnostics.Process]::Start($psi)
}

function Install-Quiet($path) {
    $log = Join-Path $env:TEMP ("eric-install-" + [IO.Path]::GetFileName($path) + ".log")
    if (Test-Path $log) { Remove-Item $log -Force }
    Write-Host "installing $path"
    $proc = Start-Quoted $path @("/qn", "/norestart", "/l*v", $log)
    $deadline = (Get-Date).AddMinutes(15)
    while (-not $proc.HasExited) {
        if ((Get-Date) -gt $deadline) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            if (Test-Path $log) { Get-Content $log -Tail 40 }
            throw "install of $path did not finish in 15 minutes"
        }
        $bytes = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
        Write-Host "install still running; log bytes $bytes"
        Start-Sleep -Seconds 20
    }
    if ($proc.ExitCode -ne 0 -and $proc.ExitCode -ne 3010) {
        if (Test-Path $log) { Get-Content $log -Tail 40 }
        throw "install of $path exited $($proc.ExitCode)"
    }
}

function Wait-Health {
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $layout.healthUrl -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -match '"status"\s*:\s*"UP"') { return }
        } catch {}
        Start-Sleep -Seconds 2
    }
    throw "no UP response from $($layout.healthUrl)"
}

function Assert-Page($url) {
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 15
    if ($response.StatusCode -ne 200) { throw "$url returned $($response.StatusCode)" }
    Write-Host "$url $($response.StatusCode)"
}

function Assert-Clean($dataShouldExist) {
    Start-Sleep -Seconds 3
    $left = Get-Service -Name $layout.serviceName -ErrorAction SilentlyContinue
    if ($null -ne $left) { throw "service still present: $($left.Status)" }
    # netsh prints blank lines around "No rules match". Without Out-String, $rule is an array,
    # -notmatch returns the blank lines, and the check fails even when the rule is gone.
    $rule = & netsh advfirewall firewall show rule name="$($layout.serviceName)" | Out-String
    if ($rule -notmatch "No rules match") { throw "firewall rule still present:`n$rule" }
    # reg.exe query writes to stderr when the key is gone, and 2>&1 under
    # ErrorActionPreference Stop turns that into a terminating error in Windows PowerShell 5.1.
    if (Test-Path "HKLM:\Software\$product") { throw "registry key HKLM\Software\$product still present" }
    $menu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$($layout.menuGroup)"
    if (Test-Path $menu) { throw "start-menu folder still present: $menu" }
    foreach ($dir in @("C:\Program Files\$($layout.serviceName)", "C:\Program Files\Control Canvas")) {
        if (Test-Path $dir) { throw "program files still present: $dir" }
    }
    $data = Join-Path $env:ProgramData $product
    $pin = Join-Path $data "plc-resources.json"
    if (Test-Path $pin) { throw "PLC pinning file still present: $pin" }
    if ($dataShouldExist) {
        if (-not (Test-Path (Join-Path $data "keep-me.txt"))) { throw "ProgramData was removed when the checkbox was off" }
    } else {
        if (Test-Path $data) { throw "ProgramData still present after -DeleteData" }
    }
}

function Invoke-Timing {
    $plc = "C:\Program Files\$($layout.serviceName)\app\plc"
    $engine = Join-Path $plc "eric-plc.exe"
    if (-not (Test-Path $engine)) { throw "eric-plc.exe was not installed" }
    $work = Join-Path $env:TEMP "eric-plc-timing"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null
    Set-Content -Path (Join-Path $work "mapping.json") -Value '{"inputs":[{"address":"%IX0.0","tag":"plc/bit","type":"BOOL"}],"outputs":[]}'
    @"
period_ms = 10
fifo_priority = 50
mlock = true
max_consecutive_overruns = 3
bridge_timeout_ms = 1000
shm_path = "$($work -replace '\\','/')/image.bin"
program = ""
entry = ""
mapping = "mapping.json"
status_path = "status.json"
label = "CI Windows runner, not reference"
control_socket = ""
"@ | Set-Content -Path (Join-Path $work "timing.toml")
    $run = Start-Quoted $engine @("run", "--config", (Join-Path $work "timing.toml"), "--duration-ms", "3000") $work
    $run.WaitForExit() | Out-Null
    if ($run.ExitCode -ne 0) {
        Get-Content (Join-Path $work "run.err") -ErrorAction SilentlyContinue
        throw "eric-plc timing run exited $($run.ExitCode)"
    }
    $stats = & $engine stats --status (Join-Path $work "status.json")
    Write-Host "CI Windows runner, not reference"
    Write-Host ($stats -join "`n")
    if ($env:GITHUB_STEP_SUMMARY) {
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "## eric-plc timing (CI Windows runner, not reference)`n`n$($stats -join "`n")"
    }
}

function Enable-Msvc {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { return }
    $install = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $install) { return }
    $vcvars = Join-Path $install "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) { return }
    cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
        if ($_ -match '^(.*?)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
    }
}

# Deploy a Structured Text program through the installed gateway, the way the Logic page does, with Visual
# Studio hidden: the bundled LLVM linker must be enough. The program reads through a REF_TO that only its
# constructors set, so a scan with a missed constructor table faults instead of reporting 42.
function Invoke-St {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    $hidden = "$vswhere.eric-hidden"
    if (Test-Path $vswhere) { Rename-Item -Path $vswhere -NewName ([IO.Path]::GetFileName($hidden)) }
    try {
        $plc = "C:\Program Files\$($layout.serviceName)\app\plc"
        foreach ($f in @("llvm\lld-link.exe", "rusty\plc.exe", "rusty\iec61131std.dll", "rusty\stdlib\include")) {
            if (-not (Test-Path (Join-Path $plc $f))) { throw "PLC bundle file missing: $f" }
        }
        $base = "http://127.0.0.1:8090/api/v1"
        $bytes = New-Object byte[] 18
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $secret = "Pw-" + [Convert]::ToBase64String($bytes)
        $setup = Invoke-WebRequest -Uri "$base/setup/admin" -Method Post -ContentType "application/json" -SessionVariable web -UseBasicParsing `
            -Body (@{ username = "ciadmin"; password = $secret } | ConvertTo-Json)
        $csrf = ($setup.Content | ConvertFrom-Json).csrfToken
        if (-not $csrf) { throw "first-run admin did not return a session" }
        $headers = @{ "X-CSRF-Token" = $csrf }
        function Call($method, $path, $body) {
            $req = @{ Uri = "$base$path"; Method = $method; WebSession = $web; Headers = $headers; UseBasicParsing = $true; ContentType = "application/json" }
            if ($null -ne $body) { $req.Body = ($body | ConvertTo-Json -Depth 6) }
            $r = Invoke-WebRequest @req
            if ($r.Content) { return $r.Content | ConvertFrom-Json }
        }
        $source = @"
PROGRAM SmokeLink
VAR_OUTPUT answer AT %QD0 : DINT; END_VAR
VAR x : DINT := 42; p : REF_TO DINT := REF(x); delay : TON; END_VAR
delay(IN := TRUE, PT := T#50ms);
answer := p^;
END_PROGRAM
"@
        Call Post "/logic/programs" @{ name = "SmokeLink" } | Out-Null
        Call Put "/logic/programs/SmokeLink" @{ source = $source } | Out-Null
        Call Put "/logic/mapping" @{ inputs = @(); outputs = @(@{ address = "%QD0"; tag = "[default]Smoke/Answer"; type = "DINT"; name = "answer" }) } | Out-Null
        $deploy = Call Post "/logic/deploy" @{ program = "SmokeLink" }
        if (-not $deploy.ok) { throw "deploy failed without Visual Studio: $($deploy | ConvertTo-Json -Depth 6)" }
        $deadline = (Get-Date).AddSeconds(60)
        $answer = $null
        do {
            Start-Sleep -Seconds 1
            $watch = Call Get "/logic/watch" $null
            $row = @($watch.variables) | Where-Object { $_.name -eq "answer" } | Select-Object -First 1
            if ($watch.state -eq "running" -and $null -ne $row) { $answer = $row.value }
        } while ((Get-Date) -lt $deadline -and "$answer" -ne "42")
        if ("$answer" -ne "42") { throw "deployed program did not report answer=42 (state $($watch.state), answer $answer)" }
        Call Post "/logic/stop" $null | Out-Null
        Write-Host "deployed a Structured Text program with the bundled LLVM linker and no Visual Studio: running, answer=42"
        if ($env:GITHUB_STEP_SUMMARY) {
            Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "## PLC deploy without Visual Studio`n`nvswhere hidden; Logic deploy linked with plc\llvm\lld-link.exe; program running, answer=42."
        }
    } finally {
        if (Test-Path $hidden) { Rename-Item -Path $hidden -NewName ([IO.Path]::GetFileName($vswhere)) }
    }
}

Write-Host "gateway installer: $installer"
Write-Host "designer installer: $designer"
Install-Quiet $installer
Install-Quiet $designer

$deadline = (Get-Date).AddMinutes(3)
$service = $null
while ((Get-Date) -lt $deadline) {
    $service = Get-Service -Name $layout.serviceName -ErrorAction SilentlyContinue
    if ($null -ne $service -and $service.Status -eq "Running") { break }
    Start-Sleep -Seconds 2
}
if ($null -eq $service -or $service.Status -ne "Running") { throw "service '$($layout.serviceName)' is not running" }
Wait-Health
Assert-Page "http://127.0.0.1:8090/admin"
Assert-Page "http://127.0.0.1:8090/designer"
Assert-Page "http://127.0.0.1:8090/hmi"
# The Start-menu "ERIC Admin" shortcut and the kept install log (also written after a successful install).
$adminLnk = Get-ChildItem -Path "$env:ProgramData\Microsoft\Windows\Start Menu\Programs" -Recurse -Filter "$($layout.adminLauncherName).lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $adminLnk) { throw "Start-menu shortcut '$($layout.adminLauncherName)' is missing" }
Write-Host "shortcut $($adminLnk.FullName)"
if (-not (Test-Path $layout.installLog)) { throw "install log was not kept at $($layout.installLog)" }
Write-Host "install log kept at $($layout.installLog) ($((Get-Item $layout.installLog).Length) bytes)"
# The install check a user runs must find nothing to fix on a working install.
$installDir = Join-Path $env:ProgramFiles $layout.gatewayName
$checkScript = Join-Path $installDir "app\check-install.ps1"
if (-not (Test-Path $checkScript)) { throw "check-install.ps1 was not installed" }
$checkOut = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $checkScript | Out-String
Write-Host $checkOut
if ($checkOut -match "(?m)^\s+FIX\s") { throw "check-install.ps1 reported problems on a fresh install" }
# A clean machine has no Visual C++ runtime: eric-plc.exe links it statically, and the runtime DLLs ship app-local.
Enable-Msvc
$dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
if ($null -ne $dumpbin) {
    $deps = & $dumpbin.Source /dependents (Join-Path $installDir "app\plc\eric-plc.exe") | Out-String
    if ($deps -match "(?i)vcruntime140|msvcp140") { throw "eric-plc.exe still needs the Visual C++ runtime:`n$deps" }
    Write-Host "eric-plc.exe has no Visual C++ runtime dependency"
}
foreach ($dll in @("app\plc\rusty\vcruntime140.dll", "app\plc\vcruntime140.dll")) {
    if (-not (Test-Path (Join-Path $installDir $dll))) { throw "missing $($dll) (app-local Visual C++ runtime)" }
}
$rule = ""
$ruleDeadline = (Get-Date).AddSeconds(30)
do {
    $rule = & netsh advfirewall firewall show rule name="$($layout.serviceName)" | Out-String
    if ($rule -notmatch "No rules match") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $ruleDeadline)
if ($rule -match "No rules match") { throw "firewall rule was not added" }
Write-Host "firewall rule present"
if ($rule -notmatch "Domain,Private,Public") { throw "firewall rule does not cover every profile (Windows Home uses Public):`n$rule" }
$params = Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$($layout.serviceName)\Parameters"
$extra = @($params.AppEnvironmentExtra) -join "`n"
if ($extra -notmatch 'ERIC_ADMIN_DIST=') { throw "service environment is missing ERIC_ADMIN_DIST: $extra" }
if ($extra -notmatch 'ERIC_WEB_DIST=' -or $extra -notmatch 'ERIC_DOCS_DIR=' -or $extra -notmatch 'ERIC_PLC_COMPILER=') {
    throw "service environment is missing an ERIC_ path: $extra"
}
if ($extra -match '(?m)^OIP_') { throw "service environment still sets a deprecated OIP_ variable: $extra" }
if ([string]$params.AppStdout -notmatch 'service-stdout\.log$') { throw "service stdout is not captured ($($params.AppStdout))" }
if ([int]$params.AppStopMethodConsole -lt 10000) { throw "service stop does not wait long enough for a clean exit ($($params.AppStopMethodConsole))" }
Write-Host "service environment and stdout capture are current"
Stop-Service -Name $layout.serviceName -Force
Start-Sleep -Seconds 2
$stoppedRule = & netsh advfirewall firewall show rule name="$($layout.serviceName)" | Out-String
if ($stoppedRule -notmatch "No rules match") { throw "firewall rule survived service stop:`n$stoppedRule" }
Write-Host "firewall rule removed on service stop"
Start-Service -Name $layout.serviceName
Wait-Health
$menu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$($layout.menuGroup)"
if (-not (Test-Path $menu)) { throw "no Start-menu folder at $menu" }
if (-not (Test-Path (Join-Path $layout.dataDir "gateway.yaml"))) { throw "data dir was not created at $($layout.dataDir)" }
Invoke-Timing
Invoke-St

$data = Join-Path $env:ProgramData $product
New-Item -ItemType Directory -Force -Path $data | Out-Null
Set-Content -Path (Join-Path $data "keep-me.txt") -Value "keep"
Set-Content -Path (Join-Path $data "plc-resources.json") -Value '{"plcCpus":[1]}'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $uninstall -Silent -Product $product -Installers "$installer|$designer"
Assert-Clean $true
Write-Host "keep-data uninstall left ProgramData and removed the install"

Install-Quiet $installer
Set-Content -Path (Join-Path $data "keep-me.txt") -Value "drop"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $uninstall -Silent -DeleteData -Product $product -Installers $installer
Assert-Clean $false
Write-Host "delete-data uninstall removed ProgramData"
