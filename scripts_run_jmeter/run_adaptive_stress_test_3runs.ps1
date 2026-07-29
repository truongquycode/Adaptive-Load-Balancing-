param (
    [string]$JMeterHome = "D:\Downloads\apache-jmeter-5.6.3",
    [string]$TestPlan = "..\jmeter\04_stress_overload_graceful_degradation_mixed_1200_tst.jmx",
    [int]$WaitAfterReset = 20,
    [int]$WaitBetweenRuns = 400,
    [int]$VerifyRetries = 18,
    [int]$VerifyInterval = 10,
    [string]$ServerBaseUrl = "http://172.30.35.37:8080",
    [string]$BackendBaseUrl = "http://172.30.35.37",
    [string]$BackendPorts = "8081 8082 8083"
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectDir = (Resolve-Path "$ScriptDir\..").ProviderPath

$ExpectedStrategy = "adaptive"
$ExpectedAblation = "full"
$StrategyLabel = "adaptive_full"
$RunsCount = 3

$DateSuffix = Get-Date -Format "yyyy-MM-dd"
$ResultRoot = "$ProjectDir\benchmark-raw-results\additional-tests\stress-test\adaptive_full_rerun_$DateSuffix"
$ServerStrategyUrl = "$ServerBaseUrl/actuator/alb/strategy"
$GatewayResetUrl = "$ServerBaseUrl/actuator/alb/reset"

# Function to write colorful messages
function Write-Log {
    param([string]$Message, [string]$Level="INFO")
    $Color = "White"
    if ($Level -eq "ERROR") { $Color = "Red" }
    elseif ($Level -eq "WARN") { $Color = "Yellow" }
    elseif ($Level -eq "SUCCESS") { $Color = "Green" }
    Write-Host "[$Level] $Message" -ForegroundColor $Color
}

Write-Log "============================================================="
Write-Log "Adaptive Stress Test Rerun (3 Runs)"
Write-Log "JMX Test Plan: $TestPlan"
Write-Log "Output Directory: $ResultRoot"
Write-Log "Parameters: WaitAfterReset=$WaitAfterReset s, WaitBetweenRuns=$WaitBetweenRuns s"
Write-Log "============================================================="

# 1. Verification checks
$JMeterExe = "$JMeterHome\bin\jmeter.bat"
if (-not (Test-Path $JMeterExe)) {
    Write-Log "JMeter not found at $JMeterExe" "ERROR"
    exit 1
}

$ResolvedTestPlan = $TestPlan
if (-not ([System.IO.Path]::IsPathRooted($TestPlan))) {
    $ResolvedTestPlan = "$ScriptDir\$TestPlan"
}
if (-not (Test-Path $ResolvedTestPlan)) {
    Write-Log "Test plan not found at $ResolvedTestPlan" "ERROR"
    exit 1
}

# Ensure Output Directory exists
if (-not (Test-Path $ResultRoot)) {
    New-Item -ItemType Directory -Path $ResultRoot | Out-Null
}

# Helper Function: Verify Server Strategy
function Verify-Server {
    param([int]$Retries = $VerifyRetries)
    Write-Log "Verifying ALB Server Strategy at $ServerStrategyUrl"
    for ($i = 1; $i -le $Retries; $i++) {
        Write-Log "Verify attempt $i / $Retries..."
        try {
            $response = Invoke-RestMethod -Uri $ServerStrategyUrl -TimeoutSec 10 -ErrorAction Stop
            Write-Log "[SERVER] strategy=$($response.strategy); ablationVariant=$($response.ablationVariant)"
            
            if (($response.strategy -ieq $ExpectedStrategy) -and ($response.ablationVariant -ieq $ExpectedAblation)) {
                Write-Log "Server verified successfully!" "SUCCESS"
                return $true
            } else {
                Write-Log "Server returned strategy=$($response.strategy), ablation=$($response.ablationVariant). Expected: $ExpectedStrategy/$ExpectedAblation" "WARN"
            }
        } catch {
            Write-Log "SERVER UNAVAILABLE: $($_.Exception.Message)" "WARN"
        }
        Start-Sleep -Seconds $VerifyInterval
    }
    return $false
}

# Helper Function: Reset System State
function Reset-SystemState {
    Write-Log "Resetting ALB and Chaos state before run..."
    
    # Gateway Reset
    try {
        Invoke-RestMethod -Method Post -Uri $GatewayResetUrl -TimeoutSec 15 -ErrorAction Stop | Out-Null
        Write-Log "Gateway reset OK."
    } catch {
        Write-Log "Gateway reset failed: $($_.Exception.Message)" "WARN"
    }

    # Backend Chaos Reset
    $ports = $BackendPorts.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
    foreach ($port in $ports) {
        try {
            $chaosUrl = "${BackendBaseUrl}:${port}/api/chaos/reset"
            Invoke-RestMethod -Method Post -Uri $chaosUrl -TimeoutSec 15 -ErrorAction Stop | Out-Null
            Write-Log "Backend $port reset OK."
        } catch {
            Write-Log "Backend $port reset failed: $($_.Exception.Message)" "WARN"
        }
    }
    Write-Log "Reset completed." "SUCCESS"
}

# Helper Function: Write Metadata
function Write-Metadata {
    param([string]$FilePath, [int]$RunNumber)
    
    $GitCommit = "Unknown"
    try {
        $GitCommit = (git -C $ProjectDir rev-parse HEAD 2>$null).Trim()
    } catch {}

    $Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    
    $MetaContent = @"
scenario=04_stress_overload_graceful_degradation_mixed_1200_tst
strategy=$ExpectedStrategy
ablation=$ExpectedAblation
run=$RunNumber
git_commit=$GitCommit
test_plan=$ResolvedTestPlan
server_base_url=$ServerBaseUrl
created_at=$Timestamp
wait_after_reset=$WaitAfterReset
wait_between_runs=$WaitBetweenRuns
"@
    Set-Content -Path $FilePath -Value $MetaContent
}

# 2. Main Loop for 3 Runs
for ($Run = 1; $Run -le $RunsCount; $Run++) {
    Write-Log "`n-------------------------------------------------------------"
    Write-Log "Running $StrategyLabel ($Run / $RunsCount)"
    Write-Log "-------------------------------------------------------------"

    # A. Verify Server state BEFORE resetting/running
    $isVerified = Verify-Server -Retries 1
    if (-not $isVerified) {
        Write-Log "Server is NOT running expected strategy. Initiating auto-deploy..." "WARN"
        
        $UpdateConfigScript = "$ScriptDir\update_alb_config.ps1"
        $AppYml = "$ProjectDir\api-gateway-alb\src\main\resources\application.yml"
        $DeployMarker = "$ProjectDir\api-gateway-alb\.alb-strategy-deploy-marker.txt"
        
        Write-Log "Updating application.yml..."
        & $UpdateConfigScript -ApplicationYml $AppYml -DeployMarker $DeployMarker -Strategy $ExpectedStrategy -Ablation $ExpectedAblation
        
        Write-Log "Committing and pushing to trigger CI/CD..."
        git -C $ProjectDir add $AppYml $DeployMarker
        git -C $ProjectDir commit -m "Auto-deploy benchmark strategy $ExpectedStrategy ablation $ExpectedAblation"
        git -C $ProjectDir push origin HEAD
        
        Write-Log "Waiting 120 seconds for CI/CD deploy..."
        Start-Sleep -Seconds 120
        
        Write-Log "Re-verifying server state..."
        $isVerified = Verify-Server
        if (-not $isVerified) {
            Write-Log "Auto-deploy completed but server verification STILL FAILED." "ERROR"
            Write-Log "RUN FAILED. Halting process." "ERROR"
            exit 1
        }
    }

    # B. Reset System State
    Reset-SystemState

    # C. Wait After Reset
    Write-Log "Waiting $WaitAfterReset seconds after reset to stabilize..."
    Start-Sleep -Seconds $WaitAfterReset

    # D. Prepare Run Directories
    $RunDir = "$ResultRoot\$StrategyLabel-$Run"
    if (Test-Path $RunDir) {
        Write-Log "Removing existing run directory: $RunDir"
        Remove-Item -Recurse -Force $RunDir
    }
    
    $JtlFile = "$ResultRoot\$StrategyLabel-$Run.jtl"
    if (Test-Path $JtlFile) {
        Remove-Item -Force $JtlFile
    }

    # E. Write Metadata
    $MetaFile = "$ResultRoot\$StrategyLabel-$Run-metadata.txt"
    Write-Metadata -FilePath $MetaFile -RunNumber $Run

    # F. Run JMeter
    Write-Log "Starting JMeter Stress Test for Run $Run..."
    $JMeterArgs = @(
        "-n", 
        "-t", "`"$ResolvedTestPlan`"", 
        "-Jalb.strategy=`"$ExpectedStrategy`"", 
        "-Jalb.ablation=`"$ExpectedAblation`"", 
        "-l", "`"$JtlFile`"", 
        "-e", 
        "-o", "`"$RunDir`""
    )
    
    $JMeterCommand = "& `"$JMeterExe`" " + ($JMeterArgs -join " ")
    Write-Log "Executing: $JMeterCommand"
    
    # Execute JMeter
    $process = Start-Process -FilePath $JMeterExe -ArgumentList ($JMeterArgs -join " ") -Wait -NoNewWindow -PassThru
    
    if ($process.ExitCode -ne 0) {
        Write-Log "JMeter execution failed with exit code $($process.ExitCode)." "ERROR"
        Write-Log "RUN FAILED." "ERROR"
        exit 1
    }

    Write-Log "Run $Run finished successfully." "SUCCESS"

    # G. Wait Between Runs
    if ($Run -lt $RunsCount) {
        Write-Log "Waiting $WaitBetweenRuns seconds before next run to let backend cool down..."
        Start-Sleep -Seconds $WaitBetweenRuns
    }
}

Write-Log "`n============================================================="
Write-Log "All $RunsCount runs of Adaptive Stress Test completed successfully!" "SUCCESS"
Write-Log "Output Directory: $ResultRoot"
Write-Log "============================================================="
