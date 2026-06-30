@echo off
setlocal EnableExtensions
call "%~dp0_benchmark_common.bat" "01_low_baseline_mixed_0300_nochaos_tst" "jmeter\01_low_baseline_mixed_0300_nochaos_tst.jmx" "01_low_baseline_mixed_0300_nochaos_tst" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
