@echo off
setlocal

set SCRIPT_DIR=%~dp0
set PROJ_ROOT=%SCRIPT_DIR%..\..\..\..
set RAW_DIR=%PROJ_ROOT%\benchmark-raw-results\05_adaptive_ablation_medium_dependency_slowdown
set BASELINE_CSV=%PROJ_ROOT%\docs\benchmark-results\official-runs\2026-07-official-3runs\02-medium-dependency-slowdown\data\aggregate-summary-clean.csv
set OUT_DIR=%PROJ_ROOT%\docs\benchmark-results\05_adaptive_ablation_medium_dependency_slowdown

echo ========================================================
echo RUNNING ADAPTIVE ABLATION STUDY PIPELINE
echo ========================================================
echo.

echo [1/2] Parsing Raw Data and Calculating Differences...
python "%SCRIPT_DIR%process_adaptive_ablation_results.py" --raw-dir "%RAW_DIR%" --baseline-csv "%BASELINE_CSV%" --out-dir "%OUT_DIR%"
if errorlevel 1 (
    echo [ERROR] Parser failed!
    pause
    exit /b 1
)

echo.
echo [2/2] Generating Visualizations (PNG and SVG)...
python "%SCRIPT_DIR%generate_ablation_visualizations.py" --data-dir "%OUT_DIR%\data" --out-dir "%OUT_DIR%\visualizations"
if errorlevel 1 (
    echo [ERROR] Visualizer failed!
    pause
    exit /b 1
)

echo.
echo [SUCCESS] Pipeline finished. Data and charts are saved to:
echo %OUT_DIR%
pause
exit /b 0
