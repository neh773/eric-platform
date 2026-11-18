# P&ID pages for the fictional Brownsville collection. Dot-sourced by screens.ps1.
$ink = @{ color = "#d7dee6"; fontSize = 14 }
$muted = @{ color = "#8b98a5"; fontSize = 12 }
$stations = @(
    @{ n = "CinderFlat"; district = "North Levee"; to = "NorthBasin"; lat = 25.948; lon = -97.508 },
    @{ n = "SaltCedar"; district = "North Levee"; to = "NorthBasin"; lat = 25.940; lon = -97.478 },
    @{ n = "MileMarker"; district = "West Drain"; to = "NorthBasin"; lat = 25.932; lon = -97.545 },
    @{ n = "OldLevee"; district = "West Drain"; to = "CentralBasin"; lat = 25.915; lon = -97.552 },
    @{ n = "CanalStreet"; district = "Midtown"; to = "CentralBasin"; lat = 25.912; lon = -97.497 },
    @{ n = "EastGate"; district = "Midtown"; to = "CentralBasin"; lat = 25.905; lon = -97.455 },
    @{ n = "WestDrain"; district = "West Drain"; to = "CentralBasin"; lat = 25.890; lon = -97.540 },
    @{ n = "NorthDitch"; district = "Harbor"; to = "PortBasin"; lat = 25.925; lon = -97.430 },
    @{ n = "SouthFlume"; district = "Harbor"; to = "PortBasin"; lat = 25.878; lon = -97.448 },
    @{ n = "MidtownVault"; district = "Midtown"; to = "PortBasin"; lat = 25.898; lon = -97.490 },
    @{ n = "RailroadSpur"; district = "South Flats"; to = "PortBasin"; lat = 25.868; lon = -97.520 },
    @{ n = "BayouEnd"; district = "South Flats"; to = "PortBasin"; lat = 25.855; lon = -97.475 }
)
$tanks = @(
    @{ n = "NorthBasin"; plant = "ResacaBend"; lat = 25.936; lon = -97.470 },
    @{ n = "CentralBasin"; plant = "ResacaBend"; lat = 25.900; lon = -97.510 },
    @{ n = "PortBasin"; plant = "LagunaWorks"; lat = 25.870; lon = -97.450 }
)
$plants = @(
    @{ n = "ResacaBend"; lat = 25.928; lon = -97.420 },
    @{ n = "LagunaWorks"; lat = 25.848; lon = -97.415 }
)

