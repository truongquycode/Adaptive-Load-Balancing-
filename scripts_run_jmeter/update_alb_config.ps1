param(
    [Parameter(Mandatory=$true)] [string] $ApplicationYml,
    [Parameter(Mandatory=$true)] [string] $DeployMarker,
    [Parameter(Mandatory=$true)] [string] $Strategy,
    [Parameter(Mandatory=$true)] [string] $Ablation,
    [Parameter(Mandatory=$true)] [string] $Label
)

$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path -LiteralPath $ApplicationYml)) {
    throw "application.yml not found: $ApplicationYml"
}

$lines = [System.IO.File]::ReadAllLines($ApplicationYml, $enc)
$strategyDone = $false
$ablationDone = $false

# The file currently contains a single ALB strategy line and a single ablation variant line.
# This updater intentionally uses tolerant regex replacement instead of YAML indentation parsing,
# because Windows CMD escaping made the old inline PowerShell parser fragile.
for ($i = 0; $i -lt $lines.Length; $i++) {
    if (-not $strategyDone -and $lines[$i] -match '^\s*strategy\s*:\s*.*$') {
        $indent = [regex]::Match($lines[$i], '^\s*').Value
        $lines[$i] = $indent + 'strategy: ' + $Strategy
        $strategyDone = $true
        continue
    }

    if (-not $ablationDone -and $lines[$i] -match '^\s*variant\s*:\s*.*$') {
        $indent = [regex]::Match($lines[$i], '^\s*').Value
        $lines[$i] = $indent + 'variant: ' + $Ablation
        $ablationDone = $true
        continue
    }
}

if (-not $strategyDone) {
    throw 'Cannot find a strategy line in application.yml. Expected a line like: strategy: ${ALB_STRATEGY:adaptive}'
}

if (-not $ablationDone) {
    throw 'Cannot find an ablation variant line in application.yml. Expected a line like: variant: ${ALB_ABLATION_VARIANT:full}'
}

[System.IO.File]::WriteAllText(
    $ApplicationYml,
    (($lines -join [Environment]::NewLine) + [Environment]::NewLine),
    $enc
)

$marker = @(
    "strategy=$Strategy",
    "ablation=$Ablation",
    "label=$Label",
    ('timestamp=' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
)

[System.IO.File]::WriteAllText(
    $DeployMarker,
    (($marker -join [Environment]::NewLine) + [Environment]::NewLine),
    $enc
)

Write-Host "[INFO] Updated application.yml: strategy=$Strategy, ablation=$Ablation"
Write-Host "[INFO] Updated deploy marker: $DeployMarker"
