# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
#
# Windows version of scripts/check.sh: the same build, lint, and tests CI runs.
# Paste the summary at the end into your pull request.
#
#   pwsh scripts/check.ps1                 # everything
#   pwsh scripts/check.ps1 web docs        # only some groups
#
# Needs: JDK 21, Docker Desktop (some tests start containers), Python 3.10+ with
# pyyaml, Node 22+ with pnpm 10.
#
# Groups: gradle, repo, web, plc, docs. The PLC engine and its scan smoke are
# Linux-only (they use /dev/shm); on Windows the plc group is skipped. Use WSL
# or a Linux machine, or scripts/check.sh, if you changed plc/.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Groups)
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$All = @('gradle', 'repo', 'web', 'plc', 'docs')
if (-not $Groups -or $Groups.Count -eq 0) { $Groups = $All }
$LogDir = Join-Path $Root 'build/check-logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Summary = New-Object System.Collections.Generic.List[string]
$script:Failed = $false

function Have($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

function Step([string]$Name, [string]$Dir, [string]$Exe, [string[]]$ArgList) {
  $log = Join-Path $LogDir (($Name -replace ' ', '-') + '.log')
  Write-Host "`n==> $Name"
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Push-Location (Join-Path $Root $Dir)
  & $Exe @ArgList *> $log
  $code = $LASTEXITCODE
  Pop-Location
  $secs = [int]$sw.Elapsed.TotalSeconds
  if ($code -eq 0) { $Summary.Add(('PASS  {0,4}s  {1}' -f $secs, $Name)) }
  else {
    Get-Content $log -Tail 40
    $Summary.Add(('FAIL  {0,4}s  {1}   (log: build/check-logs/{2})' -f $secs, $Name, (Split-Path $log -Leaf)))
    $script:Failed = $true
  }
}
function Skip([string]$Name, [string]$Why) { $Summary.Add("SKIP          $Name   ($Why)") }

$total = [Diagnostics.Stopwatch]::StartNew()
foreach ($g in $Groups) {
  switch ($g) {
    'gradle' {
      if (Have java) { Step 'gradle build' '.' (Join-Path $Root 'gradlew.bat') @('build', '--no-daemon', '--continue') }
      else { Skip 'gradle build' 'install JDK 21' }
    }
    'repo' {
      $py = if (Have python) { 'python' } elseif (Have python3) { 'python3' } else { $null }
      if (-not $py) { Skip 'repo checks' 'install Python 3'; break }
      Step 'banned terms' '.' $py @('scripts/check_banned_terms.py')
      Step 'filename case collisions' '.' $py @('scripts/check_case_collisions.py')
      & $py -c 'import yaml' 2>$null
      if ($LASTEXITCODE -eq 0) {
        Step 'script tests' '.' $py @('-m', 'unittest', 'discover', '-s', 'scripts/tests')
        Step 'differences index' '.' $py @('scripts/gen_differences_index.py', '--check')
      } else { Skip 'script tests' 'pip install pyyaml==6.0.2' }
      Step 'third-party licenses' '.' $py @('scripts/check_licenses.py')
    }
    'web' {
      if (-not (Have pnpm)) { Skip 'web' 'install Node 22 and pnpm 10'; break }
      $py = if (Have python) { 'python' } else { 'python3' }
      Step 'web install' 'web' 'pnpm' @('install', '--frozen-lockfile')
      Step 'web dependency licenses' 'web' $py @('scripts/check_npm_licenses.py')
      Step 'web lint' 'web' 'pnpm' @('lint')
      Step 'web typecheck' 'web' 'pnpm' @('typecheck')
      Step 'web unit tests' 'web' 'pnpm' @('test')
    }
    'plc' { Skip 'plc' 'Linux only; run scripts/check.sh plc in WSL or on Linux' }
    'docs' {
      if (-not (Have npm)) { Skip 'docs site' 'install Node 22'; break }
      Step 'docs site install' 'docs-site' 'npm' @('ci', '--no-audit', '--no-fund')
      Step 'docs site build' 'docs-site' 'npm' @('run', 'build')
    }
    default { Write-Error "unknown group: $g (use: $($All -join ' '))"; exit 2 }
  }
}

Write-Host "`n===== ERIC local check ====="
$Summary | ForEach-Object { Write-Host $_ }
$commit = (git rev-parse --short HEAD 2>$null)
Write-Host ('Total: {0}s on Windows, commit {1}' -f [int]$total.Elapsed.TotalSeconds, $commit)
if ($script:Failed) { Write-Host 'RESULT: FAIL'; exit 1 }
Write-Host 'RESULT: PASS'
