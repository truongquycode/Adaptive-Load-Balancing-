@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================
REM  Common ALB benchmark runner — strict and reproducible version
REM
REM  Features:
REM    1) Strict server verification through GET /actuator/alb/strategy.
REM    2) Strategy order is randomized by default to reduce run-order bias.
REM    3) ALB state and chaos state are reset before every JMeter run.
REM    4) A metadata file is written for each run so results cannot be mislabeled.
REM    5) Supports Adaptive ablation variants through alb.ablation.variant.
REM =============================================================

set "SCENARIO_NAME=%~1"
set "TEST_PLAN_ARG=%~2"
set "RESULT_SUBDIR=%~3"
set "MODE=%~4"
if not defined MODE set "MODE=strategies"

if not defined JMETER_HOME set "JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3"
if not defined RESULT_ROOT set "RESULT_ROOT=%JMETER_HOME%\bin\ALB_Test\results"
if not defined GIT_BRANCH set "GIT_BRANCH=main"
if not defined SERVER_BASE_URL set "SERVER_BASE_URL=http://172.30.35.37:8080"
if not defined BACKEND_BASE_URL set "BACKEND_BASE_URL=http://172.30.35.37"
if not defined BACKEND_PORTS set "BACKEND_PORTS=8081 8082 8083"
if not defined RUNS_PER_ITEM set "RUNS_PER_ITEM=5"
if not defined WAIT_AFTER_PUSH set "WAIT_AFTER_PUSH=120"
if not defined WAIT_BETWEEN_RUNS set "WAIT_BETWEEN_RUNS=180"
if not defined WAIT_AFTER_RESET set "WAIT_AFTER_RESET=20"
if not defined VERIFY_RETRIES set "VERIFY_RETRIES=18"
if not defined VERIFY_INTERVAL set "VERIFY_INTERVAL=10"
if not defined RANDOMIZE_ORDER set "RANDOMIZE_ORDER=true"
if not defined STRICT_SERVER_STRATEGY_CHECK set "STRICT_SERVER_STRATEGY_CHECK=true"

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

for %%I in ("%~dp0..") do set "PROJECT_DIR=%%~fI"
set "APP_YML=%PROJECT_DIR%\api-gateway-alb\src\main\resources\application.yml"
set "DEPLOY_MARKER=%PROJECT_DIR%\api-gateway-alb\.alb-strategy-deploy-marker.txt"
set "SERVER_STRATEGY_URL=%SERVER_BASE_URL%/actuator/alb/strategy"
set "GATEWAY_RESET_URL=%SERVER_BASE_URL%/actuator/alb/reset"

if exist "%TEST_PLAN_ARG%" (
    set "TEST_PLAN=%TEST_PLAN_ARG%"
) else (
    set "TEST_PLAN=%PROJECT_DIR%\%TEST_PLAN_ARG%"
)
set "RESULT_BASE=%RESULT_ROOT%\%RESULT_SUBDIR%"

if not exist "%JMETER_HOME%\bin\jmeter.bat" (
    echo [ERROR] JMeter not found: %JMETER_HOME%\bin\jmeter.bat
    exit /b 1
)
if not exist "%TEST_PLAN%" (
    echo [ERROR] Test plan not found: %TEST_PLAN%
    exit /b 1
)
if not exist "%APP_YML%" (
    echo [ERROR] application.yml not found: %APP_YML%
    exit /b 1
)
if not exist "%POWERSHELL_EXE%" (
    echo [ERROR] PowerShell not found: %POWERSHELL_EXE%
    exit /b 1
)

call :CONFIGURE_ITEMS
if errorlevel 1 exit /b 1

call :BUILD_RUN_ORDER
if errorlevel 1 exit /b 1

echo.
echo =============================================================
echo Scenario : %SCENARIO_NAME%
echo Mode     : %MODE%
echo JMX      : %TEST_PLAN%
echo Results  : %RESULT_BASE%
echo Order    : !ORDER!
echo Strict   : %STRICT_SERVER_STRATEGY_CHECK%
echo =============================================================

if not exist "%RESULT_BASE%" mkdir "%RESULT_BASE%"
call :WRITE_SCENARIO_METADATA

for %%I in (!ORDER!) do (
    call :RUN_ITEM %%I
    if errorlevel 1 goto FAILED
)

echo.
echo =============================================================
echo Scenario completed successfully: %SCENARIO_NAME%
echo Results saved in: %RESULT_BASE%
echo =============================================================
exit /b 0