function Trend($Id, $X, $Y, $W, $H, $Title, $Pens) {
    Node $Id "time-series-chart" @{
        title = $Title
        historyPens = $Pens
        penChoices = $Pens
        rangeSeconds = 900
        historyRefreshSeconds = 5
        decimals = 1
        showLegend = $true
    } $X $Y $W $H $null $null
}
function SensorAt($Id, $X, $Y, $Tag, $Label, $Path, $Unit, $Decimals, $High) {
    $expr = "if({$Path} >= $High, `"fault`", `"normal`")"
    Node $Id "sensor" @{ tag = $Tag; label = $Label; unit = $Unit; decimals = $Decimals } $X $Y 110 120 @{
        value = (Direct $Path)
        state = @{ kind = "expression"; expression = $expr; overlayOptOut = $true }
    } $null
}
function PipeAt($Id, $X1, $Y1, $X2, $Y2, $Flow, [switch]$IndirectPath) {
    $left = [Math]::Min($X1, $X2)
    $top = [Math]::Min($Y1, $Y2)
    $w = [Math]::Max(36, [Math]::Abs($X2 - $X1) + 20)
    $h = [Math]::Max(24, [Math]::Abs($Y2 - $Y1) + 20)
    $points = "[{`"x`":$($X1 - $left + 6),`"y`":$($Y1 - $top + 6)},{`"x`":$($X2 - $left + 6),`"y`":$($Y2 - $top + 6)}]"
    $bind = $null
    if ($Flow) {
        $binding = if ($IndirectPath) { Indirect $Flow } else { Direct $Flow }
        $bind = @{ flow = $binding }
    }
    Node $Id "pipe" @{ label = $Id; points = $points; width = 5; stroke = "#8aa0b0"; outline = "#24303a"; flowColor = "#d5e6f2" } $left $top $w $h $bind $null
}

function Elbow($Id, $X1, $Y1, $X2, $Y2, $Flow, [switch]$IndirectPath) {
    $xs = @($X1, $X2)
    $ys = @($Y1, $Y1, $Y2)
    $left = ($xs | Measure-Object -Minimum).Minimum
    $top = (@($Y1, $Y2) | Measure-Object -Minimum).Minimum
    $w = [Math]::Max(24, (($xs | Measure-Object -Maximum).Maximum - $left) + 16)
    $h = [Math]::Max(24, ((@($Y1, $Y2) | Measure-Object -Maximum).Maximum - $top) + 16)
    $pts = @(
        @{ x = [int]($X1 - $left + 6); y = [int]($Y1 - $top + 6) },
        @{ x = [int]($X2 - $left + 6); y = [int]($Y1 - $top + 6) },
        @{ x = [int]($X2 - $left + 6); y = [int]($Y2 - $top + 6) }
    )
    $points = ConvertTo-Body $pts
    $bind = $null
    if ($Flow) { $bind = @{ flow = $(if ($IndirectPath) { Indirect $Flow } else { Direct $Flow }) } }
    Node $Id "pipe" @{ label = $Id; points = $points; width = 5; stroke = "#8aa0b0"; outline = "#24303a"; flowColor = "#d5e6f2" } $left $top $w $h $bind $null
}

# A pipe that terminates on named ports (componentId.port) or on fromPoint/toPoint. The runtime routes it.
function LinkPipe($Id, $Spec, $Flow, [switch]$Indirect) {
    $props = @{
        label = $Id
        width = 5
        stroke = "#8aa0b0"
        outline = "#24303a"
        outlineWidth = 1
        joints = "round"
        showJoints = $false
        lineStyle = "process"
        tee = "none"
        flowColor = "#d5e6f2"
    }
    foreach ($key in @($Spec.Keys)) { $props[$key] = $Spec[$key] }
    $bind = $null
    if ($null -ne $Flow -and $Flow -ne "") {
        $bind = @{ flow = $(if ($Indirect) { Indirect $Flow } else { Direct $Flow }) }
    }
    Node $Id "pipe" $props 0 0 12 12 $bind $null
}

$nav = @()
$links = @(
    @{ id = "overview"; text = "City"; url = "/" },
    @{ id = "map"; text = "Map"; url = "/map" },
    @{ id = "network"; text = "Network"; url = "/network" },
    @{ id = "alarms"; text = "Alarms"; url = "/alarms" },
    @{ id = "trends"; text = "Trends"; url = "/trends" },
    @{ id = "headworks"; text = "Headworks"; url = "/headworks" },
    @{ id = "aeration"; text = "Aeration"; url = "/aeration" },
    @{ id = "clarifiers"; text = "Clarifiers"; url = "/clarifiers" },
    @{ id = "disinfection"; text = "Disinfection"; url = "/disinfection" }
)
$y = 12
foreach ($link in $links) {
    $nav += Node $link.id "link" @{ text = $link.text; url = $link.url } 8 $y 170 26 $null @{ color = "#9ec9ef"; fontSize = 15 }
    $y += 28
}
$nav += Node "nav-stations" "label" @{ text = "Stations" } 8 $y 170 18 $null $muted
$y += 20
foreach ($station in $stations) {
    $nav += Node ("go-" + $station.n) "link" @{ text = $station.n; url = "/station/$($station.n)" } 8 $y 170 20 $null @{ color = "#c5d4e0"; fontSize = 13 }
    $y += 20
}
$nav += Node "nav-tanks" "label" @{ text = "Basins" } 8 $y 170 18 $null $muted
$y += 20
foreach ($tank in $tanks) {
    $nav += Node ("go-" + $tank.n) "link" @{ text = $tank.n; url = "/tank/$($tank.n)" } 8 $y 170 20 $null @{ color = "#c5d4e0"; fontSize = 13 }
    $y += 20
}
$nav += Node "nav-plants" "label" @{ text = "Plants" } 8 $y 170 18 $null $muted
$y += 20
foreach ($plant in $plants) {
    $nav += Node ("go-" + $plant.n) "link" @{ text = $plant.n; url = "/plant/$($plant.n)" } 8 $y 170 20 $null @{ color = "#c5d4e0"; fontSize = 13 }
    $y += 20
}
Publish-View "nav" "Navigation" (Root 190 ([Math]::Max(900, $y + 12)) $nav @{})

# City P&ID: stations, force mains, basins, plants.
$city = @()
$city += Node "title" "label" @{ text = "Brownsville collection" } 12 8 420 28 $null @{ color = "#e8eef4"; fontSize = 22 }
$city += Node "fiction" "label" @{ text = "Fictional sites. Wet wells, force mains, basins, plants." } 12 36 520 18 $null $muted
$col = 0
foreach ($station in $stations) {
    $x = 16 + ($col % 6) * 200
    $yy = 64 + ([Math]::Floor($col / 6) * 150)
    $path = "[default]Brownsville/Stations/$($station.n)"
    $city += Node "$($station.n)-name" "link" @{ text = $station.n; url = "/station/$($station.n)" } $x $yy 160 20 $null @{ color = "#e8eef4"; fontSize = 14 }
    $well = Node "$($station.n)-well" "cylindrical-tank" @{ label = "Well"; min = 0; max = 18; unit = "ft"; decimals = 1; alarmHigh = 78; alarmLow = 8 } $x ($yy + 24) 64 64 @{ level = (Direct "$path/Level") } $null
    $city += OnPopup $well "sum-$($station.n)" "fp-station" $station.n @{ root = $path; page = "/station/$($station.n)"; pens = "$path/Level,$path/Flow" }
    $pump = Node "$($station.n)-p1" "pump" @{ label = "1" } ($x + 96) ($yy + 28) 52 52 @{ state = (Direct "$path/P1/Run") } $null
    $city += OnPopup $pump "city-$($station.n)-p1" "fp-pump" "$($station.n) P1" @{ root = "$path/P1"; flow = "$path/Flow"; pens = "$path/P1/Amps,$path/P1/Speed,$path/Flow" }
    $city += LinkPipe "$($station.n)-suction" @{ from = "$($station.n)-well.outlet"; to = "$($station.n)-p1.suction" } "$path/Flow"
    $city += LinkPipe "$($station.n)-dis" @{ from = "$($station.n)-p1.discharge"; toPoint = @{ x = ($x + 176); y = [int]($yy + 28 + 18) }; tee = "end" } "$path/Flow"
    $city += Node "$($station.n)-fit" "numeric" @{ decimals = 0; suffix = " gpm" } $x ($yy + 96) 120 22 @{ value = (Direct "$path/Flow") } $ink
    $col++
}
$tx = 16
foreach ($tank in $tanks) {
    $path = "[default]Brownsville/Tanks/$($tank.n)"
    $city += Node "$($tank.n)-name" "link" @{ text = $tank.n; url = "/tank/$($tank.n)" } $tx 390 180 20 $null @{ color = "#e8eef4"; fontSize = 15 }
    $city += Node "$($tank.n)-vessel" "vessel" @{ label = "EQ"; min = 0; max = 16; unit = "ft"; decimals = 1; alarmHigh = 88 } $tx 414 80 110 @{ level = (Direct "$path/Level") } $null
    $city += SensorAt "$($tank.n)-lit" ($tx + 90) 414 "LIT" "Level" "$path/Level" "ft" 1 14
    $city += PipeAt "$($tank.n)-out" ($tx + 170) 460 ($tx + 250) 460 "$path/Outflow"
    $tx += 400
}
$px = 16
foreach ($plant in $plants) {
    $path = "[default]Brownsville/Plants/$($plant.n)"
    $city += Node "$($plant.n)-name" "link" @{ text = $plant.n; url = "/plant/$($plant.n)" } $px 560 200 22 $null @{ color = "#e8eef4"; fontSize = 16 }
    $city += Node "$($plant.n)-blower" "pump" @{ palette = "muted"; label = "Air" } $px 588 48 48 @{ state = (Direct "$path/BlowerRun") } $null
    $city += SensorAt "$($plant.n)-fit" ($px + 60) 560 "FIT" "In" "$path/InfluentFlow" "gpm" 0 4000
    $city += SensorAt "$($plant.n)-ait" ($px + 180) 560 "AIT" "DO" "$path/Do" "mg/L" 2 0
    $px += 420
}
$city += Trend "city-levels" 16 700 580 240 "Levels (ft)" "[default]Brownsville/Stations/CinderFlat/Level,[default]Brownsville/Tanks/NorthBasin/Level,[default]Brownsville/Tanks/PortBasin/Level"
$city += Trend "city-flows" 610 700 580 240 "Flows (gpm)" "[default]Brownsville/TotalInflow,[default]Brownsville/Plants/ResacaBend/InfluentFlow,[default]Brownsville/Plants/LagunaWorks/InfluentFlow"
Publish-View "overview" "City" (Root 1220 980 $city @{})

# Reusable lift station P&ID. Square symbols so each port sits on the artwork.
# Well outlet y = 210. Pumps are 88px; suction is 0.54 down, so the middle pump's suction meets that outlet.
$param = @{ name = @{ value = "CinderFlat"; direction = "input" } }
$base = "[default]Brownsville/Stations/{name}"
$st = @()
$st += Node "title" "label" @{ text = "" } 16 8 360 28 @{ text = @{ kind = "expression"; expression = "{view.params.name}" } } @{ color = "#e8eef4"; fontSize = 22 }
$well = Node "well" "cylindrical-tank" @{ label = "Wet well"; min = 0; max = 18; unit = "ft"; decimals = 1; alarmHigh = 78; alarmLow = 8 } 36 150 120 120 @{ level = (Indirect "$base/Level") } $null
$st += OnPopup $well "well-{name}" "fp-well" "{name} wet well" @{ root = "[default]Brownsville/Stations/{name}"; pens = "[default]Brownsville/Stations/{name}/Level,[default]Brownsville/Stations/{name}/LeadOn,[default]Brownsville/Stations/{name}/LagOn" }
$st += Node "inflow" "numeric" @{ decimals = 0; suffix = " gpm in" } 36 278 160 22 @{ value = (Indirect "$base/Inflow") } $ink
$lit = Node "lit" "sensor" @{ tag = "LIT"; label = "Level"; unit = "ft"; decimals = 1 } 180 24 100 100 @{ value = (Indirect "$base/Level") } $null
$st += OnPopup $lit "lit-{name}" "fp-analog" "{name} level" @{ tag = "[default]Brownsville/Stations/{name}/Level"; unit = "ft"; decimals = "1"; limits = "H 14 ft, HH 16.5 ft, L 1.5 ft"; pens = "[default]Brownsville/Stations/{name}/Level" }
$pumpY = @{ P1 = 20; P2 = 162; P3 = 292 }
foreach ($pump in @("P1", "P2", "P3")) {
    $p = "$base/$pump"
    $py = $pumpY[$pump]
    $vy = $py - 15
    $symbol = Node "$pump-symbol" "pump" @{ label = $pump } 300 $py 88 88 @{ state = (Indirect "$p/Run") } $null
    $st += OnPopup $symbol "pump-{name}-$pump" "fp-pump" "{name} $pump" @{ root = "[default]Brownsville/Stations/{name}/$pump"; flow = "[default]Brownsville/Stations/{name}/Flow"; pens = "[default]Brownsville/Stations/{name}/$pump/Amps,[default]Brownsville/Stations/{name}/$pump/Speed,[default]Brownsville/Stations/{name}/Flow" }
    $valve = Node "$pump-valve" "valve" @{ label = $pump } 424 $vy 72 72 @{ state = (Indirect "$p/Run") } $null
    $st += OnPopup $valve "valve-{name}-$pump" "fp-valve" "{name} $pump valve" @{ tag = "[default]Brownsville/Stations/{name}/$pump/Run"; pens = "[default]Brownsville/Stations/{name}/Flow" }
    $st += Node "$pump-amp" "numeric" @{ decimals = 1; suffix = " A" } 300 ($py + 94) 120 22 @{ value = (Indirect "$p/Amps") } $ink
    $suctionY = [int]($py + 48)
    $st += LinkPipe "$pump-suction" @{ from = "well.outlet"; to = "$pump-symbol.suction"; waypoints = "[{`"x`":270,`"y`":210},{`"x`":270,`"y`":$suctionY}]" } "$p/Speed" -Indirect
    $st += LinkPipe "$pump-to-valve" @{ from = "$pump-symbol.discharge"; to = "$pump-valve.in" } "$p/Speed" -Indirect
    $outY = [int]($py + 30)
    $st += LinkPipe "$pump-to-header" @{ from = "$pump-valve.out"; toPoint = @{ x = 540; y = $outY }; tee = "end" } "$p/Speed" -Indirect
}
$st += LinkPipe "header-bus" @{ fromPoint = @{ x = 540; y = 50 }; toPoint = @{ x = 540; y = 322 }; tee = "both" } "$base/Flow" -Indirect
$st += LinkPipe "header-run" @{ fromPoint = @{ x = 540; y = 246 }; toPoint = @{ x = 1000; y = 246 }; tee = "both" } "$base/Flow" -Indirect
$fit = Node "fit" "sensor" @{ tag = "FIT"; label = "Flow"; unit = "gpm"; decimals = 0 } 760 148 100 100 @{ value = (Indirect "$base/Flow") } $null
$st += OnPopup $fit "fit-{name}" "fp-analog" "{name} flow" @{ tag = "[default]Brownsville/Stations/{name}/Flow"; unit = "gpm"; decimals = "0"; limits = "No alarm limits configured"; pens = "[default]Brownsville/Stations/{name}/Flow" }
$pit = Node "pit" "sensor" @{ tag = "PIT"; label = "Pressure"; unit = "psi"; decimals = 1 } 900 148 100 100 @{ value = (Indirect "$base/DischargePressure") } $null
$st += OnPopup $pit "pit-{name}" "fp-analog" "{name} pressure" @{ tag = "[default]Brownsville/Stations/{name}/DischargePressure"; unit = "psi"; decimals = "1"; limits = "No alarm limits configured"; pens = "[default]Brownsville/Stations/{name}/Flow" }
$st += LinkPipe "fit-riser" @{ fromPoint = @{ x = 810; y = 246 }; to = "fit.tap"; tee = "start" } "$base/Flow" -Indirect
$st += LinkPipe "pit-riser" @{ fromPoint = @{ x = 950; y = 246 }; to = "pit.tap"; tee = "start" } "$base/DischargePressure" -Indirect
$st += Node "power" "indicator" @{ label = "Utility"; onText = "On"; offText = "Off" } 36 430 150 32 @{ value = (Indirect "$base/PowerOk") } $null
$st += Node "gen" "indicator" @{ label = "Generator"; onText = "Run"; offText = "Off" } 200 430 160 32 @{ value = (Indirect "$base/Generator") } $null
$st += Node "comms" "indicator" @{ label = "Comms"; onText = "Ok"; offText = "Down" } 380 430 140 32 @{ value = (Indirect "$base/CommsOk") } $null
$st += Node "hint" "label" @{ text = "Tap a symbol for its faceplate. Setpoints are on the wet well." } 540 436 460 20 $null $muted
$st += Trend "station-level" 16 480 520 200 "Wet well level" "[default]Brownsville/Stations/{name}/Level,[default]Brownsville/Stations/{name}/LeadOn"
$st += Trend "station-flow" 550 480 500 200 "Discharge flow" "[default]Brownsville/Stations/{name}/Flow"
Publish-View "station" "Lift station" (Root 1080 700 $st $param)

