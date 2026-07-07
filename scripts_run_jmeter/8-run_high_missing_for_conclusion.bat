@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================
REM High-load missing official runs for final conclusion
REM
REM Purpose:
REM   Complete only the missing runs from the official 3-run high-load
REM   benchmark, without re-running the complete Low/Medium results.
REM
REM Missing based on the current official summary:
REM   - least_connect  : runs 1, 2, 3
REM   - round_robin    : run 3
REM
REM Output folder:
REM   benchmark-raw-results\official-runs-3x\03_high_dependency_slowdown_mixed_0900_staged_tst
REM
REM Run from project root:
REM   scripts_run_jmeter\8-run_high_missing_for_conclusion.bat
REM =============================================================

for %%I in ("%~dp0..") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

if not defined JMETER_HOME set "JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3"
if not defined GIT_BRANCH set "GIT_BRANCH=main"
if not defined SERVER_BASE_URL set "SERVER_BASE_URL=http://172.30.35.37:8080"
if not defined BACKEND_BASE_URL set "BACKEND_BASE_URL=http://172.30.35.37"
if not defined BACKEND_PORTS set "BACKEND_PORTS=8081 8082 8083"

REM Raw result root inside project. This matches the official overnight runner.
set "RESULT_ROOT=%PROJECT_DIR%\benchmark-raw-results\official-runs-3x"
set "SCENARIO_NAME=03_high_dependency_slowdown_mixed_0900_staged_tst"
set "TEST_PLAN=%PROJECT_DIR%\jmeter\03_high_dependency_slowdown_mixed_0900_staged_tst.jmx"
set "RESULT_BASE=%RESULT_ROOT%\%SCENARIO_NAME%"

set "APP_YML=%PROJECT_DIR%\api-gateway-alb\src\main\resources\application.yml"
set "DEPLOY_MARKER=%PROJECT_DIR%\api-gateway-alb\.alb-strategy-deploy-marker.txt"
set "UPDATE_CONFIG_PS1=%~dp0update_alb_config.ps1"
set "RESET_PS1=%~dp0reset_alb_chaos.ps1"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

set "SERVER_STRATEGY_URL=%SERVER_BASE_URL%/actuator/alb/strategy"
set "GATEWAY_RESET_URL=%SERVER_BASE_URL%/actuator/alb/reset"

REM Timing optimized for a small high-load completion run.
REM Keep these waits higher than Low/Medium because High can leave backlog.
if not defined WAIT_AFTER_PUSH set "WAIT_AFTER_PUSH=60"
if not defined VERIFY_RETRIES set "VERIFY_RETRIES=30"
if not defined VERIFY_INTERVAL set "VERIFY_INTERVAL=6"
if not defined WAIT_AFTER_RESET set "WAIT_AFTER_RESET=20"
if not defined WAIT_BETWEEN_RUNS set "WAIT_BETWEEN_RUNS=180"

REM Safety: do not overwrite an existing JTL unless explicitly requested.
if not defined SKIP_IF_JTL_EXISTS set "SKIP_IF_JTL_EXISTS=true"
set "STRICT_SERVER_STRATEGY_CHECK=true"

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
if not exist "%UPDATE_CONFIG_PS1%" (
    echo [ERROR] Config updater not found: %UPDATE_CONFIG_PS1%
    exit /b 1
)
if not exist "%RESET_PS1%" (
    echo [ERROR] Reset helper not found: %RESET_PS1%
    exit /b 1
)
if not exist "%POWERSHELL_EXE%" (
    echo [ERROR] PowerShell not found: %POWERSHELL_EXE%
    exit /b 1
)
if not exist "%RESULT_BASE%" mkdir "%RESULT_BASE%"

set "RUN_LOG=%RESULT_BASE%\high-missing-for-conclusion-%DATE:/=-%_%TIME::=-%.log"
set "RUN_LOG=%RUN_LOG: =0%"
set "RUN_LOG=%RUN_LOG:,=-%"
set "RUN_LOG=%RUN_LOG:.=-%"

(
    echo =============================================================
    echo High-load missing official runs started
    echo started_at=%DATE% %TIME%
    echo project_dir=%PROJECT_DIR%
    echo result_base=%RESULT_BASE%
    echo skip_if_jtl_exists=%SKIP_IF_JTL_EXISTS%
    echo wait_after_push=%WAIT_AFTER_PUSH%
    echo wait_after_reset=%WAIT_AFTER_RESET%
    echo wait_between_runs=%WAIT_BETWEEN_RUNS%
    echo Missing plan:
    echo   least_connect: 1 2 3
    echo   round_robin  : 3
    echo =============================================================
) > "%RUN_LOG%"

echo =============================================================
echo High-load missing official runs
echo Results: %RESULT_BASE%
echo Log    : %RUN_LOG%
echo =============================================================

