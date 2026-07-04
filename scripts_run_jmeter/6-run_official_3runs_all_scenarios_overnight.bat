@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================
REM Official overnight benchmark runner
REM
REM Goal:
REM   - Run 3 repeats for each load-balancing strategy.
REM   - Run all main load levels: Low, Medium, High, Stress.
REM   - Store raw outputs under the project directory.
REM   - Keep waits short enough for overnight execution, but long enough
REM     to avoid obvious cross-run contamination from backlog/recovery.
REM
REM Run from project root:
REM   scripts_run_jmeter\6-run_official_3runs_all_scenarios_overnight.bat
REM =============================================================

for %%I in ("%~dp0..") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

set "RUNS_PER_ITEM=3"
set "RANDOMIZE_ORDER=true"
set "STRICT_SERVER_STRATEGY_CHECK=true"
set "NO_PAUSE=true"

REM Put raw benchmark output inside the project, but keep it ignored by Git.
set "RESULT_ROOT=%PROJECT_DIR%\benchmark-raw-results\official-runs-3x"
if not exist "%RESULT_ROOT%" mkdir "%RESULT_ROOT%"

REM CI/CD strategy deploy timing.
REM WAIT_AFTER_PUSH is intentionally moderate; VERIFY_RETRIES continues polling
REM until the server reports the expected strategy/ablation variant.
set "WAIT_AFTER_PUSH=60"
set "VERIFY_RETRIES=30"
set "VERIFY_INTERVAL=6"

REM Reset wait after ALB/chaos reset before JMeter starts.
set "WAIT_AFTER_RESET=15"

set "RUN_LOG=%RESULT_ROOT%\overnight-official-3runs-%DATE:/=-%_%TIME::=-%.log"
set "RUN_LOG=%RUN_LOG: =0%"

(
    echo =============================================================
    echo Official 3-run ALB benchmark started
    echo started_at=%DATE% %TIME%
    echo project_dir=%PROJECT_DIR%
    echo result_root=%RESULT_ROOT%
    echo runs_per_item=%RUNS_PER_ITEM%
    echo randomized_order=%RANDOMIZE_ORDER%
    echo wait_after_push=%WAIT_AFTER_PUSH%
    echo verify_retries=%VERIFY_RETRIES%
    echo verify_interval=%VERIFY_INTERVAL%
    echo =============================================================
) > "%RUN_LOG%"

echo =============================================================
echo Official 3-run ALB benchmark started
echo Results: %RESULT_ROOT%
echo Log    : %RUN_LOG%
echo =============================================================

echo.
echo [1/4] LOW load official run
set "WAIT_BETWEEN_RUNS=45"
call "%~dp01-run_low_all_strategies.bat" >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto FAILED

echo.
echo [2/4] MEDIUM dependency slowdown official run
set "WAIT_BETWEEN_RUNS=90"
call "%~dp02-run_medium_chaos_all_strategies.bat" >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto FAILED

echo.
echo [3/4] HIGH dependency slowdown official run
set "WAIT_BETWEEN_RUNS=150"
call "%~dp03-run_high_all_strategies.bat" >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto FAILED

echo.
echo [4/4] STRESS/recovery official run
set "WAIT_BETWEEN_RUNS=180"
call "%~dp04-run_stress-test_all_strategies.bat" >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto FAILED

(
    echo =============================================================
    echo Official 3-run ALB benchmark completed successfully
    echo finished_at=%DATE% %TIME%
    echo result_root=%RESULT_ROOT%
    echo Next step:
    echo   scripts_run_jmeter\7-summarize_official_3runs_all_scenarios.bat
    echo =============================================================
) >> "%RUN_LOG%"

echo.
echo =============================================================
echo Official 3-run ALB benchmark completed successfully.
echo Raw results: %RESULT_ROOT%
echo Log        : %RUN_LOG%
echo.
echo Next morning, run:
echo scripts_run_jmeter\7-summarize_official_3runs_all_scenarios.bat
echo =============================================================
exit /b 0

:FAILED
echo.
echo =============================================================
echo Benchmark stopped because one scenario failed.
echo Check log: %RUN_LOG%
echo =============================================================
(
    echo =============================================================
    echo Benchmark stopped because one scenario failed
    echo failed_at=%DATE% %TIME%
    echo =============================================================
) >> "%RUN_LOG%"
exit /b 1