$tankParam = @{ name = @{ value = "NorthBasin"; direction = "input" } }
$tb = "[default]Brownsville/Tanks/{name}"
$tk = @()
$tk += Node "title" "label" @{ text = "" } 16 8 360 28 @{ text = @{ kind = "expression"; expression = "{view.params.name}" } } @{ color = "#e8eef4"; fontSize = 22 }
$inValve = Node "in-valve" "valve" @{ label = "Inlet" } 24 24 72 72 @{ state = (Indirect "$tb/InletValve") } $null
$tk += OnPopup $inValve "in-valve-{name}" "fp-valve" "{name} inlet" @{ tag = "[default]Brownsville/Tanks/{name}/InletValve"; pens = "[default]Brownsville/Tanks/{name}/Inflow" }
$basin = Node "vessel" "vessel" @{ label = "Basin"; min = 0; max = 16; unit = "ft"; decimals = 1; alarmHigh = 88 } 220 80 160 160 @{ level = (Indirect "$tb/Level") } $null
$tk += OnPopup $basin "basin-{name}" "fp-basin" "{name}" @{ root = "[default]Brownsville/Tanks/{name}"; pens = "[default]Brownsville/Tanks/{name}/Level,[default]Brownsville/Tanks/{name}/Outflow" }
$litB = Node "lit" "sensor" @{ tag = "LIT"; label = "Basin"; unit = "ft"; decimals = 1 } 400 20 100 100 @{ value = (Indirect "$tb/Level") } $null
$tk += OnPopup $litB "lit-basin-{name}" "fp-analog" "{name} level" @{ tag = "[default]Brownsville/Tanks/{name}/Level"; unit = "ft"; decimals = "1"; limits = "Overflow 14 ft"; pens = "[default]Brownsville/Tanks/{name}/Level" }
$outValve = Node "out-valve" "valve" @{ label = "Outlet" } 460 128 72 72 @{ state = (Indirect "$tb/OutletValve") } $null
$tk += OnPopup $outValve "out-valve-{name}" "fp-valve" "{name} outlet" @{ tag = "[default]Brownsville/Tanks/{name}/OutletValve"; pens = "[default]Brownsville/Tanks/{name}/Outflow" }
$fitIn = Node "fit-in" "sensor" @{ tag = "FIT"; label = "In"; unit = "gpm"; decimals = 0 } 24 200 100 100 @{ value = (Indirect "$tb/Inflow") } $null
$tk += OnPopup $fitIn "fit-in-{name}" "fp-analog" "{name} inflow" @{ tag = "[default]Brownsville/Tanks/{name}/Inflow"; unit = "gpm"; decimals = "0"; limits = "No alarm limits configured"; pens = "[default]Brownsville/Tanks/{name}/Inflow" }
$fitOut = Node "fit-out" "sensor" @{ tag = "FIT"; label = "Out"; unit = "gpm"; decimals = 0 } 620 80 100 100 @{ value = (Indirect "$tb/Outflow") } $null
$tk += OnPopup $fitOut "fit-out-{name}" "fp-analog" "{name} outflow" @{ tag = "[default]Brownsville/Tanks/{name}/Outflow"; unit = "gpm"; decimals = "0"; limits = "No alarm limits configured"; pens = "[default]Brownsville/Tanks/{name}/Outflow" }
$tk += Node "vol" "numeric" @{ decimals = 0; suffix = " gal" } 400 250 170 24 @{ value = (Indirect "$tb/Volume") } $ink
$tk += LinkPipe "in-pipe" @{ from = "in-valve.out"; to = "vessel.inlet"; waypoints = '[{"x":300,"y":69}]' } "$tb/Inflow" -Indirect
$tk += LinkPipe "out-pipe" @{ from = "vessel.outlet"; to = "out-valve.in" } "$tb/Outflow" -Indirect
$tk += LinkPipe "out-meter" @{ from = "out-valve.out"; to = "fit-out.tap"; waypoints = '[{"x":670,"y":173},{"x":670,"y":178}]' } "$tb/Outflow" -Indirect
$tk += Trend "tank-level" 16 360 520 220 "Basin level" "[default]Brownsville/Tanks/{name}/Level"
$tk += Trend "tank-flow" 550 360 400 220 "Basin flow" "[default]Brownsville/Tanks/{name}/Inflow,[default]Brownsville/Tanks/{name}/Outflow"
Publish-View "tank" "Holding tank" (Root 980 640 $tk $tankParam)

