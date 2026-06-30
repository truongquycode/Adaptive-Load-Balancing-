@echo off
setlocal EnableExtensions
set "NO_PAUSE=true"

echo =============================================================
echo Run all main ALB benchmark scenarios with strict verification
echo =============================================================

call "%~dp01-run_low_all_strategies.bat"
if errorlevel 1 goto FAILED

call "%~dp02-run_medium_chaos_all_strategies.bat"
if errorlevel 1 goto FAILED

call "%~dp03-run_high_all_strategies.bat"
if errorlevel 1 goto FAILED

call "%~dp04-run_stress-test_all_strategies.bat"
if errorlevel 1 goto FAILED

echo.
echo =============================================================
echo Main benchmark scenarios completed successfully.
echo To run Adaptive ablation separately, execute:
echo   scripts_run_jmeter\5-run_adaptive_ablation_medium.bat
echo =============================================================
pause
exit /b 0

:FAILED
echo.
echo =============================================================
echo Benchmark stopped because one scenario failed.
echo =============================================================
pause
exit /b 1
