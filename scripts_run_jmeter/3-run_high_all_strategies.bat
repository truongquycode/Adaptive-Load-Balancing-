@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================
REM  Run high Load chaos benchmark for 4 ALB strategies
REM  Robust version:
REM    - Startup check ensures benchmark begins with round-robin.
REM    - Always trigger CI/CD deploy for every strategy, even when local yml already matches.
REM    - Avoids the case: result folder says round_robin but server is still running another strategy.
REM =============================================================

REM ====== EDIT THESE PATHS IF NEEDED ======
set "JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3"
set "TEST_PLAN=D:\eclipse-workspace\adaptive-load-balancer-parent\jmeter\03_high_dependency_slowdown_mixed_0900_staged_tst.jmx"
set "RESULT_BASE=D:\Downloads\apache-jmeter-5.6.3\bin\ALB_Test\results\03_high_dependency_slowdown_mixed_0900_staged_tst"

set "PROJECT_DIR=D:\eclipse-workspace\adaptive-load-balancer-parent"
set "APP_YML=%PROJECT_DIR%\api-gateway-alb\src\main\resources\application.yml"
set "DEPLOY_MARKER=%PROJECT_DIR%\api-gateway-alb\.alb-strategy-deploy-marker.txt"
set "GIT_BRANCH=main"

REM Optional server strategy endpoint. Works only if you added GET /actuator/alb/strategy.
set "SERVER_STRATEGY_URL=http://172.30.35.37:8080/actuator/alb/strategy"
set "STRICT_SERVER_STRATEGY_CHECK=false"

REM Use absolute PowerShell path to avoid PATH issue.
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

REM ====== BENCHMARK SETTINGS ======
set "RUNS_PER_STRATEGY=5"
set "WAIT_BETWEEN_RUNS=180"
set "WAIT_AFTER_PUSH=140"

REM ====== CHECK REQUIRED FILES ======
if not exist "%JMETER_HOME%\bin\jmeter.bat" (
    echo [ERROR] JMeter not found: %JMETER_HOME%\bin\jmeter.bat
    pause
    exit /b 1
)

if not exist "%TEST_PLAN%" (
    echo [ERROR] Test plan not found: %TEST_PLAN%
    pause
    exit /b 1
)

if not exist "%APP_YML%" (
    echo [ERROR] application.yml not found: %APP_YML%
    pause
    exit /b 1
)

if not exist "%POWERSHELL_EXE%" (
    echo [ERROR] PowerShell not found: %POWERSHELL_EXE%
    pause
    exit /b 1
)

REM ====== STARTUP SAFETY CHECK ======
echo.
echo =============================================================
echo Startup check: benchmark must begin with round-robin
echo =============================================================
call :READ_LOCAL_STRATEGY
if errorlevel 1 goto FAILED

echo [INFO] Local application.yml currently has alb.strategy = !LOCAL_STRATEGY!
if /I not "!LOCAL_STRATEGY!"=="round-robin" (
    echo [WARN] Local strategy is not round-robin. It will be changed and deployed before benchmark.
) else (
    echo [INFO] Local strategy is already round-robin.
    echo [INFO] A redeploy will still be forced to ensure the server also runs round-robin.
)

call :SET_DEPLOY_AND_VERIFY "round-robin" "round_robin" "startup"
if errorlevel 1 goto FAILED

REM ====== RUN STRATEGIES IN ORDER ======
call :RUN_ONLY "round-robin" "round_robin"
if errorlevel 1 goto FAILED

call :SET_DEPLOY_AND_VERIFY "random" "random" "strategy-change"
if errorlevel 1 goto FAILED
call :RUN_ONLY "random" "random"
if errorlevel 1 goto FAILED

call :SET_DEPLOY_AND_VERIFY "least-connections" "least_connect" "strategy-change"
if errorlevel 1 goto FAILED
call :RUN_ONLY "least-connections" "least_connect"
if errorlevel 1 goto FAILED

call :SET_DEPLOY_AND_VERIFY "adaptive" "adaptive" "strategy-change"
if errorlevel 1 goto FAILED
call :RUN_ONLY "adaptive" "adaptive"
if errorlevel 1 goto FAILED

echo.
echo =============================================================
echo All strategies completed successfully.
echo Results saved in: %RESULT_BASE%
echo =============================================================
if /I not "%NO_PAUSE%"=="true" pause
exit /b 0

REM =============================================================
REM  Read current local alb.strategy from application.yml
REM  Uses a temp file instead of FOR /F PowerShell command substitution to avoid quote bugs on Windows CMD.
REM =============================================================
REM =============================================================
REM  Read current local alb.strategy from application.yml
REM  This version uses FINDSTR instead of PowerShell, so startup cannot fail because of PowerShell quote parsing.
REM =============================================================
:READ_LOCAL_STRATEGY
set "LOCAL_STRATEGY="

