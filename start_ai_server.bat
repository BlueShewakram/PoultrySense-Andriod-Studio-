@echo off
TITLE PoultrySense AI Vision Server
COLOR 0A

echo =================================================================
echo        POULTRYSENSE AI - CHICKEN VISION AND TELEMETRY SERVER       
echo =================================================================
echo.

REM 1. Check if port 5000 is occupied and cleanly terminate old instances
echo [1/3] Checking port 5000 status...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTENING"') do (
    echo [INFO] Stopping previous server instance PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

REM 2. Navigate to poultrysense_ai directory
cd /d "%~dp0poultrysense_ai"

if not exist ".\venv\Scripts\python.exe" (
    echo [ERROR] Virtual environment not found at poultrysense_ai\venv
    echo Please make sure the Python venv is installed.
    pause
    exit /b 1
)

REM 3. Launch the AI Server
echo [2/3] Initializing YOLOv8 Models and Camera Feed...
echo [3/3] Starting Flask Stream on port 5000...
echo.
echo -----------------------------------------------------------------
echo  Live Web Stream: http://localhost:5000
echo  Press Ctrl+C in this window at any time to stop the AI server.
echo -----------------------------------------------------------------
echo.

.\venv\Scripts\python.exe poultrysense_ai.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] AI Server exited with code %ERRORLEVEL%.
    pause
)
