@echo off
setlocal EnableExtensions
set "RUNS_PER_ITEM=3"
set "RESULT_ROOT=%~dp0..\benchmark-raw-results"
call "%~dp0_benchmark_common.bat" "05_spike_load_adaptation_recovery_mixed_1200_tst" "jmeter\05_spike_load_adaptation_recovery_mixed_1200_tst.jmx" "additional-tests\spike-test" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
