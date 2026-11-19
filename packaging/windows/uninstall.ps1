# Undo an ERIC Windows install.
# Default keeps C:\ProgramData\ERIC. -DeleteData removes it.
# Without -Silent, a checkbox (unchecked) asks before deleting that folder.
param(
    [switch]$Silent,
    [switch]$DeleteData,
    [string]$Product = "ERIC",
    [string]$Installers = ""
)
$ErrorActionPreference = "Stop"

function Read-DeleteData {
    if ($DeleteData) { return $true }
    if ($Silent) { return $false }
    Add-Type -AssemblyName System.Windows.Forms
    $form = New-Object System.Windows.Forms.Form
    $form.Text = "Uninstall $Product"
    $form.Width = 540
    $form.Height = 200
    $form.StartPosition = "CenterScreen"
    $box = New-Object System.Windows.Forms.CheckBox
    $box.Text = "Also delete C:\ProgramData\$Product (projects, history, logs)"
    $box.Checked = $false
    $box.Left = 16
    $box.Top = 24
    $box.Width = 490
    $box.Height = 48
    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = "Uninstall"
    $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $ok.Left = 300
    $ok.Top = 100
    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = "Cancel"
    $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $cancel.Left = 400
    $cancel.Top = 100
    $form.Controls.AddRange(@($box, $ok, $cancel))
    $form.AcceptButton = $ok
    $form.CancelButton = $cancel
    if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
    return [bool]$box.Checked
}

$removeData = Read-DeleteData
$dataRoot = Join-Path $env:ProgramData $Product
$service = "$Product Gateway"

$svc = Get-Service -Name $service -ErrorAction SilentlyContinue
if ($null -ne $svc) {
    if ($svc.Status -ne "Stopped") { Stop-Service -Name $service -Force -ErrorAction SilentlyContinue }
    & sc.exe delete $service | Out-Null
}

& netsh advfirewall firewall delete rule name="$service" | Out-Null
Get-NetFirewallRule -DisplayName $service -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue

$menuRoots = @(
    (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$Product"),
    (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$Product")
)
foreach ($menu in $menuRoots) {
    if (Test-Path $menu) { Remove-Item $menu -Recurse -Force }
}
$desktops = @($env:PUBLIC, $env:USERPROFILE) | Where-Object { $_ } | ForEach-Object { Join-Path $_ "Desktop" }
foreach ($desktop in $desktops) {
    if (-not (Test-Path $desktop)) { continue }
    Get-ChildItem $desktop -Filter *.lnk -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -like "*$Product*" -or $_.Name -like "*Control Canvas*"
    } | Remove-Item -Force
}

Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object { $_.TaskName -like "*$Product*" } | ForEach-Object {
    Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false -ErrorAction SilentlyContinue
}

& reg.exe delete "HKLM\Software\$Product" /f | Out-Null
& reg.exe delete "HKLM\SYSTEM\CurrentControlSet\Services\$service" /f | Out-Null
foreach ($name in @("OIP_ADMIN_DIST", "OIP_WEB_DIST", "ERIC_PLC_RESOURCES")) {
    [Environment]::SetEnvironmentVariable($name, $null, "Machine")
}

# Pinning and the gateway -Xmx note go away even when projects are kept.
foreach ($name in @("plc-resources.json", "gateway-jvm.txt")) {
    $path = Join-Path $dataRoot $name
    if (Test-Path $path) { Remove-Item $path -Force }
}

foreach ($path in ($Installers -split '\|' | Where-Object { $_ })) {
    if (-not (Test-Path $path)) { continue }
    $proc = Start-Process -FilePath $path -ArgumentList @("uninstall") -Wait -PassThru
    if ($proc.ExitCode -ne 0 -and $proc.ExitCode -ne 3010 -and $proc.ExitCode -ne 1605) {
        Write-Host "uninstaller $path exited $($proc.ExitCode)"
    }
}
$uninstallKeys = @(
    "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
)
Get-ItemProperty $uninstallKeys -ErrorAction SilentlyContinue | Where-Object {
    $_.DisplayName -eq $service -or $_.DisplayName -eq "Control Canvas"
} | ForEach-Object {
    $cmd = $_.QuietUninstallString
    if (-not $cmd) { $cmd = $_.UninstallString }
    if ($cmd) { cmd.exe /c $cmd | Out-Null }
}
$installs = @(
    "C:\Program Files\$service",
    "C:\Program Files\Control Canvas"
)
foreach ($dir in $installs) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($removeData -and (Test-Path $dataRoot)) {
    Remove-Item $dataRoot -Recurse -Force
}
Write-Host $(if ($removeData) { "uninstalled; ProgramData removed" } else { "uninstalled; ProgramData kept" })
