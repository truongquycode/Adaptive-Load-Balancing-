@echo off
setlocal EnableExtensions
REM Chạy riêng Adaptive ablation study trên kịch bản medium dependency slowdown.
REM Các biến thể gồm: full, no-pid, fixed-weights, no-ewma-latency, no-score-ema,
REM no-capacity, no-p2c, no-probe, no-low-load-rr.
call "%~dp0_benchmark_common.bat" "adaptive_ablation_medium_dependency_slowdown" "jmeter\02_medium_dependency_slowdown_mixed_0600_tst.jmx" "05_adaptive_ablation_medium_dependency_slowdown" "ablation"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