$plantParam = @{ name = @{ value = "ResacaBend"; direction = "input" } }
$pb = "[default]Brownsville/Plants/{name}"
$pl = @()
$pl += Node "title" "label" @{ text = "" } 16 8 360 28 @{ text = @{ kind = "expression"; expression = "{view.params.name}" } } @{ color = "#e8eef4"; fontSize = 22 }
$steps = @(
    @{ id = "hw"; label = "Headworks"; tag = "FIT"; path = "InfluentFlow"; unit = "gpm"; dec = 0 },
    @{ id = "screen"; label = "Bar screen"; tag = "PIT"; path = "BarScreenDp"; unit = "psi"; dec = 2 },
    @{ id = "grit"; label = "Grit"; tag = "FIT"; path = "GritFlow"; unit = "gpm"; dec = 0 },
    @{ id = "pri"; label = "Primary"; tag = "LIT"; path = "PrimaryLevel"; unit = "ft"; dec = 1 },
    @{ id = "do"; label = "Aeration"; tag = "AIT"; path = "Do"; unit = "mg/L"; dec = 2 },
    @{ id = "sec"; label = "Secondary"; tag = "LIT"; path = "SecondaryLevel"; unit = "ft"; dec = 1 },
    @{ id = "cl2"; label = "Contact"; tag = "AIT"; path = "ChlorineResidual"; unit = "mg/L"; dec = 2 },
    @{ id = "eff"; label = "Effluent"; tag = "FIT"; path = "EffluentFlow"; unit = "gpm"; dec = 0 }
)
$x = 16
$stepIds = @()
foreach ($step in $steps) {
    $meter = Node "$($step.id)-tag" "sensor" @{ tag = $step.tag; label = $step.label; unit = $step.unit; decimals = $step.dec } $x 48 96 96 @{ value = (Indirect "$pb/$($step.path)") } $null
    $pl += OnPopup $meter "plant-{name}-$($step.id)" "fp-analog" "{name} $($step.label)" @{ tag = "[default]Brownsville/Plants/{name}/$($step.path)"; unit = $step.unit; decimals = "$($step.dec)"; limits = "Alarm limits are in the alarm configuration"; pens = "[default]Brownsville/Plants/{name}/$($step.path)" }
    $stepIds += "$($step.id)-tag"
    $x += 130
}
for ($i = 0; $i -lt ($stepIds.Count - 1); $i++) {
    $pl += LinkPipe "train-$i" @{ from = "$($stepIds[$i]).tap"; to = "$($stepIds[$i + 1]).tap" } "$pb/InfluentFlow" -Indirect
}
$blower = Node "blower" "pump" @{ label = "Blower" } 560 210 80 80 @{ state = (Indirect "$pb/BlowerRun") } $null
$pl += OnPopup $blower "pid-{name}" "fp-pid" "{name} dissolved oxygen" @{ root = "[default]Brownsville/Plants/{name}"; pens = "[default]Brownsville/Plants/{name}/Do,[default]Brownsville/Plants/{name}/DoSp" }
$pl += LinkPipe "blower-air" @{ from = "blower.discharge"; toPoint = @{ x = 700; y = 237 }; tee = "end" } "$pb/AirFlow" -Indirect
$pl += Node "air" "numeric" @{ decimals = 0; suffix = " % air" } 560 300 180 24 @{ value = (Indirect "$pb/BlowerSpeed") } $ink
$pl += Node "uv" "indicator" @{ label = "UV"; onText = "On"; offText = "Off" } 780 210 110 32 @{ value = (Indirect "$pb/UvRun") } $null
$pl += Node "ph" "sensor" @{ tag = "AIT"; label = "pH"; unit = ""; decimals = 2 } 900 200 110 120 @{ value = (Indirect "$pb/Ph") } $null
$pl += Node "turb" "sensor" @{ tag = "AIT"; label = "Turbidity"; unit = "NTU"; decimals = 2 } 1020 200 120 120 @{ value = (Indirect "$pb/Turbidity") } $null
$pl += Node "to-head" "link" @{ text = "Headworks"; url = "/headworks" } 16 340 110 22 $null @{ color = "#e8eef4" }
$pl += Node "to-air" "link" @{ text = "Aeration"; url = "/aeration" } 140 340 100 22 $null @{ color = "#e8eef4" }
$pl += Node "to-clar" "link" @{ text = "Clarifiers"; url = "/clarifiers" } 250 340 110 22 $null @{ color = "#e8eef4" }
$pl += Node "to-dis" "link" @{ text = "Disinfection"; url = "/disinfection" } 370 340 120 22 $null @{ color = "#e8eef4" }
$pl += Trend "plant-flow" 16 380 560 240 "Influent (gpm)" "[default]Brownsville/Plants/{name}/InfluentFlow,[default]Brownsville/Plants/{name}/EffluentFlow"
$pl += Trend "plant-do" 590 380 540 240 "Dissolved oxygen (mg/L)" "[default]Brownsville/Plants/{name}/Do,[default]Brownsville/Plants/{name}/DoSp"
Publish-View "plant" "Treatment plant" (Root 1160 660 $pl $plantParam)

