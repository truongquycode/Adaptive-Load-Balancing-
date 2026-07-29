@echo off
setlocal EnableExtensions

echo =========================================================
echo RUNNING STRESS TEST (1/2)
echo =========================================================
set NO_PAUSE=true
call "%~dp05-run_stress_overload_all_strategies.bat"
if errorlevel 1 (
    echo [ERROR] Stress test failed. Aborting sequence.
    pause
    exit /b 1
)

echo.
echo =========================================================
echo STRESS TEST COMPLETED. WAITING 60s BEFORE SPIKE TEST...
echo =========================================================
timeout /t 60 /nobreak

echo.
echo =========================================================
echo RUNNING SPIKE TEST (2/2)
echo =========================================================
call "%~dp06-run_spike_test_all_strategies.bat"
if errorlevel 1 (
    echo [ERROR] Spike test failed.
    pause
    exit /b 1
)

echo.
echo =========================================================
echo ALL ADDITIONAL TESTS COMPLETED SUCCESSFULLY!
echo =========================================================
pause
exit /b 0
