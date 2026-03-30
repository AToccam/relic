@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "NO_PAUSE=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
shift
goto parse_args
:args_done

set "STOPPED_ANY=0"
set "FAILED_ANY=0"

echo ============================================================
echo Relic quick stop sequence:
echo   1^) relic-face  2^) relic-core  3^) OpenClaw  4^) Ollama
echo   Port order: 5173 -^> 8082 -^> 18789 -^> 11434
echo ============================================================
echo.

call :stop_service_port "relic-face" 5173
call :stop_service_port "relic-core" 8082
call :stop_service_port "OpenClaw" 18789
call :stop_service_port "Ollama" 11434

echo.
echo ===================== Summary =====================
if "%STOPPED_ANY%"=="1" (
  echo One or more services were stopped successfully.
) else (
  echo No target service was listening on the target ports.
)
if "%FAILED_ANY%"=="1" (
  echo Some stop actions failed. Try running terminal as Administrator.
  set "FINAL_EXIT=1"
) else (
  set "FINAL_EXIT=0"
)

if "%NO_PAUSE%"=="0" (
  echo.
  echo Press any key to close this window...
  pause >nul
)
exit /b %FINAL_EXIT%

:stop_service_port
echo [%~1] Checking port %~2...
set "PIDS="

for /f %%P in ('powershell -NoProfile -Command "$port=%~2; $pids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; foreach($p in $pids){$p}"') do (
  set "PIDS=!PIDS! %%P"
)

if not defined PIDS (
  echo - Not running on port %~2.
  goto :eof
)

set "KILLED_ONE=0"
for %%P in (!PIDS!) do (
  taskkill /PID %%P /T /F >nul 2>&1
  if errorlevel 1 (
    echo - Failed to stop PID %%P.
    set "FAILED_ANY=1"
  ) else (
    echo - Stopped PID %%P.
    set "KILLED_ONE=1"
    set "STOPPED_ANY=1"
  )
)

call :wait_port_down %~2 6
if "!PORT_FREE!"=="1" (
  echo - Port %~2 released.
) else (
  echo - Port %~2 still occupied.
  set "FAILED_ANY=1"
)
goto :eof

:wait_port_down
set "PORT_FREE=0"
for /l %%I in (1,1,%~2) do (
  call :is_port_listening %~1
  if "!PORT_LISTENING!"=="0" (
    set "PORT_FREE=1"
    goto :eof
  )
  timeout /t 1 /nobreak >nul
)
goto :eof

:is_port_listening
set "PORT_LISTENING=0"
powershell -NoProfile -Command "$port=%~1; $item = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq $port } | Select-Object -First 1; if ($item) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 set "PORT_LISTENING=1"
goto :eof