function Area($Name, $Title, $Items, $Pens) {
    $nodes = @()
    $nodes += Node "h-a" "label" @{ text = "Resaca Bend" } 16 8 240 24 $null $ink
    $nodes += Node "h-b" "label" @{ text = "Laguna Works" } 620 8 240 24 $null $ink
    $yy = 40
    foreach ($item in $Items) {
        foreach ($side in @(@{ s = "a"; plant = "ResacaBend"; x = 16 }, @{ s = "b"; plant = "LagunaWorks"; x = 620 })) {
            $path = "[default]Brownsville/Plants/$($side.plant)/$($item.path)"
            $meter = Node "$($item.id)-$($side.s)" "sensor" @{ tag = $item.tag; label = $item.label; unit = $item.unit; decimals = $item.dec } $side.x $yy 96 96 @{ value = (Direct $path) } $null
            $nodes += OnPopup $meter "analog-$($side.plant)-$($item.id)" "fp-analog" "$($item.label)" @{ tag = $path; unit = $item.unit; decimals = "$($item.dec)"; limits = "Alarm limits are in the alarm configuration"; pens = $path }
            if ($item.state) {
                $eq = Node "$($item.id)-$($side.s)-eq" "pump" @{ label = $item.label } ($side.x + 150) ($yy - 4) 72 72 @{ state = (Direct "[default]Brownsville/Plants/$($side.plant)/$($item.state)") } $null
                $nodes += $eq
                $nodes += LinkPipe "$($item.id)-$($side.s)-pipe" @{ from = "$($item.id)-$($side.s).side"; to = "$($item.id)-$($side.s)-eq.suction" } $path
                $nodes += LinkPipe "$($item.id)-$($side.s)-out" @{ from = "$($item.id)-$($side.s)-eq.discharge"; toPoint = @{ x = ($side.x + 250); y = ($yy + 20) }; tee = "end" } $path
            }
        }
        $yy += 130
    }
    $nodes += Trend "$Name-trend" 16 ($yy + 8) 1100 240 $Title $Pens
    Publish-View $Name $Title (Root 1140 ($yy + 260) $nodes @{})
}
Area "headworks" "Headworks" @(
    @{ id = "in"; label = "Influent"; tag = "FIT"; path = "InfluentFlow"; unit = "gpm"; dec = 0 },
    @{ id = "sc"; label = "Bar screen"; tag = "PIT"; path = "BarScreenDp"; unit = "psi"; dec = 2; state = "BarScreenRun" },
    @{ id = "gr"; label = "Grit"; tag = "FIT"; path = "GritFlow"; unit = "gpm"; dec = 0 }
) "[default]Brownsville/Plants/ResacaBend/InfluentFlow,[default]Brownsville/Plants/ResacaBend/BarScreenDp,[default]Brownsville/Plants/LagunaWorks/InfluentFlow"
Area "aeration" "Aeration" @(
    @{ id = "do"; label = "DO"; tag = "AIT"; path = "Do"; unit = "mg/L"; dec = 2 },
    @{ id = "sp"; label = "DO SP"; tag = "AIT"; path = "DoSp"; unit = "mg/L"; dec = 2 },
    @{ id = "bl"; label = "Blower"; tag = "FIT"; path = "AirFlow"; unit = "scfm"; dec = 0; state = "BlowerRun" }
) "[default]Brownsville/Plants/ResacaBend/Do,[default]Brownsville/Plants/ResacaBend/DoSp,[default]Brownsville/Plants/ResacaBend/BlowerSpeed,[default]Brownsville/Plants/LagunaWorks/Do,[default]Brownsville/Plants/LagunaWorks/DoSp"
Area "clarifiers" "Clarifiers" @(
    @{ id = "pr"; label = "Primary"; tag = "LIT"; path = "PrimaryLevel"; unit = "ft"; dec = 1 },
    @{ id = "pf"; label = "Primary flow"; tag = "FIT"; path = "PrimaryFlow"; unit = "gpm"; dec = 0 },
    @{ id = "se"; label = "Secondary"; tag = "LIT"; path = "SecondaryLevel"; unit = "ft"; dec = 1 },
    @{ id = "ras"; label = "RAS"; tag = "FIT"; path = "RasFlow"; unit = "gpm"; dec = 0 },
    @{ id = "was"; label = "WAS"; tag = "FIT"; path = "WasFlow"; unit = "gpm"; dec = 0 }
) "[default]Brownsville/Plants/ResacaBend/PrimaryLevel,[default]Brownsville/Plants/ResacaBend/RasFlow,[default]Brownsville/Plants/ResacaBend/WasFlow,[default]Brownsville/Plants/LagunaWorks/PrimaryLevel"
Area "disinfection" "Disinfection" @(
    @{ id = "uv"; label = "UV flow"; tag = "FIT"; path = "EffluentFlow"; unit = "gpm"; dec = 0; state = "UvRun" },
    @{ id = "cl"; label = "Chlorine"; tag = "AIT"; path = "ChlorineResidual"; unit = "mg/L"; dec = 2 },
    @{ id = "tu"; label = "Turbidity"; tag = "AIT"; path = "Turbidity"; unit = "NTU"; dec = 2 },
    @{ id = "ph"; label = "pH"; tag = "AIT"; path = "Ph"; unit = ""; dec = 2 }
) "[default]Brownsville/Plants/ResacaBend/ChlorineResidual,[default]Brownsville/Plants/ResacaBend/Turbidity,[default]Brownsville/Plants/ResacaBend/EffluentFlow,[default]Brownsville/Plants/LagunaWorks/ChlorineResidual"

$alarms = @()
$alarms += Node "title" "label" @{ text = "Alarm summary" } 16 8 320 26 $null @{ color = "#e8eef4"; fontSize = 22 }
$ax = 16
foreach ($tank in $tanks) {
    $alarms += Node "$($tank.n)-well" "cylindrical-tank" @{ label = $tank.n; min = 0; max = 16; unit = "ft"; decimals = 1; alarmHigh = 88 } $ax 40 70 90 @{ level = (Direct "[default]Brownsville/Tanks/$($tank.n)/Level") } $null
    $ax += 120
}
$alarms += Node "table" "alarm-status-table" @{
    sourceFilter = "*Brownsville*"
    columns = "activeTime, displayPath, label, priority, state"
    refreshRate = 2000
    showClearedAcked = $false
} 16 150 1100 280 $null $null
$alarms += Trend "alarm-trend" 16 440 1100 220 "Levels that carry alarms" "[default]Brownsville/Stations/CanalStreet/Level,[default]Brownsville/Tanks/PortBasin/Level,[default]Brownsville/Stations/CinderFlat/Level"
Publish-View "alarms" "Alarms" (Root 1140 680 $alarms @{})

