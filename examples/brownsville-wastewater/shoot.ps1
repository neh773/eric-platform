# Signs into the local gateway and saves a screenshot of each Brownsville screen.
param([string[]]$Only, [string]$OutDir = "", [string]$Size = "1440,900")
$ErrorActionPreference = "Stop"
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot "screenshots" }
$out = $OutDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$extra = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\ERIC Gateway\Parameters").AppEnvironmentExtra
$pass = $null
foreach ($line in @($extra)) { if ($line -like "ERIC_ADMIN_PASSWORD=*") { $pass = $line.Substring(20) } }
if (-not $pass) { throw "admin password is not in the service environment" }
$login = Invoke-WebRequest "http://127.0.0.1:8090/api/v1/auth/login" -Method POST -ContentType "application/json" -Body (@{ username = "admin"; password = $pass } | ConvertTo-Json) -SessionVariable session -UseBasicParsing
$cookie = $session.Cookies.GetCookies("http://127.0.0.1:8090") | Where-Object { $_.Name -eq "oip_session" } | Select-Object -First 1
if (-not $cookie) { throw "login did not set a session cookie" }

$profile = Join-Path $PSScriptRoot "edge-profile"
if (Test-Path $profile) { Remove-Item $profile -Recurse -Force }
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$browser = Start-Process -FilePath $edge -ArgumentList @(
    "--headless=new", "--disable-gpu", "--remote-debugging-port=9222",
    "--user-data-dir=$profile", "--window-size=$Size", "about:blank"
) -PassThru
$version = $null
for ($i = 0; $i -lt 30 -and -not $version; $i++) {
    Start-Sleep -Milliseconds 300
    try { $version = Invoke-RestMethod "http://127.0.0.1:9222/json/version" -TimeoutSec 2 } catch { }
}
if (-not $version) { throw "Edge did not open a debugging port" }
$pages = Invoke-RestMethod "http://127.0.0.1:9222/json/list"
$page = $pages | Where-Object { $_.type -eq "page" } | Select-Object -First 1
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $cts.Token).Wait()
$script:nextId = 1
function Send-Cdp($method, $params) {
    $id = $script:nextId
    $script:nextId++
    $payload = @{ id = $id; method = $method; params = $params } | ConvertTo-Json -Compress -Depth 8
    $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
    $segment = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
    $ws.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).Wait()
    return $id
}
function Receive-Cdp {
    $buffer = New-Object byte[] 262144
    $stream = New-Object System.IO.MemoryStream
    do {
        $segment = New-Object System.ArraySegment[byte] -ArgumentList @(,$buffer)
        $result = $ws.ReceiveAsync($segment, $cts.Token).Result
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { throw "debugging socket closed" }
        $stream.Write($buffer, 0, $result.Count)
    } while (-not $result.EndOfMessage)
    return [Text.Encoding]::UTF8.GetString($stream.ToArray())
}
function Invoke-Cdp($method, $params) {
    $id = Send-Cdp $method $params
    while ($true) {
        $raw = Receive-Cdp
        if ($raw -match ('"id"\s*:\s*' + $id + '\b')) { return $raw }
    }
}
Invoke-Cdp "Network.enable" @{} | Out-Null
Invoke-Cdp "Page.enable" @{} | Out-Null
Invoke-Cdp "Network.setCookie" @{ name = "oip_session"; value = $cookie.Value; url = "http://127.0.0.1:8090"; httpOnly = $true } | Out-Null
$shots = @(
    @{ file = "overview.png"; url = "http://127.0.0.1:8090/hmi/brownsville/" },
    @{ file = "map.png"; url = "http://127.0.0.1:8090/hmi/brownsville/map" },
    @{ file = "network.png"; url = "http://127.0.0.1:8090/hmi/brownsville/network" },
    @{ file = "station.png"; url = "http://127.0.0.1:8090/hmi/brownsville/station/CinderFlat" },
    @{ file = "tank.png"; url = "http://127.0.0.1:8090/hmi/brownsville/tank/NorthBasin" },
    @{ file = "plant.png"; url = "http://127.0.0.1:8090/hmi/brownsville/plant/ResacaBend" },
    @{ file = "headworks.png"; url = "http://127.0.0.1:8090/hmi/brownsville/headworks" },
    @{ file = "aeration.png"; url = "http://127.0.0.1:8090/hmi/brownsville/aeration" },
    @{ file = "clarifiers.png"; url = "http://127.0.0.1:8090/hmi/brownsville/clarifiers" },
    @{ file = "disinfection.png"; url = "http://127.0.0.1:8090/hmi/brownsville/disinfection" },
    @{ file = "alarms.png"; url = "http://127.0.0.1:8090/hmi/brownsville/alarms" },
    @{ file = "trends.png"; url = "http://127.0.0.1:8090/hmi/brownsville/trends" }
)
if ($Only) { $shots = @($shots | Where-Object { $_.file -in $Only -or $_.file.Replace(".png","") -in $Only }) }
foreach ($shot in $shots) {
    Invoke-Cdp "Page.navigate" @{ url = $shot.url } | Out-Null
    Start-Sleep -Seconds $(if ($shot.file -eq "map.png") { 7 } else { 4 })
    $raw = Invoke-Cdp "Page.captureScreenshot" @{ format = "png"; captureBeyondViewport = $true }
    $match = [regex]::Match($raw, '"data"\s*:\s*"([^"]+)"')
    if (-not $match.Success) { throw "no screenshot data for $($shot.file)" }
    [IO.File]::WriteAllBytes((Join-Path $out $shot.file), [Convert]::FromBase64String($match.Groups[1].Value))
    "saved $($shot.file) $((Get-Item (Join-Path $out $shot.file)).Length)"
}
function Save-Shot([string]$Name) {
    $raw = Invoke-Cdp "Page.captureScreenshot" @{ format = "png"; captureBeyondViewport = $true }
    $match = [regex]::Match($raw, '"data"\s*:\s*"([^"]+)"')
    if (-not $match.Success) { throw "no screenshot data for $Name" }
    [IO.File]::WriteAllBytes((Join-Path $out $Name), [Convert]::FromBase64String($match.Groups[1].Value))
    "saved $Name"
}
function Click-Node([string]$Id) {
    $expr = "(() => { const n = document.querySelector('[data-node-id=""$Id""]'); if (!n) return 'missing'; n.click(); return 'ok'; })()"
    Invoke-Cdp "Runtime.evaluate" @{ expression = $expr; returnByValue = $true } | Out-Null
}
if (-not $Only) {
    $faces = @(
        @{ url = "http://127.0.0.1:8090/hmi/brownsville/station/CinderFlat"; click = "P1-symbol"; file = "popup-pump.png" },
        @{ url = ""; click = "well"; file = "popup-well.png" },
        @{ url = "http://127.0.0.1:8090/hmi/brownsville/tank/NorthBasin"; click = "in-valve"; file = "popup-valve.png" },
        @{ url = "http://127.0.0.1:8090/hmi/brownsville/plant/ResacaBend"; click = "hw-tag"; file = "popup-analog.png" },
        @{ url = ""; click = "blower"; file = "popup-pid.png" },
        @{ url = "http://127.0.0.1:8090/hmi/brownsville/"; click = "CinderFlat-well"; file = "popup-station.png" }
    )
    foreach ($face in $faces) {
        if ($face.url) {
            Invoke-Cdp "Page.navigate" @{ url = $face.url } | Out-Null
            Start-Sleep -Seconds 4
        }
        Click-Node $face.click
        Start-Sleep -Seconds 2
        Save-Shot $face.file
    }
}
if (-not $Only) {
    Invoke-Cdp "Page.navigate" @{ url = "http://127.0.0.1:8090/hmi/brownsville/trends" } | Out-Null
    Start-Sleep -Seconds 5
    Invoke-Cdp "Runtime.evaluate" @{ expression = "(() => { const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '1 h'); if (!b) return 'missing'; b.click(); return 'clicked'; })()"; returnByValue = $true } | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Cdp "Page.reload" @{ ignoreCache = $true } | Out-Null
    Start-Sleep -Seconds 5
    $pressed = Invoke-Cdp "Runtime.evaluate" @{ expression = "(() => { const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '1 h'); return b ? b.getAttribute('aria-pressed') : 'missing'; })()"; returnByValue = $true }
    "trend-persist $pressed"
}
try { $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", $cts.Token).Wait() } catch { }
Stop-Process -Id $browser.Id -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -like "*edge-brownsville*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
"done"
