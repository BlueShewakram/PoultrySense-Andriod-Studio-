@echo off
TITLE PoultrySense AI Vision Server
COLOR 0A

echo =================================================================
echo        POULTRYSENSE AI - CHICKEN VISION AND TELEMETRY SERVER       
echo =================================================================
echo.

REM 1. Clean up port 5000 if occupied
echo [1/3] Checking port 5000 status...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTENING"') do (
    echo [INFO] Stopping previous server instance PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

net session >nul 2>&1
if %errorlevel% equ 0 netsh advfirewall firewall add rule name="PoultrySense 5000" dir=in action=allow protocol=TCP localport=5000 profile=any >nul 2>&1
if %errorlevel% equ 0 netsh advfirewall firewall add rule name="PoultrySense 5005" dir=in action=allow protocol=UDP localport=5005 profile=any >nul 2>&1

REM 2. Navigate to poultrysense_ai directory
cd /d "%~dp0poultrysense_ai"

if not exist ".\venv\Scripts\python.exe" (
    echo [ERROR] Virtual environment not found at poultrysense_ai\venv
    pause
    exit /b 1
)

REM 3. Launch AI Server
echo [2/3] Initializing Camera (External USB) and AI Models...
echo [3/3] Starting AI Server on port 5000...
echo.
echo  Live Web Stream: http://localhost:5000
echo  Press Ctrl+C to stop the AI server.
echo -----------------------------------------------------------------
echo.

.\venv\Scripts\python.exe poultrysense_ai.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] AI Server exited with code %ERRORLEVEL%.
    pause
)
