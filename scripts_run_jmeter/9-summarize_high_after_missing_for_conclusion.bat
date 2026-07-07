@echo off
setlocal EnableExtensions

REM =============================================================
REM Re-summarize High-load official result after completing missing runs.
REM This script only updates the High-load CSV, not Low/Medium.
REM =============================================================

for %%I in ("%~dp0..") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

set "RAW_DIR=%PROJECT_DIR%\benchmark-raw-results\official-runs-3x\03_high_dependency_slowdown_mixed_0900_staged_tst"
set "OUT_DIR=%PROJECT_DIR%\docs\benchmark-results\official-runs\2026-07-official-3runs\03-high-dependency-slowdown\data"
set "OUT_FILE=%OUT_DIR%\jtl-summary.csv"

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python or add it to PATH.
    exit /b 1
)

if not exist "%RAW_DIR%" (
    echo [ERROR] Raw High-load folder not found:
    echo %RAW_DIR%
    exit /b 1
)
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo [INFO] Re-summarizing High-load MEASURE-only results...
python scripts_run_jmeter\summarize_jtl_results.py "%RAW_DIR%" --output "%OUT_FILE%"
if errorlevel 1 (
    echo [ERROR] Failed to summarize High-load results.
    exit /b 1
)

echo.
echo =============================================================
echo High-load summary updated:
echo %OUT_FILE%
echo.
echo Send this file with the High-load Grafana images for the final conclusion.
echo =============================================================
exit /b 0
