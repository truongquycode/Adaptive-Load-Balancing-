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

$RunsCount = 3
$ResultRoot = "$ProjectDir\benchmark-raw-results\additional-tests\stress-test-rerun"
$ServerStrategyUrl = "$ServerBaseUrl/actuator/alb/strategy"
$GatewayResetUrl = "$ServerBaseUrl/actuator/alb/reset"
$UpdateConfigScript = "$ScriptDir\update_alb_config.ps1"
$AppYml = "$ProjectDir\api-gateway-alb\src\main\resources\application.yml"
$DeployMarker = "$ProjectDir\api-gateway-alb\.alb-strategy-deploy-marker.txt"

# Chỉ định duy nhất thuật toán round-robin
$Algorithms = @("round-robin")

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
Write-Log "Stress Test Rerun - Round Robin Only (3 Runs)"
Write-Log "Output Directory: $ResultRoot"
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

# Helper Function: Verify Server Strategy
function Verify-Server {
    param([string]$ExpectedStrategy, [string]$ExpectedAblation, [int]$Retries)
    Write-Log "Verifying ALB Server Strategy at $ServerStrategyUrl"
    for ($i = 1; $i -le $Retries; $i++) {
        Write-Log "Verify attempt $i / $Retries..."
        try {
            $response = Invoke-RestMethod -Uri $ServerStrategyUrl -TimeoutSec 10 -ErrorAction Stop
            Write-Log "[SERVER] strategy=$($response.strategy); ablationVariant=$($response.ablationVariant)"
            
            if (($response.strategy -ieq $ExpectedStrategy) -and ($response.ablationVariant -ieq $ExpectedAblation)) {
                Write-Log "Server verified successfully for $ExpectedStrategy / $ExpectedAblation!" "SUCCESS"
                return $true
            } else {
                Write-Log "Expected: $ExpectedStrategy/$ExpectedAblation" "WARN"
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
    try {
        Invoke-RestMethod -Method Post -Uri $GatewayResetUrl -TimeoutSec 15 -ErrorAction Stop | Out-Null
        Write-Log "Gateway reset OK."
    } catch {
        Write-Log "Gateway reset failed: $($_.Exception.Message)" "WARN"
    }

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
    param([string]$FilePath, [string]$ExpectedStrategy, [string]$ExpectedAblation, [int]$RunNumber)
    $GitCommit = "Unknown"
    try { $GitCommit = (git -C $ProjectDir rev-parse HEAD 2>$null).Trim() } catch {}
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

# 2. MAIN LOOP: 3 Strategies
foreach ($alg in $Algorithms) {
    $ApiStrat = $alg
    $ApiAbla = "full"
    $Label = $alg -replace "-", "_"
    
    Write-Log "`n============================================================="
    Write-Log "STARTING STRATEGY: $Label"
    Write-Log "============================================================="
    
    # 2.1 PRE-FLIGHT CHECK & DEPLOY
    $isVerified = Verify-Server -ExpectedStrategy $ApiStrat -ExpectedAblation $ApiAbla -Retries 1
    if (-not $isVerified) {
        Write-Log "Server is NOT running $ApiStrat. Initiating auto-deploy..." "WARN"
        Write-Log "Updating application.yml..."
        & $UpdateConfigScript -ApplicationYml $AppYml -DeployMarker $DeployMarker -Strategy $ApiStrat -Ablation $ApiAbla
        
        Write-Log "Committing and pushing to trigger CI/CD..."
        git -C $ProjectDir add $AppYml $DeployMarker
        git -C $ProjectDir commit -m "Auto-deploy benchmark strategy $ApiStrat ablation $ApiAbla"
        git -C $ProjectDir push origin HEAD
        
        Write-Log "Waiting 120 seconds for CI/CD deploy..."
        Start-Sleep -Seconds 120
        
        Write-Log "Re-verifying server state..."
        $isVerified = Verify-Server -ExpectedStrategy $ApiStrat -ExpectedAblation $ApiAbla -Retries $VerifyRetries
        if (-not $isVerified) {
            Write-Log "Auto-deploy completed but server verification STILL FAILED." "ERROR"
            exit 1
        }
    }
    
    # 2.2 CREATE DIRECTORY
    $StratDir = "$ResultRoot\$Label"
    if (-not (Test-Path $StratDir)) {
        New-Item -ItemType Directory -Path $StratDir | Out-Null
    }

    # 2.3 RUN 3 TIMES
    for ($Run = 1; $Run -le $RunsCount; $Run++) {
        Write-Log "`n-------------------------------------------------------------"
        Write-Log "Running $Label (Run $Run / $RunsCount)"
        Write-Log "-------------------------------------------------------------"
        
        # Reset & Wait
        Reset-SystemState
        Write-Log "Waiting $WaitAfterReset seconds after reset to stabilize..."
        Start-Sleep -Seconds $WaitAfterReset
        
        $RunName = "$Label-$Run"
        $RunDir = "$StratDir\$RunName"
        if (Test-Path $RunDir) { Remove-Item -Recurse -Force $RunDir }
        
        $JtlFile = "$StratDir\$RunName.jtl"
        if (Test-Path $JtlFile) { Remove-Item -Force $JtlFile }
        
        $MetaFile = "$StratDir\$RunName-metadata.txt"
        Write-Metadata -FilePath $MetaFile -ExpectedStrategy $ApiStrat -ExpectedAblation $ApiAbla -RunNumber $Run
        
        # Run JMeter
        Write-Log "Starting JMeter Stress Test for $RunName..."
        $JMeterArgs = @("-n", "-t", "`"$ResolvedTestPlan`"", "-Jalb.strategy=`"$ApiStrat`"", "-Jalb.ablation=`"$ApiAbla`"", "-l", "`"$JtlFile`"", "-e", "-o", "`"$RunDir`"")
        $JMeterCommand = "& `"$JMeterExe`" " + ($JMeterArgs -join " ")
        Write-Log "Executing: $JMeterCommand"
        
        $process = Start-Process -FilePath $JMeterExe -ArgumentList ($JMeterArgs -join " ") -Wait -NoNewWindow -PassThru
        if ($process.ExitCode -ne 0) {
            Write-Log "JMeter execution failed with exit code $($process.ExitCode)." "ERROR"
            exit 1
        }
        
        Write-Log "Run $Run finished successfully." "SUCCESS"
        
        # Wait Cooldown (if not last run of the entire suite)
        Write-Log "Waiting $WaitBetweenRuns seconds before next run to let backend cool down..."
        Start-Sleep -Seconds $WaitBetweenRuns
    }
}

Write-Log "`n============================================================="
Write-Log "Strategy completed successfully!" "SUCCESS"
Write-Log "Data saved to: $ResultRoot"
Write-Log "============================================================="
