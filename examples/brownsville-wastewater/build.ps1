# Builds the fictional Brownsville wastewater collection project on a local ERIC gateway.
# No real utility data and no vendor names. Re-runnable: existing resources are left in place.
param(
    [string]$Gateway = "http://127.0.0.1:8090",
    [string]$UserFile = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path $UserFile)) { $UserFile = "" }

function Read-AdminPassword {
    if ($env:ERIC_ADMIN_PASSWORD) { return $env:ERIC_ADMIN_PASSWORD }
    $svc = "HKLM:\SYSTEM\CurrentControlSet\Services\ERIC Gateway\Parameters"
    if (Test-Path $svc) {
        $extra = (Get-ItemProperty $svc -ErrorAction SilentlyContinue).AppEnvironmentExtra
        foreach ($line in @($extra)) {
            if ($line -like "ERIC_ADMIN_PASSWORD=*") { return $line.Substring("ERIC_ADMIN_PASSWORD=".Length) }
        }
    }
    throw "Set ERIC_ADMIN_PASSWORD or the gateway service environment before building the demo."
}

$password = Read-AdminPassword
$login = Invoke-WebRequest -Uri "$Gateway/api/v1/auth/login" -Method POST -ContentType "application/json" -UseBasicParsing -TimeoutSec 30 -SessionVariable session -Body (@{ username = "admin"; password = $password } | ConvertTo-Json -Compress)
$csrf = ($login.Content | ConvertFrom-Json).csrfToken
$headers = @{ "X-CSRF-Token" = $csrf }

. (Join-Path $here "json.ps1")

function Send-Json([string]$Method, [string]$Url, $Body) {
    $json = ConvertTo-Body $Body
    try {
        $response = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers -WebSession $session -ContentType "application/json; charset=utf-8" -Body $json -UseBasicParsing -TimeoutSec 60
        return $response
    } catch {
        $detail = $_.ErrorDetails.Message
        if (-not $detail) { $detail = $_.Exception.Message }
        throw "$Method $Url failed: $detail"
    }
}

function Save-Resource([string]$Module, [string]$Scope, [string]$Kind, [string]$Name, $Spec) {
    $url = "$Gateway/api/v1/config/$Module/$Scope/$Kind"
    $body = @{ apiVersion = "oip/v1"; kind = $Kind; metadata = @{ name = $Name; module = $Module; version = 0 }; spec = $Spec }
    try {
        $created = Send-Json POST $url $body
        Write-Host "$Kind/$Name $($created.StatusCode)"
    } catch {
        if ($_.Exception.Message -match "409|already|exists|Conflict") {
            Write-Host "$Kind/$Name already present"
        } else {
            throw
        }
    }
}

$stations = @(
    @{ name = "CinderFlat"; district = "North Levee"; downstream = "NorthBasin"; base = 380; area = 70; pumps = 2 },
    @{ name = "SaltCedar"; district = "North Levee"; downstream = "NorthBasin"; base = 460; area = 80; pumps = 2 },
    @{ name = "MileMarker"; district = "West Drain"; downstream = "NorthBasin"; base = 310; area = 64; pumps = 2 },
    @{ name = "OldLevee"; district = "West Drain"; downstream = "CentralBasin"; base = 540; area = 90; pumps = 3 },
    @{ name = "CanalStreet"; district = "Midtown"; downstream = "CentralBasin"; base = 420; area = 74; pumps = 2 },
    @{ name = "EastGate"; district = "Midtown"; downstream = "CentralBasin"; base = 360; area = 68; pumps = 2 },
    @{ name = "WestDrain"; district = "West Drain"; downstream = "CentralBasin"; base = 610; area = 96; pumps = 3 },
    @{ name = "NorthDitch"; district = "Harbor"; downstream = "PortBasin"; base = 290; area = 60; pumps = 2 },
    @{ name = "SouthFlume"; district = "Harbor"; downstream = "PortBasin"; base = 480; area = 82; pumps = 2 },
    @{ name = "MidtownVault"; district = "Midtown"; downstream = "PortBasin"; base = 350; area = 66; pumps = 2 },
    @{ name = "RailroadSpur"; district = "South Flats"; downstream = "PortBasin"; base = 570; area = 88; pumps = 3 },
    @{ name = "BayouEnd"; district = "South Flats"; downstream = "PortBasin"; base = 440; area = 76; pumps = 2 }
)
$tanks = @(
    @{ name = "NorthBasin"; plant = "ResacaBend"; area = 240 },
    @{ name = "CentralBasin"; plant = "ResacaBend"; area = 280 },
    @{ name = "PortBasin"; plant = "LagunaWorks"; area = 320 }
)
$plants = @("ResacaBend", "LagunaWorks")

