@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3"
set "RESULT_ROOT=%~dp0..\benchmark-raw-results\kong-medium-chaos"
set "TEST_PLAN=%~dp0..\jmeter\02_medium_dependency_slowdown_mixed_0600_tst.jmx"
set "KONG_GATEWAY_URL=http://172.30.35.37:8000/alb/strategy"
set "BACKEND_BASE_URL=http://172.30.35.37"
set "BACKEND_PORTS=8081 8082 8083"
set "PROJECT_DIR=%~dp0.."

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

if not exist "%RESULT_ROOT%" mkdir "%RESULT_ROOT%"

REM Định nghĩa 4 thuật toán test
set STRATEGIES=round-robin least-connections random adaptive
set RUNS_PER_ITEM=3

echo =============================================================
echo Starting Kong API Gateway Benchmark (CI/CD Git Push mode)
echo Test Plan: 02_medium_dependency_slowdown_mixed_0600_tst.jmx
echo =============================================================

for %%S in (%STRATEGIES%) do (
    echo.
    echo =============================================================
    echo Deploying Kong Strategy: %%S
    echo =============================================================
    
    cd /d "%PROJECT_DIR%\kong-adaptive-lb"
    
    if "%%S"=="adaptive" (
        REM adaptive dung file kong.yml goc, luu tam la kong.yml.bak
        if exist kong.yml.bak copy /y kong.yml.bak kong.yml >nul
    ) else (
        REM backup file kong.yml goc neu chua co
        if not exist kong.yml.bak copy /y kong.yml kong.yml.bak >nul
        copy /y kong-%%S.yml kong.yml >nul
    )
    
    echo [INFO] Committing and pushing config %%S to trigger CI/CD...
    git add kong.yml
    git commit -m "Benchmark deploy strategy %%S"
    git push origin main
    
    if errorlevel 1 (
        echo [ERROR] Git push failed. Please check your git connection.
        exit /b 1
    )
    
    echo.
    echo [INFO] Waiting 20 seconds for GitHub Actions to start deploy...
    timeout /t 20 /nobreak
    
    cd /d "%~dp0"
    
    echo [INFO] Verifying strategy %%S on remote server - Polling %KONG_GATEWAY_URL% ...
    "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; Write-Host ('[CHECK] Polling API: %KONG_GATEWAY_URL%'); for($i=1; $i -le 30; $i++){ Write-Host -NoNewline ('  Attempt ' + $i + '/30: '); try { $r=Invoke-RestMethod -Uri '%KONG_GATEWAY_URL%' -TimeoutSec 5; if ($r.message -eq '%%S') { Write-Host ('SUCCESS! Ubuntu is currently running: [' + $r.message + ']') -ForegroundColor Green; exit 0 } else { Write-Host ('MISMATCH! Expected [%%S], but Ubuntu is running: [' + $r.message + ']') -ForegroundColor Yellow } } catch { Write-Host 'API UNAVAILABLE or TIMEOUT. Waiting for Kong...' -ForegroundColor Red }; Start-Sleep -Seconds 5 }; exit 1"
    
    if errorlevel 1 (
        echo [ERROR] Failed to verify strategy %%S on Ubuntu server.
        echo CI/CD pipeline might have failed, or the Gateway is not starting.
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