:CONFIGURE_ITEMS
if /I "%MODE%"=="strategies" (
    set "ITEM_COUNT=4"
    set "ITEM_1=round-robin,round_robin,full"
    set "ITEM_2=random,random,full"
    set "ITEM_3=least-connections,least_connect,full"
    set "ITEM_4=adaptive,adaptive_full,full"
    exit /b 0
)

if /I "%MODE%"=="ablation" (
    set "ITEM_COUNT=9"
    set "ITEM_1=adaptive,adaptive_full,full"
    set "ITEM_2=adaptive,adaptive_no_pid,no-pid"
    set "ITEM_3=adaptive,adaptive_fixed_weights,fixed-weights"
    set "ITEM_4=adaptive,adaptive_no_ewma_latency,no-ewma-latency"
    set "ITEM_5=adaptive,adaptive_no_score_ema,no-score-ema"
    set "ITEM_6=adaptive,adaptive_no_capacity,no-capacity"
    set "ITEM_7=adaptive,adaptive_no_p2c,no-p2c"
    set "ITEM_8=adaptive,adaptive_no_probe,no-probe"
    set "ITEM_9=adaptive,adaptive_no_low_load_rr,no-low-load-rr"
    exit /b 0
)

echo [ERROR] Unknown benchmark mode: %MODE%
exit /b 1

:BUILD_RUN_ORDER
set "ORDER="

REM Build run order with pure CMD logic.
REM This avoids fragile PowerShell multiline escaping on Windows CMD.
for /L %%I in (1,1,%ITEM_COUNT%) do set "IDX_%%I=%%I"

if /I "%RANDOMIZE_ORDER%"=="true" (
    for /L %%I in (%ITEM_COUNT%,-1,2) do (
        set /a "J=(!RANDOM! %% %%I) + 1"
        for %%J in (!J!) do (
            set "TMP=!IDX_%%I!"
            set "IDX_%%I=!IDX_%%J!"
            set "IDX_%%J=!TMP!"
        )
    )
)

for /L %%I in (1,1,%ITEM_COUNT%) do set "ORDER=!ORDER! !IDX_%%I!"

if not defined ORDER (
    echo [ERROR] Run order is empty.
    exit /b 1
)

exit /b 0

:RUN_ITEM
set "ITEM_INDEX=%~1"
set "ITEM_VALUE=!ITEM_%ITEM_INDEX%!"
for /f "tokens=1,2,3 delims=," %%A in ("!ITEM_VALUE!") do (
    set "STRATEGY=%%A"
    set "LABEL=%%B"
    set "ABLATION_VARIANT=%%C"
)

if not defined STRATEGY (
    echo [ERROR] Cannot parse item index %ITEM_INDEX%: !ITEM_VALUE!
    exit /b 1
)

echo.
echo =============================================================
echo Benchmark item %ITEM_INDEX%: strategy=!STRATEGY!, ablation=!ABLATION_VARIANT!, label=!LABEL!
echo =============================================================

call :SET_DEPLOY_AND_VERIFY "!STRATEGY!" "!ABLATION_VARIANT!" "!LABEL!"
if errorlevel 1 exit /b 1

call :RUN_JMETER_ITEM "!STRATEGY!" "!ABLATION_VARIANT!" "!LABEL!"
if errorlevel 1 exit /b 1

exit /b 0

:SET_DEPLOY_AND_VERIFY
set "TARGET_STRATEGY=%~1"
set "TARGET_ABLATION=%~2"
set "TARGET_LABEL=%~3"

echo [INFO] Deploying strategy=%TARGET_STRATEGY%, ablation=%TARGET_ABLATION%

cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

git rebase --abort >nul 2>nul
git merge --abort >nul 2>nul

echo [INFO] Pull latest code from GitHub using --autostash...
git pull --rebase --autostash origin %GIT_BRANCH%
if errorlevel 1 (
    echo [ERROR] git pull failed. Fix repository state before benchmark.
    exit /b 1
)

call :UPDATE_APPLICATION_YML "%TARGET_STRATEGY%" "%TARGET_ABLATION%"
if errorlevel 1 exit /b 1

"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$p='%DEPLOY_MARKER%'; $enc=New-Object System.Text.UTF8Encoding($false); $content=@('strategy=%TARGET_STRATEGY%','ablation=%TARGET_ABLATION%','label=%TARGET_LABEL%','timestamp='+(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')); [System.IO.File]::WriteAllLines($p,$content,$enc)"
if errorlevel 1 (
    echo [ERROR] Failed to update deploy marker.
    exit /b 1
)

