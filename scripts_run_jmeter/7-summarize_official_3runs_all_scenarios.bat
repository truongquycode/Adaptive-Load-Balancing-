@echo off
setlocal EnableExtensions

REM =============================================================
REM Summarize official overnight benchmark results.
REM
REM This script reads raw .jtl files from:
REM   benchmark-raw-results\official-runs-3x
REM and writes MEASURE-only CSV summaries into:
REM   docs\benchmark-results\official-runs\2026-07-official-3runs
REM =============================================================

for %%I in ("%~dp0..") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

set "RAW_ROOT=%PROJECT_DIR%\benchmark-raw-results\official-runs-3x"
set "DOC_ROOT=%PROJECT_DIR%\docs\benchmark-results\official-runs\2026-07-official-3runs"
set "PYTHON_CMD=python"

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python or add it to PATH.
    exit /b 1
)

if not exist "%RAW_ROOT%" (
    echo [ERROR] Raw result root not found: %RAW_ROOT%
    exit /b 1
)

if not exist "%DOC_ROOT%" mkdir "%DOC_ROOT%"

call :SUMMARIZE_ONE "01_low_baseline_mixed_0300_nochaos_tst" "01-low-load"
if errorlevel 1 exit /b 1

call :SUMMARIZE_ONE "02_medium_dependency_slowdown_mixed_0600_tst" "02-medium-dependency-slowdown"
if errorlevel 1 exit /b 1

call :SUMMARIZE_ONE "03_high_dependency_slowdown_mixed_0900_staged_tst" "03-high-dependency-slowdown"
if errorlevel 1 exit /b 1

call :SUMMARIZE_ONE "04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst" "04-stress-recovery"
if errorlevel 1 exit /b 1

(
    echo # Official 3-run Benchmark Results
    echo.
    echo Bộ thư mục này chứa các file CSV tổng hợp từ kết quả benchmark chính thức, mỗi thuật toán chạy 3 lần cho từng mức tải.
    echo.
    echo Dữ liệu trong các file CSV chỉ tính các sampler có nhãn `MEASURE_`, không tính setup, teardown, warm-up, ramp-down hoặc guard.
    echo.
    echo ^| Kịch bản ^| Thư mục ^|
    echo ^|---^|---^|
    echo ^| Low load 300 RPS ^| `01-low-load` ^|
    echo ^| Medium dependency slowdown 600 RPS ^| `02-medium-dependency-slowdown` ^|
    echo ^| High dependency slowdown 900 RPS ^| `03-high-dependency-slowdown` ^|
    echo ^| Stress/recovery 1200→600 RPS ^| `04-stress-recovery` ^|
) > "%DOC_ROOT%\README.md"

echo.
echo =============================================================
echo Official summaries created in:
echo %DOC_ROOT%
echo =============================================================
exit /b 0

:SUMMARIZE_ONE
set "SCENARIO_DIR=%~1"
set "DOC_SUBDIR=%~2"
set "RAW_DIR=%RAW_ROOT%\%SCENARIO_DIR%"
set "OUT_DIR=%DOC_ROOT%\%DOC_SUBDIR%\data"

if not exist "%RAW_DIR%" (
    echo [ERROR] Raw scenario folder not found: %RAW_DIR%
    exit /b 1
)
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo [INFO] Summarizing %SCENARIO_DIR%...
%PYTHON_CMD% scripts_run_jmeter\summarize_jtl_results.py "%RAW_DIR%" --output "%OUT_DIR%\jtl-summary.csv"
if errorlevel 1 (
    echo [ERROR] Failed to summarize: %RAW_DIR%
    exit /b 1
)

(
    echo # %DOC_SUBDIR%
    echo.
    echo File `data/jtl-summary.csv` được sinh từ raw JTL của kịch bản `%SCENARIO_DIR%`.
    echo.
    echo **Chỉ các dòng `MEASURE_` được dùng để tính kết quả.**
) > "%DOC_ROOT%\%DOC_SUBDIR%\README.md"

exit /b 0
