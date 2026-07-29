$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectDir = (Resolve-Path "$ScriptDir\..").ProviderPath

$RawRoot = "$ProjectDir\benchmark-raw-results\additional-tests"
$AdaptiveRerunDir = "$RawRoot\stress-test\adaptive_full_rerun_2026-07-22"
$BaselineRerunRoot = "$RawRoot\stress-test-rerun"
$FinalRoot = "$RawRoot\stress-test-final"

function Write-Log {
    param([string]$Message, [string]$Level="INFO")
    $Color = "White"
    if ($Level -eq "ERROR") { $Color = "Red" }
    elseif ($Level -eq "WARN") { $Color = "Yellow" }
    elseif ($Level -eq "SUCCESS") { $Color = "Green" }
    Write-Host "[$Level] $Message" -ForegroundColor $Color
}

Write-Log "============================================================="
Write-Log "Building Stress Test Final Dataset (FIXED PATH)"
Write-Log "Target: $FinalRoot"
Write-Log "============================================================="

if (Test-Path $FinalRoot) {
    Write-Log "Removing existing stress-test-final directory..." "WARN"
    Remove-Item -Recurse -Force $FinalRoot
}
New-Item -ItemType Directory -Path $FinalRoot | Out-Null

Write-Log "1. Copying Adaptive Full (from rerun)..."
if (Test-Path $AdaptiveRerunDir) {
    Copy-Item -Path $AdaptiveRerunDir -Destination "$FinalRoot\adaptive_full" -Recurse
    Write-Log "Copied: adaptive_full_rerun_2026-07-22 -> stress-test-final\adaptive_full" "SUCCESS"
} else {
    Write-Log "Source directory NOT FOUND: $AdaptiveRerunDir" "ERROR"
    exit 1
}

# Mapping: Tên thực tế sinh ra bởi Benchmark Script -> Tên chuẩn yêu cầu bởi Python Parser
$Baselines = @(
    @{ Src="least_connections"; Dst="least_connect" },
    @{ Src="random"; Dst="random" },
    @{ Src="round_robin"; Dst="round_robin" }
)

foreach ($base in $Baselines) {
    $src = $base.Src
    $dst = $base.Dst
    Write-Log "2. Copying Baseline ($src -> $dst)..."
    
    $BaseDir = "$BaselineRerunRoot\$src"
    if (-not (Test-Path $BaseDir)) {
        Write-Log "Source directory NOT FOUND: $BaseDir" "ERROR"
        exit 1
    }
    
    # Copy folder
    $DstDir = "$FinalRoot\$dst"
    Copy-Item -Path $BaseDir -Destination $DstDir -Recurse
    Write-Log "Copied: $src -> stress-test-final\$dst" "SUCCESS"
    
    # Fix Tên File nội bộ
    if ($src -ne $dst) {
        Write-Log "Renaming internal files from prefix '$src' to '$dst'..."
        
        # Rename files (.jtl, -metadata.txt)
        Get-ChildItem -Path $DstDir -File | Where-Object { $_.Name -match "^$src" } | ForEach-Object {
            $newName = $_.Name -replace "^$src", $dst
            Rename-Item -Path $_.FullName -NewName $newName
        }
        
        # Rename HTML folders
        Get-ChildItem -Path $DstDir -Directory | Where-Object { $_.Name -match "^$src" } | ForEach-Object {
            $newName = $_.Name -replace "^$src", $dst
            Rename-Item -Path $_.FullName -NewName $newName
        }
    }
}

Write-Log "============================================================="
Write-Log "DATASET VALIDATION"
Write-Log "============================================================="
$TotalRuns = 0
$ExpectedStrats = @("adaptive_full", "least_connect", "random", "round_robin")
foreach ($strat in $ExpectedStrats) {
    $found = 0
    for ($i = 1; $i -le 3; $i++) {
        $jtl = "$FinalRoot\$strat\$strat-$i.jtl"
        if (Test-Path $jtl) { $found++ }
    }
    $TotalRuns += $found
    Write-Log "$strat : $found/3"
}

Write-Log ""
if ($TotalRuns -eq 12) {
    Write-Log "TOTAL              : 12/12"
    Write-Log "STATUS             : VALID" "SUCCESS"
    Write-Log "Dataset Built Successfully! Ready for Python Pipeline parsing."
} else {
    Write-Log "TOTAL              : $TotalRuns/12"
    Write-Log "STATUS             : INVALID" "ERROR"
    exit 1
}
Write-Log "============================================================="
