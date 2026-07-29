@echo off
setlocal

echo =============================================================
echo Starting Adaptive Stress Test (3 Runs) via PowerShell...
echo =============================================================

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_adaptive_stress_test_3runs.ps1"

if errorlevel 1 (
    echo [ERROR] The PowerShell script exited with an error.
    pause
    exit /b 1
)

echo.
echo [SUCCESS] Batch script finished.
pause
exit /b 0
