@echo off
setlocal

echo =============================================================
echo Building Final Dataset via PowerShell...
echo =============================================================

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_final_dataset.ps1"

if errorlevel 1 (
    echo [ERROR] The PowerShell script exited with an error.
    pause
    exit /b 1
)

echo.
echo [SUCCESS] Batch script finished.
pause
exit /b 0
