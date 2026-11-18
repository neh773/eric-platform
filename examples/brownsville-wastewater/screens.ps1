param(
    [Parameter(Mandatory = $true)][string]$Gateway,
    [Parameter(Mandatory = $true)]$WebSession,
    [Parameter(Mandatory = $true)]$Headers
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "json.ps1")

function Send-View([string]$Method, [string]$Url, $Body) {
    $json = ConvertTo-Body $Body
    try {
        return Invoke-WebRequest -Uri $Url -Method $Method -Headers $Headers -WebSession $WebSession -ContentType "application/json; charset=utf-8" -Body $json -UseBasicParsing -TimeoutSec 60
    } catch {
        $detail = $_.ErrorDetails.Message
        if (-not $detail) { $detail = $_.Exception.Message }
        throw "$Method $Url failed: $detail"
    }
}

function Publish-View([string]$Name, [string]$Title, $Document) {
    $body = @{
        apiVersion = "oip/v1"
        kind = "View"
        metadata = @{ name = $Name; module = "designer"; version = 0 }
        spec = @{ path = $Name; title = $Title; document = $Document }
    }
    $url = "$Gateway/api/v1/config/designer/project/brownsville/View"
    try { Send-View DELETE "$url/$Name" $null | Out-Null } catch { }
    $created = Send-View POST $url $body
    Write-Host "view $Name $($created.StatusCode)"
}

function Node($Id, $Type, $Props, $X, $Y, $W, $H, $Bindings, $Style) {
    $node = @{
        id = $Id
        kind = "component"
        name = $Id
        type = $Type
        props = $Props
        frame = @{ x = $X; y = $Y; width = $W; height = $H }
    }
    if ($Bindings) { $node.bindings = $Bindings }
    if ($Style) { $node.style = $Style }
    return $node
}

function Direct($Path, [switch]$Write) {
    @{ kind = "tag"; mode = "direct"; path = $Path; bidirectional = [bool]$Write; publishInitialUncertain = $true; overlayOptOut = $true }
}

function Indirect($Pattern, [switch]$Write) {
    @{
        kind = "tag"
        mode = "indirect"
        path = $Pattern
        bidirectional = [bool]$Write
        publishInitialUncertain = $true
        overlayOptOut = $true
        references = @{ name = @{ source = "param"; name = "name" } }
    }
}

function Root($Width, $Height, $Children, $Params) {
    @{
        width = $Width
        height = $Height
        params = $Params
        root = @{
            id = "root"
            kind = "container"
            name = "Root"
            layout = "coordinate"
            props = @{ mode = "fixed" }
            style = @{ background = "#12171c"; color = "#d7dee6" }
            children = $Children
        }
    }
}

function BindParam($Pattern, $Param, [switch]$Write) {
    @{
        kind = "tag"
        mode = "indirect"
        path = $Pattern
        bidirectional = [bool]$Write
        publishInitialUncertain = $true
        overlayOptOut = $true
        references = @{ $Param = @{ source = "param"; name = $Param } }
    }
}

function PropParam($Name) {
    @{ kind = "property"; source = @{ scope = "param"; name = $Name } }
}

function Row($Id, $Type, $Props, $H, $Bindings) {
    $node = Node $Id $Type $Props 0 0 480 $H $Bindings $null
    $node.flex = @{ grow = 0; shrink = 0; basis = $H; display = $true }
    return $node
}

function OnPopup($Node, $PopupId, $View, $Title, $Params) {
    $Node.events = @(@{
        event = "onClick"
        actions = @(@{
            kind = "popup"
            mode = "open"
            id = $PopupId
            view = $View
            title = $Title
            showClose = $true
            draggable = $true
            modal = $false
            viewportBound = $true
            position = @{ width = 520; height = 540; left = 210; top = 36 }
            params = $Params
        })
    })
    return $Node
}

function FlexRoot($Children, $Params) {
    @{
        width = 500
        height = 500
        params = $Params
        root = @{
            id = "root"
            kind = "container"
            name = "Root"
            layout = "flex"
            props = @{ direction = "column"; alignItems = "stretch" }
            style = @{ background = "#12171c"; color = "#d7dee6" }
            children = $Children
        }
    }
}

. (Join-Path $PSScriptRoot "pid.ps1")