$allPens = "[default]Brownsville/TotalInflow,[default]Brownsville/Stations/CinderFlat/Level,[default]Brownsville/Stations/CinderFlat/Flow,[default]Brownsville/Stations/OldLevee/Level,[default]Brownsville/Stations/OldLevee/Flow,[default]Brownsville/Stations/BayouEnd/Level,[default]Brownsville/Tanks/NorthBasin/Level,[default]Brownsville/Tanks/CentralBasin/Level,[default]Brownsville/Tanks/PortBasin/Level,[default]Brownsville/Tanks/PortBasin/Outflow,[default]Brownsville/Plants/ResacaBend/InfluentFlow,[default]Brownsville/Plants/ResacaBend/Do,[default]Brownsville/Plants/ResacaBend/DoSp,[default]Brownsville/Plants/ResacaBend/ChlorineResidual,[default]Brownsville/Plants/LagunaWorks/InfluentFlow,[default]Brownsville/Plants/LagunaWorks/Do,[default]Brownsville/Plants/LagunaWorks/ChlorineResidual"
$trends = @(
    (Node "title" "label" @{ text = "Trends" } 16 8 200 26 $null @{ color = "#e8eef4"; fontSize = 22 }),
    (Node "hint" "label" @{ text = "Drag to pan, pinch or scroll to zoom, tap for the crosshair. Pens and range stay on this phone." } 16 36 900 18 $null $muted),
    (Trend "level-trend" 16 60 1120 250 "Levels (ft)" "[default]Brownsville/Stations/CinderFlat/Level,[default]Brownsville/Stations/OldLevee/Level,[default]Brownsville/Stations/BayouEnd/Level,[default]Brownsville/Tanks/NorthBasin/Level,[default]Brownsville/Tanks/CentralBasin/Level,[default]Brownsville/Tanks/PortBasin/Level"),
    (Trend "flow-trend" 16 330 1120 250 "Flows (gpm)" "[default]Brownsville/TotalInflow,[default]Brownsville/Stations/CinderFlat/Flow,[default]Brownsville/Stations/OldLevee/Flow,[default]Brownsville/Tanks/NorthBasin/Outflow,[default]Brownsville/Tanks/PortBasin/Outflow,[default]Brownsville/Plants/ResacaBend/InfluentFlow,[default]Brownsville/Plants/LagunaWorks/InfluentFlow")
)
Publish-View "trends" "Trends" (Root 1160 620 $trends @{})

