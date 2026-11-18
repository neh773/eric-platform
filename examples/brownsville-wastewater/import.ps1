# Install the Brownsville example into a gateway. It is not installed until this runs.
param(
    [string]$Gateway = "http://127.0.0.1:8090",
    [switch]$Replace
)
$ErrorActionPreference = "Stop"
$svc = "HKLM:\SYSTEM\CurrentControlSet\Services\ERIC Gateway\Parameters"
$pass = $env:ERIC_ADMIN_PASSWORD
if (-not $pass -and (Test-Path $svc)) {
    foreach ($line in @((Get-ItemProperty $svc).AppEnvironmentExtra)) {
        if ($line -like "ERIC_ADMIN_PASSWORD=*") { $pass = $line.Substring("ERIC_ADMIN_PASSWORD=".Length) }
    }
}
if (-not $pass) { throw "Set ERIC_ADMIN_PASSWORD or the gateway service environment." }
$login = Invoke-WebRequest "$Gateway/api/v1/auth/login" -Method POST -ContentType "application/json" -Body (@{ username = "admin"; password = $pass } | ConvertTo-Json) -SessionVariable session -UseBasicParsing
$headers = @{ "X-CSRF-Token" = ($login.Content | ConvertFrom-Json).csrfToken }
$query = if ($Replace) { "?replace=true" } else { "" }
$installed = Invoke-WebRequest "$Gateway/api/v1/examples/brownsville-wastewater/install$query" -Method POST -Headers $headers -WebSession $session -UseBasicParsing
$installed.Content