REM Run Least Connections first because it is needed for the official conclusion.
call :RUN_STRATEGY "least-connections" "least_connect" "full" "1 2 3"
if errorlevel 1 goto FAILED

REM Complete only round_robin-3. Runs 1 and 2 are already present in the current official result set.
call :RUN_STRATEGY "round-robin" "round_robin" "full" "3"
if errorlevel 1 goto FAILED

REM Clean system state after the completion run.
call :RESET_SYSTEM_STATE

(
    echo =============================================================
    echo High-load missing official runs completed successfully
    echo finished_at=%DATE% %TIME%
    echo Next step:
    echo   scripts_run_jmeter\9-summarize_high_after_missing_for_conclusion.bat
    echo =============================================================
) >> "%RUN_LOG%"

echo.
echo =============================================================
echo High-load missing official runs completed successfully.
echo Raw results: %RESULT_BASE%
echo.
echo Next step:
echo scripts_run_jmeter\9-summarize_high_after_missing_for_conclusion.bat
echo =============================================================
exit /b 0

:RUN_STRATEGY
set "RUN_STRATEGY=%~1"
set "RUN_LABEL=%~2"
set "RUN_ABLATION=%~3"
set "RUN_LIST=%~4"
set "RESULT_DIR=%RESULT_BASE%\%RUN_LABEL%"
if not exist "%RESULT_DIR%" mkdir "%RESULT_DIR%"

echo.
echo =============================================================
echo Strategy: %RUN_STRATEGY%  Label: %RUN_LABEL%  Runs: %RUN_LIST%
echo =============================================================
(
    echo.
    echo =============================================================
    echo Strategy: %RUN_STRATEGY%  Label: %RUN_LABEL%  Runs: %RUN_LIST%
    echo =============================================================
) >> "%RUN_LOG%"

call :SET_DEPLOY_AND_VERIFY "%RUN_STRATEGY%" "%RUN_ABLATION%" "%RUN_LABEL%"
if errorlevel 1 exit /b 1

for %%N in (%RUN_LIST%) do (
    call :RUN_ONE_JMETER "%%N"
    if errorlevel 1 exit /b 1
)
exit /b 0

:SET_DEPLOY_AND_VERIFY
set "TARGET_STRATEGY=%~1"
set "TARGET_ABLATION=%~2"
set "TARGET_LABEL=%~3"

echo [INFO] Deploying strategy=%TARGET_STRATEGY%, ablation=%TARGET_ABLATION%
(
    echo [INFO] Deploying strategy=%TARGET_STRATEGY%, ablation=%TARGET_ABLATION%
) >> "%RUN_LOG%"

cd /d "%PROJECT_DIR%"
if errorlevel 1 exit /b 1

git rebase --abort >nul 2>nul
git merge --abort >nul 2>nul

echo [INFO] Pull latest code from GitHub using --autostash...
git pull --rebase --autostash origin %GIT_BRANCH% >> "%RUN_LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] git pull failed. Fix repository state before benchmark.
    exit /b 1
)

"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%UPDATE_CONFIG_PS1%" ^
    -ApplicationYml "%APP_YML%" ^
    -DeployMarker "%DEPLOY_MARKER%" ^
    -Strategy "%TARGET_STRATEGY%" ^
    -Ablation "%TARGET_ABLATION%" ^
    -Label "%TARGET_LABEL%" >> "%RUN_LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] Failed to update strategy or ablation variant in application.yml.
    exit /b 1
)

git add "%APP_YML%" "%DEPLOY_MARKER%" >> "%RUN_LOG%" 2>&1
git diff --cached --quiet
if not errorlevel 1 (
    echo [ERROR] No staged changes. Deploy marker should have changed.
    exit /b 1
)

git commit -m "Benchmark deploy strategy %TARGET_STRATEGY% ablation %TARGET_ABLATION%" >> "%RUN_LOG%" 2>&1
if errorlevel 1 exit /b 1

git push origin HEAD:%GIT_BRANCH% >> "%RUN_LOG%" 2>&1
if errorlevel 1 exit /b 1

echo [INFO] Waiting %WAIT_AFTER_PUSH% seconds for CI/CD deploy...
timeout /t %WAIT_AFTER_PUSH% /nobreak >nul

call :VERIFY_SERVER "%TARGET_STRATEGY%" "%TARGET_ABLATION%"
if errorlevel 1 exit /b 1
exit /b 0

:VERIFY_SERVER
set "EXPECTED_STRATEGY=%~1"
set "EXPECTED_ABLATION=%~2"

