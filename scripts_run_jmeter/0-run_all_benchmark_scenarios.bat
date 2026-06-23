@echo off
setlocal EnableExtensions
set "NO_PAUSE=true"

echo =============================================================
echo Run all ALB benchmark scenarios
echo =============================================================

 call "D:\eclipse-workspace\adaptive-load-balancer-parent\scripts_run_jmeter\1-run_low_all_strategies.bat"
 if errorlevel 1 goto FAILED

 call "D:\eclipse-workspace\adaptive-load-balancer-parent\scripts_run_jmeter\2-run_medium_chaos_all_strategies.bat"
 if errorlevel 1 goto FAILED

 call "D:\eclipse-workspace\adaptive-load-balancer-parent\scripts_run_jmeter\3-run_high_all_strategies.bat"
 if errorlevel 1 goto FAILED

 call "D:\eclipse-workspace\adaptive-load-balancer-parent\scripts_run_jmeter\4-run_stress-test_all_strategies.bat"
 if errorlevel 1 goto FAILED

echo.
echo =============================================================
echo All benchmark scenarios completed successfully.
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