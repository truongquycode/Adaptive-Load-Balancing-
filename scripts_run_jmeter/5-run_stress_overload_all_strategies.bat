@echo off
setlocal EnableExtensions
set "RUNS_PER_ITEM=3"
set "WAIT_BETWEEN_RUNS=400"
set "RESULT_ROOT=%~dp0..\benchmark-raw-results"

if exist "%~dp0..\jmeter\04_stress_overload_graceful_degradation_mixed_1300_tst.jmx" ren "%~dp0..\jmeter\04_stress_overload_graceful_degradation_mixed_1300_tst.jmx" "04_stress_overload_graceful_degradation_mixed_1200_tst.jmx"

call "%~dp0_benchmark_common.bat" "04_stress_overload_graceful_degradation_mixed_1200_tst" "jmeter\04_stress_overload_graceful_degradation_mixed_1200_tst.jmx" "additional-tests\stress-test" "strategies"
if errorlevel 1 exit /b 1
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0
