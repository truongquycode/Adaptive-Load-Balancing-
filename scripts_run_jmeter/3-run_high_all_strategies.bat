@echo off
setlocal EnableExtensions
call "%~dp0_benchmark_common.bat" "03_high_dependency_slowdown_mixed_0900_staged_tst" "jmeter\03_high_dependency_slowdown_mixed_0900_staged_tst.jmx" "03_high_dependency_slowdown_mixed_0900_staged_tst" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
