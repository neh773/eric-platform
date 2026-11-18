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
    $rule = & netsh advfirewall firewall show rule name="$($layout.serviceName)"
    if ($rule -notmatch "No rules match") { throw "firewall rule still present" }
    $reg = & reg.exe query "HKLM\Software\$product" 2>&1
    if ($LASTEXITCODE -eq 0) { throw "registry key HKLM\Software\$product still present: $reg" }
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

function Invoke-St {
    Enable-Msvc
    $plc = "C:\Program Files\$($layout.serviceName)\app\plc"
    $compiler = Join-Path $plc "rusty\plc.exe"
    if (-not (Test-Path $compiler)) { throw "RuSTy plc.exe was not installed" }
    $work = Join-Path $env:TEMP "eric-st-smoke"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null
    $source = Join-Path $plc "examples\sealin\sealin.st"
    $include = Join-Path $plc "rusty\stdlib\include"
    $obj = Join-Path $work "sealin.obj"
    $hw = Join-Path $work "hwmap.json"
    $compile = Start-Quoted $compiler @($source, "-i", (Join-Path $include "*.st"), "-c", "--hwmap-file=$hw", "-o", $obj)
    $compile.WaitForExit() | Out-Null
    if ($compile.ExitCode -ne 0 -or -not (Test-Path $obj)) {
        Get-Content (Join-Path $work "compile.out"), (Join-Path $work "compile.err") -ErrorAction SilentlyContinue
        throw "RuSTy did not produce a Windows object ($($compile.ExitCode))"
    }
    $link = Get-Command link.exe -ErrorAction SilentlyContinue
    $dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
    if ($null -eq $link -or $null -eq $dumpbin) { throw "link.exe and dumpbin.exe must be on PATH to link the RuSTy object" }
    $dll = Join-Path $work "sealin.dll"
    $exports = New-Object System.Collections.Generic.List[string]
    foreach ($line in (& dumpbin.exe /SYMBOLS $obj)) {
        if ($line -match 'UNDEF') { continue }
        if ($line -match 'External\s+\|\s+(\S+)\s*$') { $exports.Add($Matches[1]) }
    }
    $linkArgs = @("/nologo", "/DLL")
    foreach ($name in $exports) { $linkArgs += "/EXPORT:$name" }
    $linkArgs += @("/OUT:$dll", $obj, (Join-Path $plc "rusty\iec61131std.lib"), "kernel32.lib", "advapi32.lib", "userenv.lib", "ws2_32.lib", "ntdll.lib", "bcrypt.lib", "msvcrt.lib", "vcruntime.lib", "ucrt.lib")
    $linked = Start-Quoted $link.Source $linkArgs
    $linked.WaitForExit() | Out-Null
    if ($linked.ExitCode -ne 0 -or -not (Test-Path $dll)) {
        Get-Content (Join-Path $work "link.out"), (Join-Path $work "link.err") -ErrorAction SilentlyContinue
        throw "the Microsoft linker did not produce sealin.dll"
    }
    Copy-Item (Join-Path $plc "rusty\iec61131std.dll") (Join-Path $work "iec61131std.dll") -Force
    Copy-Item (Join-Path $plc "examples\sealin\mapping.json") (Join-Path $work "mapping.json") -Force
    @"
period_ms = 20
fifo_priority = 50
mlock = false
max_consecutive_overruns = 3
bridge_timeout_ms = 1000
shm_path = "$($work -replace '\\','/')/image.bin"
program = "sealin.dll"
entry = "SealIn"
instance = "SealIn_instance"
hwmap = "hwmap.json"
mapping = "mapping.json"
status_path = "status.json"
label = "CI Windows runner, not reference"
control_socket = ""
"@ | Set-Content -Path (Join-Path $work "plc.toml")
    $run = Start-Quoted (Join-Path $plc "eric-plc.exe") @("run", "--config", (Join-Path $work "plc.toml"), "--duration-ms", "1000") $work
    $run.WaitForExit() | Out-Null
    if ($run.ExitCode -ne 0) {
        Get-Content (Join-Path $work "st.err") -ErrorAction SilentlyContinue
        throw "eric-plc did not run the Windows ST program ($($run.ExitCode))"
    }
    Write-Host "RuSTy produced sealin.dll and eric-plc ran it"
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
$rule = ""
$ruleDeadline = (Get-Date).AddSeconds(30)
do {
    $rule = & netsh advfirewall firewall show rule name="$($layout.serviceName)" | Out-String
    if ($rule -notmatch "No rules match") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $ruleDeadline)
if ($rule -match "No rules match") { throw "firewall rule was not added" }
Write-Host "firewall rule present"
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