for /f "tokens=1,* delims=:" %%A in ('findstr /R /C:"^[ ][ ]*strategy:[ ][ ]*" "%APP_YML%"') do (
    set "LOCAL_STRATEGY=%%B"
    goto TRIM_LOCAL_STRATEGY
)

:TRIM_LOCAL_STRATEGY
if not defined LOCAL_STRATEGY (
    echo [ERROR] Cannot read alb.strategy from %APP_YML%
    exit /b 1
)

REM Remove inline comment after value, for example: strategy: round-robin  # comment
for /f "tokens=1 delims=#" %%S in ("!LOCAL_STRATEGY!") do set "LOCAL_STRATEGY=%%S"

REM Trim leading spaces
for /f "tokens=* delims= " %%S in ("!LOCAL_STRATEGY!") do set "LOCAL_STRATEGY=%%S"

REM Trim trailing spaces by asking CMD to re-tokenize once more
for /f "tokens=1" %%S in ("!LOCAL_STRATEGY!") do set "LOCAL_STRATEGY=%%S"

if not defined LOCAL_STRATEGY (
    echo [ERROR] Cannot read alb.strategy from %APP_YML%
    exit /b 1
)
exit /b 0

REM =============================================================
REM  Set application.yml strategy, commit/push, wait for CI/CD, verify server if endpoint exists.
REM =============================================================
:SET_DEPLOY_AND_VERIFY
set "STRATEGY=%~1"
set "LABEL=%~2"
set "REASON=%~3"

echo.
echo =============================================================
echo Deploy strategy: %STRATEGY%  [%REASON%]
echo =============================================================

cd /d "%PROJECT_DIR%"
if errorlevel 1 (
    echo [ERROR] Cannot cd to project dir: %PROJECT_DIR%
    exit /b 1
)

echo [INFO] Abort any unfinished merge/rebase state if present...
git rebase --abort >nul 2>nul
git merge --abort >nul 2>nul

echo [INFO] Pull latest code from GitHub using --autostash...
git pull --rebase --autostash origin %GIT_BRANCH%
if errorlevel 1 (
    echo [ERROR] git pull failed even with --autostash.
    echo [ERROR] Run these commands manually, then rerun the benchmark:
    echo        cd /d "%PROJECT_DIR%"
    echo        git status
    echo        git rebase --abort
    echo        git merge --abort
    echo        git pull --rebase --autostash origin %GIT_BRANCH%
    exit /b 1
)

echo [INFO] Updating application.yml: alb.strategy = %STRATEGY%
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p='%APP_YML%'; $s='%STRATEGY%'; $enc=New-Object System.Text.UTF8Encoding($false); $lines=[System.IO.File]::ReadAllLines($p,$enc); $inside=$false; $done=$false; for($i=0; $i -lt $lines.Length; $i++){ if($lines[$i] -match '^alb:\s*$'){ $inside=$true; continue }; if($inside -and $lines[$i] -match '^\S'){ $inside=$false }; if($inside -and $lines[$i] -match '^\s*strategy:\s*'){ $lines[$i]='    strategy: '+$s; $done=$true; break } }; if(-not $done){ Write-Error 'Cannot find alb.strategy'; exit 2 }; [System.IO.File]::WriteAllLines($p,$lines,$enc)"
if errorlevel 1 (
    echo [ERROR] Failed to update application.yml
    exit /b 1
)


