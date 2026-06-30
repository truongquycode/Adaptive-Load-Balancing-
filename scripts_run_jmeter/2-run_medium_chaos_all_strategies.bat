@echo off
setlocal EnableExtensions
call "%~dp0_benchmark_common.bat" "02_medium_dependency_slowdown_mixed_0600_tst" "jmeter\02_medium_dependency_slowdown_mixed_0600_tst.jmx" "02_medium_dependency_slowdown_mixed_0600_tst" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
