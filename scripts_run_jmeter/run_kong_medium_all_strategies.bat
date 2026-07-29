@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3"
set "RESULT_ROOT=%~dp0..\benchmark-raw-results\kong-medium-chaos"
set "TEST_PLAN=%~dp0..\jmeter\02_medium_dependency_slowdown_mixed_0600_tst.jmx"
set "KONG_ADMIN_URL=http://172.30.35.37:8001/config"
set "KONG_SERVICE_URL=http://172.30.35.37:8001/services/backend-service"
set "BACKEND_BASE_URL=http://172.30.35.37"
set "BACKEND_PORTS=8081 8082 8083"

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

if not exist "%RESULT_ROOT%" mkdir "%RESULT_ROOT%"

REM Định nghĩa 4 thuật toán test
set STRATEGIES=round-robin least-connections random adaptive
set RUNS_PER_ITEM=5

echo =============================================================
echo Starting Kong API Gateway Benchmark
echo Test Plan: 02_medium_dependency_slowdown_mixed_0600_tst.jmx
echo =============================================================

for %%S in (%STRATEGIES%) do (
    echo.
    echo =============================================================
    echo Deploying Kong Strategy: %%S
    echo =============================================================
    
    if "%%S"=="adaptive" (
        curl -s -X POST "%KONG_ADMIN_URL%" -F config=@..\kong-adaptive-lb\kong.yml
    ) else (
        curl -s -X POST "%KONG_ADMIN_URL%" -F config=@..\kong-adaptive-lb\kong-%%S.yml
    )
    
    echo.
    echo [INFO] Waiting 5 seconds for config to apply...
    timeout /t 5 /nobreak
    
    REM Kiểm tra xem config đã được nạp trên server chưa
    set "EXPECTED_HOST="
    if "%%S"=="adaptive" set "EXPECTED_HOST=dummy-url"
    if "%%S"=="round-robin" set "EXPECTED_HOST=backend-upstream-round-robin"
    if "%%S"=="least-connections" set "EXPECTED_HOST=backend-upstream-least-connections"
    if "%%S"=="random" set "EXPECTED_HOST=backend-upstream-random"
    
    echo [INFO] Verifying strategy %%S on remote server...
    "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; for($i=1; $i -le 10; $i++){ try { $r=Invoke-RestMethod -Uri '%KONG_SERVICE_URL%' -TimeoutSec 5; if ($r.host -eq '!EXPECTED_HOST!') { Write-Host ('[INFO] Confirmed strategy active (service_host='+$r.host+')'); exit 0 } else { Write-Host ('[WARN] Host mismatch, expected !EXPECTED_HOST! but got '+$r.host) } } catch { Write-Host ('[WARN] Admin API unavailable') }; Start-Sleep -Seconds 2 }; exit 1"
    
    if errorlevel 1 (
        echo [ERROR] Failed to verify strategy %%S on Ubuntu server!
        echo Vui long kiem tra lai CI/CD hoac thu muc kong-adaptive-lb da duoc push len Ubuntu chua.
        exit /b 1
    )
    
    for /L %%N in (1,1,%RUNS_PER_ITEM%) do (
        echo.
        echo -------------------------------------------------------------
        echo Running %%S - Run %%N / %RUNS_PER_ITEM%
        echo -------------------------------------------------------------
        
        echo [INFO] Resetting backend chaos state...
        "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Continue'; foreach($p in '%BACKEND_PORTS%'.Split(' ')){ if($p.Trim().Length -gt 0){ try { Invoke-RestMethod -Method Post -Uri ('%BACKEND_BASE_URL%:'+ $p + '/api/chaos/reset') -TimeoutSec 15 | Out-Null } catch { Write-Host ('[WARN] Backend '+ $p +' reset failed') } } }; Write-Host '[INFO] Reset completed.'"
        
        echo [INFO] Waiting 20 seconds after reset...
        timeout /t 20 /nobreak
        
        set "RESULT_DIR=%RESULT_ROOT%\%%S"
        if not exist "!RESULT_DIR!" mkdir "!RESULT_DIR!"
        
        if exist "!RESULT_DIR!\%%S-%%N.jtl" del /f /q "!RESULT_DIR!\%%S-%%N.jtl"
        if exist "!RESULT_DIR!\%%S-%%N" rmdir /s /q "!RESULT_DIR!\%%S-%%N"
        
        echo [INFO] Running JMeter for %%S run %%N...
        call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%TEST_PLAN%" ^
            -JGATEWAY_PORT=8000 ^
            -l "!RESULT_DIR!\%%S-%%N.jtl" ^
            -e -o "!RESULT_DIR!\%%S-%%N"
            
        if errorlevel 1 (
            echo [ERROR] JMeter failed for %%S run %%N.
            exit /b 1
        )
        
        echo [INFO] %%S run %%N finished.
        
        if not "%%N"=="%RUNS_PER_ITEM%" (
            echo [INFO] Waiting 180 seconds before next run...
            timeout /t 180 /nobreak
        )
    )
)

echo.
echo =============================================================
echo All benchmark runs completed successfully!
echo Results are saved in: %RESULT_ROOT%
echo =============================================================
pause
exit /b 0