git add "%APP_YML%" "%DEPLOY_MARKER%"
git diff --cached --quiet
if not errorlevel 1 (
    echo [ERROR] No staged changes. Deploy marker should have changed.
    exit /b 1
)

git commit -m "Benchmark deploy strategy %TARGET_STRATEGY% ablation %TARGET_ABLATION%"
if errorlevel 1 exit /b 1

git push origin HEAD:%GIT_BRANCH%
if errorlevel 1 exit /b 1

echo [INFO] Waiting %WAIT_AFTER_PUSH% seconds for CI/CD deploy...
timeout /t %WAIT_AFTER_PUSH% /nobreak

call :VERIFY_SERVER "%TARGET_STRATEGY%" "%TARGET_ABLATION%"
if errorlevel 1 exit /b 1

exit /b 0

:UPDATE_APPLICATION_YML
set "NEW_STRATEGY=%~1"
set "NEW_ABLATION=%~2"
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
"$p='%APP_YML%'; $strategy='%NEW_STRATEGY%'; $ablation='%NEW_ABLATION%'; $enc=New-Object System.Text.UTF8Encoding($false); $lines=[System.IO.File]::ReadAllLines($p,$enc); $insideAlb=$false; $insideAblation=$false; $strategyDone=$false; $ablationDone=$false; for($i=0; $i -lt $lines.Length; $i++){ if($lines[$i] -match '^alb:\s*$'){ $insideAlb=$true; $insideAblation=$false; continue }; if($insideAlb -and $lines[$i] -match '^\S'){ $insideAlb=$false; $insideAblation=$false }; if($insideAlb -and $lines[$i] -match '^\s*strategy:\s*'){ $lines[$i]='    strategy: '+$strategy; $strategyDone=$true; continue }; if($insideAlb -and $lines[$i] -match '^\s*ablation:\s*$'){ $insideAblation=$true; continue }; if($insideAblation -and $lines[$i] -match '^\s*variant:\s*'){ $lines[$i]='        variant: '+$ablation; $ablationDone=$true; continue }; if($insideAblation -and $lines[$i] -match '^    \S' -and $lines[$i] -notmatch '^\s*variant:\s*'){ $insideAblation=$false } }; if(-not $strategyDone){ Write-Error 'Cannot find alb.strategy'; exit 2 }; if(-not $ablationDone){ Write-Error 'Cannot find alb.ablation.variant'; exit 3 }; [System.IO.File]::WriteAllLines($p,$lines,$enc)"
if errorlevel 1 (
    echo [ERROR] Failed to update strategy or ablation variant in application.yml.
    exit /b 1
)
exit /b 0

:VERIFY_SERVER
set "EXPECTED_STRATEGY=%~1"
set "EXPECTED_ABLATION=%~2"

echo [INFO] Strict verification: %SERVER_STRATEGY_URL%
for /L %%R in (1,1,%VERIFY_RETRIES%) do (
    echo [INFO] Verify attempt %%R/%VERIFY_RETRIES%...
    "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { $r=Invoke-RestMethod -Uri '%SERVER_STRATEGY_URL%' -TimeoutSec 10; Write-Host ('[SERVER] strategy={0}; ablationVariant={1}; timestamp={2}' -f $r.strategy,$r.ablationVariant,$r.timestamp); if(($r.strategy -ieq '%EXPECTED_STRATEGY%') -and ($r.ablationVariant -ieq '%EXPECTED_ABLATION%')) { exit 0 } else { exit 3 } } catch { Write-Host ('[SERVER] UNAVAILABLE: '+$_.Exception.Message); exit 2 }"
    if not errorlevel 1 (
        echo [INFO] Server verified: strategy=%EXPECTED_STRATEGY%, ablation=%EXPECTED_ABLATION%
        exit /b 0
    )
    timeout /t %VERIFY_INTERVAL% /nobreak >nul
)

echo [ERROR] Server strategy/ablation verification failed after %VERIFY_RETRIES% attempts.
echo [ERROR] Expected strategy=%EXPECTED_STRATEGY%, ablation=%EXPECTED_ABLATION%.
if /I "%STRICT_SERVER_STRATEGY_CHECK%"=="true" exit /b 1

echo [WARN] STRICT_SERVER_STRATEGY_CHECK=false, continuing despite verification failure.
exit /b 0

:RUN_JMETER_ITEM
set "RUN_STRATEGY=%~1"
set "RUN_ABLATION=%~2"
set "RUN_LABEL=%~3"
set "RESULT_DIR=%RESULT_BASE%\%RUN_LABEL%"
if not exist "%RESULT_DIR%" mkdir "%RESULT_DIR%"