echo [INFO] Strict verification: %SERVER_STRATEGY_URL%
for /L %%R in (1,1,%VERIFY_RETRIES%) do (
    echo [INFO] Verify attempt %%R/%VERIFY_RETRIES%...
    "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { $r=Invoke-RestMethod -Uri '%SERVER_STRATEGY_URL%' -TimeoutSec 10; Write-Host ('[SERVER] strategy={0}; ablationVariant={1}; timestamp={2}' -f $r.strategy,$r.ablationVariant,$r.timestamp); if(($r.strategy -ieq '%EXPECTED_STRATEGY%') -and ($r.ablationVariant -ieq '%EXPECTED_ABLATION%')) { exit 0 } else { exit 3 } } catch { Write-Host ('[SERVER] UNAVAILABLE: '+$_.Exception.Message); exit 2 }" >> "%RUN_LOG%" 2>&1
    if not errorlevel 1 (
        echo [INFO] Server verified: strategy=%EXPECTED_STRATEGY%, ablation=%EXPECTED_ABLATION%
        exit /b 0
    )
    timeout /t %VERIFY_INTERVAL% /nobreak >nul
)

echo [ERROR] Server strategy/ablation verification failed after %VERIFY_RETRIES% attempts.
echo [ERROR] Expected strategy=%EXPECTED_STRATEGY%, ablation=%EXPECTED_ABLATION%.
exit /b 1

:RUN_ONE_JMETER
set "RUN_NUMBER=%~1"
set "JTL_FILE=%RESULT_DIR%\%RUN_LABEL%-%RUN_NUMBER%.jtl"
set "HTML_DIR=%RESULT_DIR%\%RUN_LABEL%-%RUN_NUMBER%"
set "META_FILE=%RESULT_DIR%\%RUN_LABEL%-%RUN_NUMBER%-metadata.txt"

if /I "%SKIP_IF_JTL_EXISTS%"=="true" if exist "%JTL_FILE%" (
    echo [INFO] Skip existing result: %JTL_FILE%
    (
        echo [INFO] Skip existing result: %JTL_FILE%
    ) >> "%RUN_LOG%"
    exit /b 0
)

echo.
echo -------------------------------------------------------------
echo Running %RUN_LABEL% %RUN_NUMBER%
echo -------------------------------------------------------------
(
    echo.
    echo -------------------------------------------------------------
    echo Running %RUN_LABEL% %RUN_NUMBER%
    echo -------------------------------------------------------------
) >> "%RUN_LOG%"

call :VERIFY_SERVER "%RUN_STRATEGY%" "%RUN_ABLATION%"
if errorlevel 1 exit /b 1

call :RESET_SYSTEM_STATE
if errorlevel 1 exit /b 1

echo [INFO] Waiting %WAIT_AFTER_RESET% seconds after reset...
timeout /t %WAIT_AFTER_RESET% /nobreak >nul

if exist "%JTL_FILE%" del /f /q "%JTL_FILE%"
if exist "%HTML_DIR%" rmdir /s /q "%HTML_DIR%"

call :WRITE_RUN_METADATA "%META_FILE%" "%RUN_STRATEGY%" "%RUN_ABLATION%" "%RUN_NUMBER%"

call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%TEST_PLAN%" ^
    -Jalb.strategy="%RUN_STRATEGY%" ^
    -Jalb.ablation="%RUN_ABLATION%" ^
    -l "%JTL_FILE%" ^
    -e -o "%HTML_DIR%" >> "%RUN_LOG%" 2>&1

if errorlevel 1 (
    echo [ERROR] JMeter failed for %RUN_LABEL% run %RUN_NUMBER%.
    exit /b 1
)

echo [INFO] %RUN_LABEL% run %RUN_NUMBER% finished.

echo [INFO] Waiting %WAIT_BETWEEN_RUNS% seconds before the next missing run...
timeout /t %WAIT_BETWEEN_RUNS% /nobreak >nul
exit /b 0

:RESET_SYSTEM_STATE
echo [INFO] Best-effort reset ALB and chaos state...
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%RESET_PS1%" ^
    -GatewayResetUrl "%GATEWAY_RESET_URL%" ^
    -BackendBaseUrl "%BACKEND_BASE_URL%" ^
    -BackendPorts "%BACKEND_PORTS%" ^
    -TimeoutSec 8 ^
    -Retries 1 ^
    -RetryDelaySec 2 >> "%RUN_LOG%" 2>&1
if errorlevel 1 (
    echo [WARN] Pre-run reset helper returned a warning. Continuing because the JMeter test plan also resets gateway and backend chaos in Setup Thread Group.
    echo [WARN] Check log if the same backend keeps failing: %RUN_LOG%
)
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
    echo note=completion-run-for-official-high-load-conclusion
) > "%META_FILE%"
exit /b 0

:FAILED
echo.
echo =============================================================
echo High-load missing completion run failed.
echo Check log: %RUN_LOG%
echo =============================================================
(
    echo =============================================================
    echo High-load missing completion run failed
    echo failed_at=%DATE% %TIME%
    echo =============================================================
) >> "%RUN_LOG%"
exit /b 1