# Network schematic: one column per basin so each force main runs right, then shares a trunk into the basin.
$net = @()
$net += Node "title" "label" @{ text = "Collection network" } 12 6 420 24 $null @{ color = "#e8eef4"; fontSize = 20 }
$net += Node "hint" "label" @{ text = "Each station has its own main. Arrows move only while that main is flowing." } 12 30 720 16 $null $muted
$groups = @{ NorthBasin = 64; CentralBasin = 460; PortBasin = 980 }
$seen = @{}
foreach ($station in $stations) {
    if (-not $seen.ContainsKey($station.to)) { $seen[$station.to] = 0 }
    $i = $seen[$station.to]
    $seen[$station.to] = $i + 1
    $x = 16
    $yy = $groups[$station.to] + $i * 110
    $path = "[default]Brownsville/Stations/$($station.n)"
    $net += Node "$($station.n)-link" "link" @{ text = $station.n; url = "/station/$($station.n)" } $x $yy 140 18 $null @{ color = "#e8eef4"; fontSize = 13 }
    $well = Node "$($station.n)-well" "cylindrical-tank" @{ label = ""; min = 0; max = 18; unit = "ft"; decimals = 1; alarmHigh = 78 } $x ($yy + 20) 64 64 @{ level = (Direct "$path/Level") } $null
    $net += OnPopup $well "net-$($station.n)" "fp-station" $station.n @{ root = $path; page = "/station/$($station.n)"; pens = "$path/Level,$path/Flow" }
    $pump = Node "$($station.n)-pump" "pump" @{ label = "" } ($x + 100) ($yy + 24) 56 56 @{ state = (Direct "$path/P1/Run") } $null
    $net += OnPopup $pump "net-$($station.n)-p1" "fp-pump" "$($station.n) P1" @{ root = "$path/P1"; flow = "$path/Flow"; pens = "$path/P1/Amps,$path/P1/Speed,$path/Flow" }
    $net += Node "$($station.n)-flow" "numeric" @{ decimals = 0; suffix = " gpm" } ($x + 168) ($yy + 78) 140 22 @{ value = (Direct "$path/Flow") } @{ color = "#d7dee6"; fontSize = 13 }
    $net += LinkPipe "$($station.n)-suction" @{ from = "$($station.n)-well.outlet"; to = "$($station.n)-pump.suction" } "$path/Flow"
    $approach = $groups[$station.to] + 28
    $net += LinkPipe "$($station.n)-fm" @{ from = "$($station.n)-pump.discharge"; to = "$($station.to)-vessel.inlet"; waypoints = "[{`"x`":620,`"y`":$([int]($yy + 43))},{`"x`":620,`"y`":$approach},{`"x`":750,`"y`":$approach}]"; tee = "end" } "$path/Flow"
}
foreach ($tank in $tanks) {
    $yy = $groups[$tank.n] + 20
    $path = "[default]Brownsville/Tanks/$($tank.n)"
    $net += Node "$($tank.n)-link" "link" @{ text = $tank.n; url = "/tank/$($tank.n)" } 820 $yy 170 18 $null @{ color = "#e8eef4"; fontSize = 14 }
    $vessel = Node "$($tank.n)-vessel" "vessel" @{ label = "EQ"; min = 0; max = 16; unit = "ft"; decimals = 1; alarmHigh = 88 } 700 ($yy + 24) 100 100 @{ level = (Direct "$path/Level") } $null
    $net += OnPopup $vessel "net-$($tank.n)" "fp-basin" $tank.n @{ root = $path; pens = "$path/Level,$path/Outflow" }
    $net += Node "$($tank.n)-out" "numeric" @{ decimals = 0; suffix = " gpm" } 810 ($yy + 28) 130 22 @{ value = (Direct "$path/Outflow") } $ink
    $plantId = $(if ($tank.plant -eq "ResacaBend") { "rb-fit" } else { "lw-fit" })
    $tapY = $(if ($tank.plant -eq "ResacaBend") { 194 } else { 1094 })
    $outY = $groups[$tank.n] + 92
    $net += LinkPipe "$($tank.n)-to-plant" @{ from = "$($tank.n)-vessel.outlet"; to = "$plantId.tap"; waypoints = "[{`"x`":900,`"y`":$outY},{`"x`":900,`"y`":$tapY}]"; tee = "start" } "$path/Outflow"
}
$net += Node "rb-link" "link" @{ text = "ResacaBend"; url = "/plant/ResacaBend" } 980 80 160 18 $null @{ color = "#e8eef4"; fontSize = 14 }
$net += Node "rb-fit" "sensor" @{ tag = "FIT"; label = "In"; unit = "gpm"; decimals = 0 } 980 100 96 96 @{ value = (Direct "[default]Brownsville/Plants/ResacaBend/InfluentFlow") } $null
$net += Node "lw-link" "link" @{ text = "LagunaWorks"; url = "/plant/LagunaWorks" } 980 980 170 18 $null @{ color = "#e8eef4"; fontSize = 14 }
$net += Node "lw-fit" "sensor" @{ tag = "FIT"; label = "In"; unit = "gpm"; decimals = 0 } 980 1000 96 96 @{ value = (Direct "[default]Brownsville/Plants/LagunaWorks/InfluentFlow") } $null
$net += Trend "net-trend" 16 1560 1100 200 "Force main and plant flows" "[default]Brownsville/TotalInflow,[default]Brownsville/Tanks/NorthBasin/Outflow,[default]Brownsville/Tanks/PortBasin/Outflow,[default]Brownsville/Plants/ResacaBend/InfluentFlow,[default]Brownsville/Plants/LagunaWorks/InfluentFlow"
Publish-View "network" "Network" (Root 1140 1780 $net @{})

# Street map: Leaflet + OpenStreetMap, live gauges, tap to open the P&ID.
$markerList = @()
$lineList = @()
foreach ($station in $stations) {
    $markerList += @{ id = $station.n; lat = $station.lat; lon = $station.lon; title = $station.n; path = "[default]Brownsville/Stations/$($station.n)/Level"; max = 18; high = 14; unit = "ft"; href = "/station/$($station.n)" }
    $basin = $tanks | Where-Object { $_.n -eq $station.to } | Select-Object -First 1
    $lineList += @{ points = @(@($station.lat, $station.lon), @($basin.lat, $basin.lon)); color = "#2f6f9f" }
}
foreach ($tank in $tanks) {
    $markerList += @{ id = $tank.n; lat = $tank.lat; lon = $tank.lon; title = $tank.n; path = "[default]Brownsville/Tanks/$($tank.n)/Level"; max = 16; high = 14; unit = "ft"; href = "/tank/$($tank.n)" }
    $plant = $plants | Where-Object { $_.n -eq $tank.plant } | Select-Object -First 1
    $lineList += @{ points = @(@($tank.lat, $tank.lon), @($plant.lat, $plant.lon)); color = "#c4a15a" }
}
foreach ($plant in $plants) {
    $markerList += @{ id = $plant.n; lat = $plant.lat; lon = $plant.lon; title = $plant.n; path = "[default]Brownsville/Plants/$($plant.n)/InfluentFlow"; max = 6000; high = 5000; unit = "gpm"; href = "/plant/$($plant.n)" }
}
$mapTitle = Node "title" "label" @{ text = "Brownsville" } 0 0 240 24 $null @{ color = "#e8eef4"; fontSize = 20 }
$mapTitle.flex = @{ grow = 0; shrink = 0; basis = 28; display = $true }
$mapHint = Node "hint" "label" @{ text = "Fictional sites on the city basemap. Pinch to zoom. Tap a gauge to open it." } 0 0 720 16 $null $muted
$mapHint.flex = @{ grow = 0; shrink = 0; basis = 22; display = $true }
$mapNode = Node "city-map" "leaflet-map" @{
    label = "Brownsville collection map"
    centerLat = 25.90
    centerLon = -97.49
    zoom = 12
    tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    attribution = "© OpenStreetMap"
    markers = (ConvertTo-Body $markerList)
    lines = (ConvertTo-Body $lineList)
} 0 0 640 480 $null $null
$mapNode.flex = @{ grow = 1; shrink = 1; basis = 480; display = $true }
$mapDoc = @{
    width = 800
    height = 640
    params = @{}
    root = @{
        id = "root"
        kind = "container"
        name = "Root"
        layout = "flex"
        props = @{ direction = "column"; alignItems = "stretch" }
        style = @{ background = "#12171c"; color = "#d7dee6" }
        children = @($mapTitle, $mapHint, $mapNode)
    }
}
Publish-View "map" "Map" $mapDoc

$fpParam = @{
    root = @{ value = "[default]Brownsville/Stations/CinderFlat/P1"; direction = "input" }
    flow = @{ value = "[default]Brownsville/Stations/CinderFlat/Flow"; direction = "input" }
    pens = @{ value = "[default]Brownsville/Stations/CinderFlat/Flow"; direction = "input" }
    tag = @{ value = "[default]Brownsville/Stations/CinderFlat/Level"; direction = "input" }
    unit = @{ value = ""; direction = "input" }
    decimals = @{ value = "1"; direction = "input" }
    limits = @{ value = ""; direction = "input" }
    page = @{ value = "/station/CinderFlat"; direction = "input" }
}
$pumpFace = @()
$pumpFace += Row "run" "indicator" @{ label = "Running"; onText = "Run"; offText = "Stopped" } 36 @{ value = (BindParam "{root}/Run" "root") }
$pumpFace += Row "fault" "indicator" @{ label = "Fault"; onText = "Fault"; offText = "Clear" } 36 @{ value = (BindParam "{root}/Fault" "root") }
$pumpFace += Row "hoa" "multi-state-button" @{ label = "HOA"; states = "0:Off, 1:Hand, 2:Auto" } 44 @{ value = (BindParam "{root}/Hoa" "root" -Write) }
$pumpFace += Row "note" "label" @{ text = "Hand runs the pump. Off stops it." } 22 $null $muted
$pumpFace += Row "amps" "numeric" @{ prefix = "Amps "; decimals = 1; suffix = " A" } 28 @{ value = (BindParam "{root}/Amps" "root") }
$pumpFace += Row "speed" "numeric" @{ prefix = "Speed "; decimals = 0; suffix = " %" } 28 @{ value = (BindParam "{root}/Speed" "root") }
$pumpFace += Row "hours" "numeric" @{ prefix = "Run hours "; decimals = 1; suffix = " h" } 28 @{ value = (BindParam "{root}/Runtime" "root") }
$pumpFace += Row "starts" "numeric" @{ prefix = "Starts "; decimals = 0 } 28 @{ value = (BindParam "{root}/Starts" "root") }
$pumpTrend = Row "trend" "time-series-chart" @{ title = "Amps, speed, flow"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 1; showLegend = $true } 220 $null $null
$pumpTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$pumpTrend.flex = @{ grow = 1; shrink = 1; basis = 220; display = $true }
$pumpFace += $pumpTrend
Publish-View "fp-pump" "Pump faceplate" (FlexRoot $pumpFace $fpParam)

$valveFace = @()
$valveFace += Row "state" "indicator" @{ label = "Position"; onText = "Open"; offText = "Closed" } 36 @{ value = (BindParam "{tag}" "tag") }
$valveFace += Row "toggle" "state-toggle" @{ label = "Command"; onText = "Open"; offText = "Closed" } 44 @{ value = (BindParam "{tag}" "tag" -Write); confirmed = (BindParam "{tag}" "tag") }
$valveFace += Row "note" "label" @{ text = "Open and close write this valve." } 24 $null $muted
$valveTrend = Row "trend" "time-series-chart" @{ title = "Related flow"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 0; showLegend = $true } 240 $null $null
$valveTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$valveTrend.flex = @{ grow = 1; shrink = 1; basis = 240; display = $true }
$valveFace += $valveTrend
Publish-View "fp-valve" "Valve faceplate" (FlexRoot $valveFace $fpParam)

$analogFace = @()
$analogFace += Row "value" "numeric" @{ prefix = "Value "; decimals = 1 } 36 @{ value = (BindParam "{tag}" "tag") }
$unitRow = Row "unit" "label" @{ text = "" } 22 $null $ink
$unitRow.bindings = @{ text = (PropParam "unit") }
$analogFace += $unitRow
$limitRow = Row "limits" "label" @{ text = "" } 36 $null $muted
$limitRow.bindings = @{ text = (PropParam "limits") }
$analogFace += $limitRow
$analogFace += Row "note" "label" @{ text = "Alarm limits shown here are the configured alarm setpoints. They are not separate writable tags." } 40 $null $muted
$analogTrend = Row "trend" "time-series-chart" @{ title = "Value"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 1; showLegend = $true } 250 $null $null
$analogTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$analogTrend.flex = @{ grow = 1; shrink = 1; basis = 250; display = $true }
$analogFace += $analogTrend
Publish-View "fp-analog" "Instrument faceplate" (FlexRoot $analogFace $fpParam)

$wellFace = @()
$wellFace += Row "level" "numeric" @{ prefix = "Level "; decimals = 1; suffix = " ft" } 32 @{ value = (BindParam "{root}/Level" "root") }
$wellFace += Row "inflow" "numeric" @{ prefix = "Inflow "; decimals = 0; suffix = " gpm" } 28 @{ value = (BindParam "{root}/Inflow" "root") }
$wellFace += Row "flow" "numeric" @{ prefix = "Discharge "; decimals = 0; suffix = " gpm" } 28 @{ value = (BindParam "{root}/Flow" "root") }
foreach ($sp in @("LeadOn", "LeadOff", "LagOn", "LagOff")) {
    $wellFace += Row $sp.ToLower() "numeric-entry" @{ label = $sp; suffix = " ft"; decimals = 1; minimum = 0; maximum = 18 } 52 @{ value = (BindParam "{root}/$sp" "root" -Write) }
}
$wellTrend = Row "trend" "time-series-chart" @{ title = "Level and setpoints"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 1; showLegend = $true } 200 $null $null
$wellTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$wellTrend.flex = @{ grow = 1; shrink = 1; basis = 200; display = $true }
$wellFace += $wellTrend
Publish-View "fp-well" "Wet well faceplate" (FlexRoot $wellFace $fpParam)

$basinFace = @()
$basinFace += Row "level" "numeric" @{ prefix = "Level "; decimals = 1; suffix = " ft" } 32 @{ value = (BindParam "{root}/Level" "root") }
$basinFace += Row "inflow" "numeric" @{ prefix = "Inflow "; decimals = 0; suffix = " gpm" } 28 @{ value = (BindParam "{root}/Inflow" "root") }
$basinFace += Row "outflow" "numeric" @{ prefix = "Outflow "; decimals = 0; suffix = " gpm" } 28 @{ value = (BindParam "{root}/Outflow" "root") }
$basinTrend = Row "trend" "time-series-chart" @{ title = "Level and outflow"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 1; showLegend = $true } 240 $null $null
$basinTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$basinTrend.flex = @{ grow = 1; shrink = 1; basis = 240; display = $true }
$basinFace += $basinTrend
Publish-View "fp-basin" "Basin faceplate" (FlexRoot $basinFace $fpParam)

$sumFace = @()
$sumFace += Row "level" "numeric" @{ prefix = "Level "; decimals = 1; suffix = " ft" } 32 @{ value = (BindParam "{root}/Level" "root") }
$sumFace += Row "flow" "numeric" @{ prefix = "Flow "; decimals = 0; suffix = " gpm" } 28 @{ value = (BindParam "{root}/Flow" "root") }
$sumFace += Row "p1" "indicator" @{ label = "P1"; onText = "Run"; offText = "Off" } 32 @{ value = (BindParam "{root}/P1/Run" "root") }
$sumFace += Row "p2" "indicator" @{ label = "P2"; onText = "Run"; offText = "Off" } 32 @{ value = (BindParam "{root}/P2/Run" "root") }
$sumFace += Row "comms" "indicator" @{ label = "Comms"; onText = "Ok"; offText = "Down" } 32 @{ value = (BindParam "{root}/CommsOk" "root") }
$open = Row "open" "link" @{ text = "Open station"; url = "/" } 28 $null @{ color = "#9ec9ef"; fontSize = 16 }
$open.bindings = @{ url = (PropParam "page") }
$sumFace += $open
$sumTrend = Row "trend" "time-series-chart" @{ title = "Level and flow"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 1; showLegend = $true } 200 $null $null
$sumTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$sumTrend.flex = @{ grow = 1; shrink = 1; basis = 200; display = $true }
$sumFace += $sumTrend
Publish-View "fp-station" "Station summary" (FlexRoot $sumFace $fpParam)

$pidFace = @()
$pidFace += Row "pv" "numeric" @{ prefix = "PV "; decimals = 2; suffix = " mg/L" } 32 @{ value = (BindParam "{root}/Do" "root") }
$pidFace += Row "sp" "numeric-entry" @{ label = "SP"; suffix = " mg/L"; decimals = 2; minimum = 0; maximum = 6 } 52 @{ value = (BindParam "{root}/DoSp" "root" -Write) }
$pidFace += Row "cv" "numeric" @{ prefix = "CV "; decimals = 0; suffix = " %" } 32 @{ value = (BindParam "{root}/BlowerSpeed" "root") }
$pidFace += Row "mode" "indicator" @{ label = "Mode"; onText = "Auto"; offText = "Off" } 32 @{ value = (BindParam "{root}/BlowerRun" "root") }
$pidFace += Row "tune" "label" @{ text = "Tuning is fixed. Setpoint is editable. CV is blower speed." } 24 $null $muted
$pidTrend = Row "trend" "time-series-chart" @{ title = "Setpoint and value"; rangeSeconds = 900; historyRefreshSeconds = 5; decimals = 2; showLegend = $true } 210 $null $null
$pidTrend.bindings = @{ historyPens = (PropParam "pens"); penChoices = (PropParam "pens") }
$pidTrend.flex = @{ grow = 1; shrink = 1; basis = 210; display = $true }
$pidFace += $pidTrend
Publish-View "fp-pid" "DO loop faceplate" (FlexRoot $pidFace $fpParam)

$pages = @(
    @{ name = "City"; url = "/"; title = "Brownsville"; primaryView = "overview" },
    @{ name = "Map"; url = "/map"; title = "Map"; primaryView = "map" },
    @{ name = "Network"; url = "/network"; title = "Network"; primaryView = "network" },
    @{ name = "Station"; url = "/station/:name"; title = "Lift station"; primaryView = "station" },
    @{ name = "Tank"; url = "/tank/:name"; title = "Basin"; primaryView = "tank" },
    @{ name = "Plant"; url = "/plant/:name"; title = "Plant"; primaryView = "plant" },
    @{ name = "Headworks"; url = "/headworks"; title = "Headworks"; primaryView = "headworks" },
    @{ name = "Aeration"; url = "/aeration"; title = "Aeration"; primaryView = "aeration" },
    @{ name = "Clarifiers"; url = "/clarifiers"; title = "Clarifiers"; primaryView = "clarifiers" },
    @{ name = "Disinfection"; url = "/disinfection"; title = "Disinfection"; primaryView = "disinfection" },
    @{ name = "Alarms"; url = "/alarms"; title = "Alarms"; primaryView = "alarms" },
    @{ name = "Trends"; url = "/trends"; title = "Trends"; primaryView = "trends" }
)
try { Send-View DELETE "$Gateway/api/v1/config/designer/project/brownsville/PageConfig/page-config" $null | Out-Null } catch { }
$pageBody = @{
    apiVersion = "oip/v1"
    kind = "PageConfig"
    metadata = @{ name = "page-config"; module = "designer"; version = 0 }
    spec = @{
        pages = $pages
        sharedDocks = @(@{ id = "nav"; edge = "left"; view = "nav"; size = 188; display = "auto"; content = "auto"; handle = "autoHide"; autoBreakpoint = 760 })
        cornerPriority = "top-bottom"
    }
}
$saved = Send-View POST "$Gateway/api/v1/config/designer/project/brownsville/PageConfig" $pageBody
Write-Host "pages $($saved.StatusCode)"
$proj = Invoke-WebRequest "$Gateway/api/v1/projects/brownsville" -WebSession $WebSession -UseBasicParsing
$match = $proj.Headers["ETag"]
if (-not $match) { $match = '"' + (($proj.Content | ConvertFrom-Json).version) + '"' }
Invoke-WebRequest "$Gateway/api/v1/projects/brownsville" -Method PATCH -Headers ($Headers + @{ "If-Match" = $match }) -WebSession $WebSession -ContentType "application/json" -Body '{"sessionTheme":"nightwatch","publicHmi":true}' -UseBasicParsing | Out-Null
Write-Host "theme nightwatch"
