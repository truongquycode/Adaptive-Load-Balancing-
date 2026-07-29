@echo off
setlocal

echo =============================================================
echo Starting Stress Test (Round Robin Only - 3 Runs) via PowerShell...
echo =============================================================

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_round_robin_stress_test.ps1"

if errorlevel 1 (
    echo [ERROR] The PowerShell script exited with an error.
    pause
    exit /b 1
)

echo.
echo [SUCCESS] Batch script finished.
pause
exit /b 0
