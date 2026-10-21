@echo off
TITLE Stop PoultrySense AI Server
COLOR 0E

echo =================================================================
echo             POULTRYSENSE AI - SERVER STOP UTILITY               
echo =================================================================
echo.
echo [PoultrySense AI] Stopping AI server and releasing camera...

set FOUND=0
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
    set FOUND=1
    echo [OK] Terminated AI server process PID %%a
)

if "%FOUND%"=="0" (
    echo [INFO] No running AI server was found on port 5000.
) else (
    echo [OK] Camera released and port 5000 is now free.
)

echo.
ping 127.0.0.1 -n 3 >nul