$pumpMembers = @(
    @{ name = "Run"; type = "memory"; dataType = "Boolean"; value = $false },
    @{ name = "Fault"; type = "memory"; dataType = "Boolean"; value = $false },
    @{ name = "Hoa"; type = "memory"; dataType = "Int32"; value = 2 },
    @{ name = "Runtime"; type = "memory"; dataType = "Double"; value = 120.0 },
    @{ name = "Starts"; type = "memory"; dataType = "Int32"; value = 40 },
    @{ name = "Speed"; type = "memory"; dataType = "Double"; value = 0 },
    @{ name = "Amps"; type = "memory"; dataType = "Double"; value = 0 },
    @{ name = "Rated"; type = "memory"; dataType = "Double"; value = 650 }
)
$levelAlarms = @(
    @{ name = "High"; mode = "AboveValue"; setpointA = 14; priority = "High"; deadband = 0.2 },
    @{ name = "HighHigh"; mode = "AboveValue"; setpointA = 16.5; priority = "Critical"; deadband = 0.2 },
    @{ name = "Low"; mode = "BelowValue"; setpointA = 1.5; priority = "Medium"; deadband = 0.2 }
)

Write-Host "project"
try {
    Send-Json POST "$Gateway/api/v1/projects" @{ name = "brownsville"; title = "Brownsville collection"; description = "Fictional wastewater collection and treatment for a demonstration city."; enabled = $true; runnable = $true } | Out-Null
} catch {
    if ($_.Exception.Message -notmatch "409|exists|Conflict") { throw }
    Write-Host "project already present"
}

