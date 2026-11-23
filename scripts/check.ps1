# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
<#
The full local check on Windows. Required before every push and every release tag (CONTRIBUTING.md, manual
release-process). Run from an elevated PowerShell in the repository root:

  scripts\check.ps1

1. Gradle build and every JVM test           5. Windows installers, then packaging\windows\smoke.ps1: the
2. Repository checks (terms, names, licenses)    fresh install, service, firewall rule, upgrade and uninstall
3. eric-plc tests                             6. The new-user UI suite (web\e2e-ui): every feature through the
4. Web lint, typecheck, unit tests, e2e          admin page and Control Canvas forms, plus restart, kill,
                                                 backup restore, with the bundled compiler and scan engine

Needs JDK 21, Rust (cargo), Visual Studio Build Tools (the linker), Node 22 with corepack, Python 3, WiX 3 on PATH,
and network access for the pinned downloads. Elevation is needed for the installer test.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw "run scripts\check.ps1 from an elevated PowerShell (the installer test installs and removes the service)" }
$logDir = Join-Path ([IO.Path]::GetTempPath()) ("eric-check-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$results = New-Object System.Collections.Generic.List[string]

function Step([string]$Name, [scriptblock]$Body) {
    Write-Host "==> $Name"
    $log = Join-Path $logDir (($Name -replace '[ /\\]', '_') + ".log")
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $global:LASTEXITCODE = 0
    try {
        & $Body *> $log
        if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
        $results.Add("PASS  $Name ($([int]$watch.Elapsed.TotalSeconds) s)")
    } catch {
        $results.Add("FAIL  $Name ($([int]$watch.Elapsed.TotalSeconds) s), log: $log")
        Get-Content $log -Tail 40 -ErrorAction SilentlyContinue
        $results | ForEach-Object { Write-Host $_ }
        throw "check FAILED at: $Name ($_)"
    }
}

$web = Join-Path $root "web"
Step "gradle build and tests" { .\gradlew.bat build --no-daemon }
Step "banned terms" { python scripts\check_banned_terms.py }
Step "case collisions" { python scripts\check_case_collisions.py }
Step "licenses" { python scripts\check_licenses.py }
Step "eric-plc tests" { cargo test --manifest-path plc\eric-plc\Cargo.toml --locked }
Step "web install" { Push-Location $web; try { corepack pnpm install --frozen-lockfile } finally { Pop-Location } }
Step "web lint" { Push-Location $web; try { corepack pnpm lint } finally { Pop-Location } }
Step "web typecheck" { Push-Location $web; try { corepack pnpm typecheck } finally { Pop-Location } }
Step "web unit tests" { Push-Location $web; try { corepack pnpm test } finally { Pop-Location } }
Step "web bundles" { Push-Location $web; try { corepack pnpm --filter @oip/admin build; if ($LASTEXITCODE -eq 0) { corepack pnpm --filter @oip/designer build } } finally { Pop-Location } }
Step "gateway installDist" { .\gradlew.bat :gateway:server:installDist --no-daemon }
Step "playwright browser" { Push-Location $web; try { corepack pnpm exec playwright install chromium } finally { Pop-Location } }
Step "web e2e" { Push-Location $web; try { corepack pnpm test:e2e } finally { Pop-Location } }
Step "Windows installers" { .\gradlew.bat :packaging:windowsInstallers --no-daemon }
Step "fresh-install test (installers)" { powershell.exe -NoProfile -ExecutionPolicy Bypass -File packaging\windows\smoke.ps1 }
Step "new-user UI suite" {
    $bundle = Join-Path $root "packaging\build\plc-bundle"
    $env:ERIC_PLC_COMPILER = Join-Path $bundle "rusty\plc.exe"
    $env:ERIC_PLC_ENGINE = Join-Path $bundle "eric-plc.exe"
    Push-Location $web
    try { corepack pnpm test:ui } finally { Pop-Location; Remove-Item Env:ERIC_PLC_COMPILER, Env:ERIC_PLC_ENGINE -ErrorAction SilentlyContinue }
}

$results | ForEach-Object { Write-Host $_ }
Write-Host "check PASSED. Logs: $logDir"
