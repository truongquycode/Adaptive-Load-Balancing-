@echo off
setlocal EnableExtensions
call "%~dp0_benchmark_common.bat" "04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst" "jmeter\04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx" "04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