Save-Resource "tags" "gateway" "Udt" "pump" @{
    path = "Types/Pump"
    parameters = @()
    members = $pumpMembers
}
Save-Resource "tags" "gateway" "Udt" "lift-station" @{
    path = "Types/LiftStation"
    parameters = @(
        @{ name = "District"; dataType = "String"; default = "" },
        @{ name = "Downstream"; dataType = "String"; default = "" },
        @{ name = "PumpCount"; dataType = "Int32"; default = 2 },
        @{ name = "BaseInflow"; dataType = "Double"; default = 400 }
    )
    members = @(
        @{ name = "Level"; type = "memory"; dataType = "Double"; value = 6.0; engUnit = "ft"; alarms = $levelAlarms },
        @{ name = "Volume"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gal" },
        @{ name = "Inflow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
        @{ name = "Flow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
        @{ name = "DischargePressure"; type = "memory"; dataType = "Double"; value = 0; engUnit = "psi" },
        @{ name = "LeadOn"; type = "memory"; dataType = "Double"; value = 7.5; engUnit = "ft" },
        @{ name = "LeadOff"; type = "memory"; dataType = "Double"; value = 4.0; engUnit = "ft" },
        @{ name = "LagOn"; type = "memory"; dataType = "Double"; value = 10.5; engUnit = "ft" },
        @{ name = "LagOff"; type = "memory"; dataType = "Double"; value = 6.5; engUnit = "ft" },
        @{ name = "StandbyOn"; type = "memory"; dataType = "Double"; value = 13.0; engUnit = "ft" },
        @{ name = "StandbyOff"; type = "memory"; dataType = "Double"; value = 9.0; engUnit = "ft" },
        @{ name = "LeadIndex"; type = "memory"; dataType = "Int32"; value = 0 },
        @{ name = "PumpCount"; type = "expression"; dataType = "Int32"; expression = "{PumpCount}" },
        @{ name = "District"; type = "expression"; dataType = "String"; expression = "{District}" },
        @{ name = "Downstream"; type = "expression"; dataType = "String"; expression = "{Downstream}" },
        @{ name = "PowerOk"; type = "memory"; dataType = "Boolean"; value = $true },
        @{ name = "Generator"; type = "memory"; dataType = "Boolean"; value = $false },
        @{ name = "CommsOk"; type = "memory"; dataType = "Boolean"; value = $true },
        @{ name = "P1"; type = "udt"; typeId = "Types/Pump" },
        @{ name = "P2"; type = "udt"; typeId = "Types/Pump" },
        @{ name = "P3"; type = "udt"; typeId = "Types/Pump" }
    )
}
Save-Resource "tags" "gateway" "Udt" "holding-tank" @{
    path = "Types/HoldingTank"
    parameters = @(@{ name = "Plant"; dataType = "String"; default = "" })
    members = @(
        @{ name = "Level"; type = "memory"; dataType = "Double"; value = 8.0; engUnit = "ft"; alarms = @(@{ name = "Overflow"; mode = "AboveValue"; setpointA = 14; priority = "Critical"; deadband = 0.3 }) },
        @{ name = "Volume"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gal" },
        @{ name = "Inflow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
        @{ name = "Outflow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
        @{ name = "InletValve"; type = "memory"; dataType = "Boolean"; value = $true },
        @{ name = "OutletValve"; type = "memory"; dataType = "Boolean"; value = $true },
        @{ name = "Plant"; type = "expression"; dataType = "String"; expression = "{Plant}" }
    )
}
Save-Resource "tags" "gateway" "Udt" "force-main" @{
    path = "Types/ForceMain"
    parameters = @(
        @{ name = "From"; dataType = "String"; default = "" },
        @{ name = "To"; dataType = "String"; default = "" }
    )
    members = @(
        @{ name = "Pressure"; type = "memory"; dataType = "Double"; value = 0; engUnit = "psi" },
        @{ name = "Flow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
        @{ name = "From"; type = "expression"; dataType = "String"; expression = "{From}" },
        @{ name = "To"; type = "expression"; dataType = "String"; expression = "{To}" }
    )
}
$plantMembers = @(
    @{ name = "InfluentFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "BarScreenDp"; type = "memory"; dataType = "Double"; value = 0.4; engUnit = "psi" },
    @{ name = "BarScreenRun"; type = "memory"; dataType = "Boolean"; value = $true },
    @{ name = "GritFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "PrimaryLevel"; type = "memory"; dataType = "Double"; value = 8; engUnit = "ft" },
    @{ name = "PrimaryFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "Do"; type = "memory"; dataType = "Double"; value = 2.0; engUnit = "mg/L" },
    @{ name = "DoSp"; type = "memory"; dataType = "Double"; value = 2.0; engUnit = "mg/L" },
    @{ name = "BlowerRun"; type = "memory"; dataType = "Boolean"; value = $true },
    @{ name = "BlowerSpeed"; type = "memory"; dataType = "Double"; value = 45; engUnit = "%" },
    @{ name = "AirFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "scfm" },
    @{ name = "SecondaryLevel"; type = "memory"; dataType = "Double"; value = 10; engUnit = "ft" },
    @{ name = "RasFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "WasFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "UvRun"; type = "memory"; dataType = "Boolean"; value = $true },
    @{ name = "ChlorineResidual"; type = "memory"; dataType = "Double"; value = 1.1; engUnit = "mg/L"; alarms = @(@{ name = "LowResidual"; mode = "BelowValue"; setpointA = 0.4; priority = "High"; deadband = 0.05 }) },
    @{ name = "EffluentFlow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" },
    @{ name = "Turbidity"; type = "memory"; dataType = "Double"; value = 1.4; engUnit = "NTU"; alarms = @(@{ name = "HighTurbidity"; mode = "AboveValue"; setpointA = 4; priority = "High"; deadband = 0.1 }) },
    @{ name = "Ph"; type = "memory"; dataType = "Double"; value = 7.1 },
    @{ name = "PlantAlarm"; type = "memory"; dataType = "Boolean"; value = $false }
)
Save-Resource "tags" "gateway" "Udt" "wwtp" @{
    path = "Types/Wwtp"
    parameters = @(@{ name = "Service"; dataType = "String"; default = "" })
    members = $plantMembers
}

$stationRows = @()
foreach ($station in $stations) {
    $stationRows += @{
        name = $station.name
        parameters = @{
            District = $station.district
            Downstream = $station.downstream
            PumpCount = $station.pumps
            BaseInflow = $station.base
        }
    }
}
Write-Host "lift stations"
try {
    Send-Json POST "$Gateway/api/v1/tags/udt-instances" @{
        typeId = "Types/LiftStation"; folder = "Brownsville/Stations"; rows = $stationRows
    } | Out-Null
} catch { if ($_.Exception.Message -notmatch "409|422|exists|already") { throw } ; Write-Host "stations already present" }

$tankRows = @()
foreach ($tank in $tanks) { $tankRows += @{ name = $tank.name; parameters = @{ Plant = $tank.plant } } }
Write-Host "tanks"
try {
    Send-Json POST "$Gateway/api/v1/tags/udt-instances" @{
        typeId = "Types/HoldingTank"; folder = "Brownsville/Tanks"; rows = $tankRows
    } | Out-Null
} catch { if ($_.Exception.Message -notmatch "409|422|exists|already") { throw } ; Write-Host "tanks already present" }

$mainRows = @()
foreach ($station in $stations) { $mainRows += @{ name = $station.name; parameters = @{ From = $station.name; To = $station.downstream } } }
Write-Host "force mains"
try {
    Send-Json POST "$Gateway/api/v1/tags/udt-instances" @{
        typeId = "Types/ForceMain"; folder = "Brownsville/Mains"; rows = $mainRows
    } | Out-Null
} catch { if ($_.Exception.Message -notmatch "409|422|exists|already") { throw } ; Write-Host "mains already present" }

$plantRows = @(
    @{ name = "ResacaBend"; parameters = @{ Service = "north and midtown" } },
    @{ name = "LagunaWorks"; parameters = @{ Service = "harbor and south flats" } }
)
Write-Host "plants"
try {
    Send-Json POST "$Gateway/api/v1/tags/udt-instances" @{
        typeId = "Types/Wwtp"; folder = "Brownsville/Plants"; rows = $plantRows
    } | Out-Null
} catch { if ($_.Exception.Message -notmatch "409|422|exists|already") { throw } ; Write-Host "plants already present" }

Save-Resource "tags" "gateway" "Tag" "brownsville-storm" @{ provider = "default"; path = "Brownsville/Storm"; type = "memory"; dataType = "Double"; value = 1 }
Save-Resource "tags" "gateway" "Tag" "brownsville-diurnal" @{ provider = "default"; path = "Brownsville/Diurnal"; type = "memory"; dataType = "Double"; value = 1 }
Save-Resource "tags" "gateway" "Tag" "brownsville-total-inflow" @{ provider = "default"; path = "Brownsville/TotalInflow"; type = "memory"; dataType = "Double"; value = 0; engUnit = "gpm" }
Save-Resource "tags" "gateway" "Tag" "brownsville-script-ok" @{ provider = "default"; path = "Brownsville/ScriptOk"; type = "memory"; dataType = "Boolean"; value = $false }
Save-Resource "tags" "gateway" "Tag" "brownsville-script-error" @{ provider = "default"; path = "Brownsville/ScriptError"; type = "memory"; dataType = "String"; value = "" }

$python = Get-Content -Raw (Join-Path $here "simulate.py")
$simSpec = @{
    type = "timer"
    delayMillis = 2000
    delayType = "fixedRate"
    threading = "dedicated"
    description = "Fictional Brownsville collection. Each tick is four seconds of process time."
    source = $python
}
$simUrl = "$Gateway/api/v1/config/scripting/gateway/GatewayEvent/brownsville-sim"
try {
    $existing = Invoke-WebRequest $simUrl -Headers $headers -WebSession $session -UseBasicParsing
    $version = (($existing.Content | ConvertFrom-Json).metadata.version)
    Send-Json PUT $simUrl @{ apiVersion = "oip/v1"; kind = "GatewayEvent"; metadata = @{ name = "brownsville-sim"; module = "scripting"; version = $version }; spec = $simSpec } | Out-Null
    Write-Host "GatewayEvent/brownsville-sim updated"
} catch {
    Save-Resource "scripting" "gateway" "GatewayEvent" "brownsville-sim" $simSpec
}

$history = @(
    "Brownsville/TotalInflow", "Brownsville/Storm"
)
foreach ($station in $stations) {
    $history += "Brownsville/Stations/$($station.name)/Level"
    $history += "Brownsville/Stations/$($station.name)/Flow"
}
foreach ($tank in $tanks) {
    $history += "Brownsville/Tanks/$($tank.name)/Level"
    $history += "Brownsville/Tanks/$($tank.name)/Outflow"
}
foreach ($plant in $plants) {
    $history += "Brownsville/Plants/$plant/InfluentFlow"
    $history += "Brownsville/Plants/$plant/Do"
    $history += "Brownsville/Plants/$plant/DoSp"
    $history += "Brownsville/Plants/$plant/BlowerSpeed"
    $history += "Brownsville/Plants/$plant/EffluentFlow"
}
foreach ($station in $stations) {
    foreach ($pump in @("P1", "P2", "P3")) {
        $history += "Brownsville/Stations/$($station.name)/$pump/Amps"
        $history += "Brownsville/Stations/$($station.name)/$pump/Speed"
    }
}
foreach ($path in $history) {
    $safe = ($path.ToLower() -replace "[^a-z0-9]", "-")
    Save-Resource "historian" "gateway" "HistorySettings" $safe @{
        tagPath = "[default]$path"
        sampleMode = "onChange"
        storageProvider = "local"
    }
}

# Views are built by screens.ps1 so this file stays the model loader.
& (Join-Path $here "screens.ps1") -Gateway $Gateway -WebSession $session -Headers $headers

$st = Get-Content -Raw (Join-Path $here "PlantDo.st")
try { Send-Json POST "$Gateway/api/v1/logic/programs" @{ name = "PlantDo" } | Out-Null } catch { }
Send-Json PUT "$Gateway/api/v1/logic/programs/PlantDo" @{ source = $st } | Out-Null
$check = Send-Json POST "$Gateway/api/v1/logic/programs/PlantDo/check" @{}
Write-Host "PlantDo check $($check.Content.Substring(0, [Math]::Min(180, $check.Content.Length)))"
try {
    $deploy = Send-Json POST "$Gateway/api/v1/logic/deploy" @{ program = "PlantDo" }
    Write-Host "PlantDo deploy $($deploy.Content.Substring(0, [Math]::Min(240, $deploy.Content.Length)))"
} catch {
    Write-Host "PlantDo deploy skipped: $($_.Exception.Message)"
}

$export = Join-Path $here "export"
if (Test-Path $export) { Remove-Item $export -Recurse -Force }
New-Item -ItemType Directory -Force -Path $export | Out-Null
$root = "C:\ProgramData\ERIC\gateway\config"
Copy-Item "$root\tags\gateway\Udt" (Join-Path $export "udt") -Recurse
Copy-Item "$root\scripting\gateway\GatewayEvent\brownsville-sim.yaml" (Join-Path $export "brownsville-sim.yaml")
Copy-Item "$root\designer\project\brownsville" (Join-Path $export "designer") -Recurse
$historyDir = Join-Path $export "history"
New-Item -ItemType Directory -Force -Path $historyDir | Out-Null
Get-ChildItem "$root\historian\gateway\HistorySettings" -Filter "Brownsville*" | Copy-Item -Destination $historyDir
Write-Host "export copied"
Start-Sleep -Seconds 8
$read = Send-Json POST "$Gateway/api/v1/tags/read" @{
    paths = @(
        "[default]Brownsville/ScriptOk",
        "[default]Brownsville/ScriptError",
        "[default]Brownsville/Stations/CinderFlat/Level",
        "[default]Brownsville/Stations/CinderFlat/Flow",
        "[default]Brownsville/Stations/CinderFlat/P1/Run",
        "[default]Brownsville/Storm",
        "[default]Brownsville/Plants/ResacaBend/InfluentFlow",
        "[default]Brownsville/Plants/ResacaBend/Do"
    )
}
Write-Host $read.Content
Write-Host "built"