REM Update a real marker under api-gateway-alb so GitHub Actions always redeploys the gateway.
REM Empty commits may be skipped by deploy.yml because there are no changed files.
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p='%DEPLOY_MARKER%'; $enc=New-Object System.Text.UTF8Encoding($false); $content=@('strategy=%STRATEGY%','reason=%REASON%','timestamp='+(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')); [System.IO.File]::WriteAllLines($p,$content,$enc)"
if errorlevel 1 (
    echo [ERROR] Failed to update deploy marker
    exit /b 1
)

git add "%APP_YML%" "%DEPLOY_MARKER%"
git diff --cached --quiet
if not errorlevel 1 (
    echo [ERROR] No staged changes were created. This should not happen because deploy marker changes every deploy.
    exit /b 1
)

echo [INFO] Commit strategy change and deploy marker...
git commit -m "Benchmark deploy ALB strategy %STRATEGY% [%REASON%]"
if errorlevel 1 (
    echo [ERROR] git commit failed.
    exit /b 1
)

echo [INFO] Push to GitHub...
git push origin HEAD:%GIT_BRANCH%
if errorlevel 1 (
    echo [ERROR] git push failed.
    exit /b 1
)

echo [INFO] Waiting %WAIT_AFTER_PUSH% seconds for CI/CD deploy...
timeout /t %WAIT_AFTER_PUSH% /nobreak

call :VERIFY_SERVER_STRATEGY "%STRATEGY%"
if errorlevel 1 exit /b 1

exit /b 0

REM =============================================================
REM  Verify actual strategy from server endpoint if available.
REM  If endpoint is unavailable and STRICT_SERVER_STRATEGY_CHECK=false, warn and continue.
REM =============================================================
:VERIFY_SERVER_STRATEGY
set "EXPECTED=%~1"
set "SERVER_STRATEGY="
set "TMP_SERVER_STRATEGY_FILE=%TEMP%\alb_server_strategy_%RANDOM%%RANDOM%.txt"

echo [INFO] Checking server strategy endpoint: %SERVER_STRATEGY_URL%
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { $r=Invoke-RestMethod -Uri '%SERVER_STRATEGY_URL%' -TimeoutSec 10; if($null -ne $r.strategy){ Write-Output $r.strategy } else { Write-Output 'UNKNOWN' } } catch { Write-Output 'UNAVAILABLE' }" > "%TMP_SERVER_STRATEGY_FILE%"
if errorlevel 1 (
    set "SERVER_STRATEGY=UNAVAILABLE"
) else (
    set /p SERVER_STRATEGY=<"%TMP_SERVER_STRATEGY_FILE%"
)
if exist "%TMP_SERVER_STRATEGY_FILE%" del /f /q "%TMP_SERVER_STRATEGY_FILE%" >nul 2>nul
if not defined SERVER_STRATEGY set "SERVER_STRATEGY=UNAVAILABLE"

if /I "%SERVER_STRATEGY%"=="UNAVAILABLE" (
    echo [WARN] Server strategy endpoint unavailable.
    if /I "%STRICT_SERVER_STRATEGY_CHECK%"=="true" (
        echo [ERROR] Strict check enabled. Cannot verify actual server strategy.
        exit /b 1
    ) else (
        echo [WARN] Continuing because STRICT_SERVER_STRATEGY_CHECK=false.
        echo [WARN] To verify strictly, add GET /actuator/alb/strategy and set STRICT_SERVER_STRATEGY_CHECK=true.
        exit /b 0
    )
)

if /I not "%SERVER_STRATEGY%"=="%EXPECTED%" (
    echo [ERROR] Server strategy mismatch!
    echo [ERROR] Expected: %EXPECTED%
    echo [ERROR] Actual  : %SERVER_STRATEGY%
    exit /b 1
)

echo [INFO] Server strategy verified: %SERVER_STRATEGY%
exit /b 0

REM =============================================================
REM  Run JMeter 5 times for an already deployed strategy.
REM =============================================================
:RUN_ONLY
set "STRATEGY=%~1"
set "LABEL=%~2"
set "RESULT_DIR=%RESULT_BASE%\%LABEL%"

echo.
echo =============================================================
echo Strategy: %STRATEGY%
echo Result folder: %RESULT_DIR%
echo =============================================================

if not exist "%RESULT_DIR%" mkdir "%RESULT_DIR%"

for /L %%i in (1,1,%RUNS_PER_STRATEGY%) do (
    echo.
    echo -------------------------------------------------------------
    echo Running %LABEL% test %%i / %RUNS_PER_STRATEGY%
    echo -------------------------------------------------------------

    if exist "%RESULT_DIR%\%LABEL%-%%i.jtl" del /f /q "%RESULT_DIR%\%LABEL%-%%i.jtl"
    if exist "%RESULT_DIR%\%LABEL%-%%i" rmdir /s /q "%RESULT_DIR%\%LABEL%-%%i"

    call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%TEST_PLAN%" ^
    -l "%RESULT_DIR%\%LABEL%-%%i.jtl" ^
    -e -o "%RESULT_DIR%\%LABEL%-%%i"

    if errorlevel 1 (
        echo [ERROR] JMeter failed for %LABEL% run %%i.
        exit /b 1
    )

    echo [INFO] %LABEL% run %%i finished.

    if not "%%i"=="%RUNS_PER_STRATEGY%" (
        echo [INFO] Waiting %WAIT_BETWEEN_RUNS% seconds before next run...
        timeout /t %WAIT_BETWEEN_RUNS% /nobreak
    )
)

echo [INFO] Completed strategy: %STRATEGY%
exit /b 0

:FAILED
echo.
echo =============================================================
echo Benchmark stopped because an error occurred.
echo =============================================================
pause
exit /b 1