for /L %%N in (1,1,%RUNS_PER_ITEM%) do (
    echo.
    echo -------------------------------------------------------------
    echo Running %RUN_LABEL% %%N / %RUNS_PER_ITEM%
    echo -------------------------------------------------------------

    call :VERIFY_SERVER "%RUN_STRATEGY%" "%RUN_ABLATION%"
    if errorlevel 1 exit /b 1

    call :RESET_SYSTEM_STATE
    if errorlevel 1 exit /b 1

    echo [INFO] Waiting %WAIT_AFTER_RESET% seconds after reset...
    timeout /t %WAIT_AFTER_RESET% /nobreak

    if exist "%RESULT_DIR%\%RUN_LABEL%-%%N.jtl" del /f /q "%RESULT_DIR%\%RUN_LABEL%-%%N.jtl"
    if exist "%RESULT_DIR%\%RUN_LABEL%-%%N" rmdir /s /q "%RESULT_DIR%\%RUN_LABEL%-%%N"

    call :WRITE_RUN_METADATA "%RESULT_DIR%\%RUN_LABEL%-%%N-metadata.txt" "%RUN_STRATEGY%" "%RUN_ABLATION%" "%%N"

    call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%TEST_PLAN%" ^
        -Jalb.strategy="%RUN_STRATEGY%" ^
        -Jalb.ablation="%RUN_ABLATION%" ^
        -l "%RESULT_DIR%\%RUN_LABEL%-%%N.jtl" ^
        -e -o "%RESULT_DIR%\%RUN_LABEL%-%%N"

    if errorlevel 1 (
        echo [ERROR] JMeter failed for %RUN_LABEL% run %%N.
        exit /b 1
    )

    echo [INFO] %RUN_LABEL% run %%N finished.

    if not "%%N"=="%RUNS_PER_ITEM%" (
        echo [INFO] Waiting %WAIT_BETWEEN_RUNS% seconds before next run...
        timeout /t %WAIT_BETWEEN_RUNS% /nobreak
    )
)
exit /b 0

:RESET_SYSTEM_STATE
echo [INFO] Reset ALB and chaos state before run...
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
"$ErrorActionPreference='Stop'; Invoke-RestMethod -Method Post -Uri '%GATEWAY_RESET_URL%' -TimeoutSec 20 ^| Out-Null; foreach($p in '%BACKEND_PORTS%'.Split(' ')){ if($p.Trim().Length -gt 0){ Invoke-RestMethod -Method Post -Uri ('%BACKEND_BASE_URL%:'+ $p + '/api/chaos/reset') -TimeoutSec 20 ^| Out-Null } }; Write-Host '[INFO] Reset completed.'"
if errorlevel 1 (
    echo [ERROR] Reset ALB/chaos failed. Benchmark stopped to avoid contaminated results.
    exit /b 1
)
exit /b 0

:WRITE_SCENARIO_METADATA
set "SCENARIO_META=%RESULT_BASE%\scenario-metadata.txt"
(
    echo scenario=%SCENARIO_NAME%
    echo mode=%MODE%
    echo test_plan=%TEST_PLAN%
    echo randomized_order=%RANDOMIZE_ORDER%
    echo order=!ORDER!
    echo server_base_url=%SERVER_BASE_URL%
    echo backend_ports=%BACKEND_PORTS%
    echo runs_per_item=%RUNS_PER_ITEM%
    echo created_at=%DATE% %TIME%
) > "%SCENARIO_META%"
exit /b 0

:WRITE_RUN_METADATA
set "META_FILE=%~1"
set "META_STRATEGY=%~2"
set "META_ABLATION=%~3"
set "META_RUN=%~4"
for /f "tokens=*" %%H in ('git -C "%PROJECT_DIR%" rev-parse HEAD 2^>nul') do set "GIT_COMMIT=%%H"
(
    echo scenario=%SCENARIO_NAME%
    echo strategy=%META_STRATEGY%
    echo ablation=%META_ABLATION%
    echo run=%META_RUN%
    echo git_commit=%GIT_COMMIT%
    echo test_plan=%TEST_PLAN%
    echo server_base_url=%SERVER_BASE_URL%
    echo created_at=%DATE% %TIME%
) > "%META_FILE%"
exit /b 0

:FAILED
echo.
echo =============================================================
echo Benchmark stopped because an error occurred.
echo =============================================================
exit /b 1
