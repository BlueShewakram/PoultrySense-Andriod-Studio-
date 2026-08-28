@echo off
REM ──────────────────────────────────────────────────────
REM  PoultrySense AI Server Auto-Launcher
REM  Starts the ByteTrack AI server if not already running
REM ──────────────────────────────────────────────────────

REM Check if the server is already running on port 5000
netstat -ano | findstr ":5000" | findstr "LISTENING" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo [PoultrySense AI] Server is already running on port 5000. Skipping.
    exit /b 0
)

echo [PoultrySense AI] Starting ByteTrack AI Server...

REM Navigate to the AI directory
cd /d "%~dp0poultrysense_ai"

REM Start the server in background (new minimized window)
start "PoultrySense AI Server" /min cmd /c ".\venv\Scripts\python.exe poultrysense_ai.py"

REM Wait briefly for the server to initialize (using ping for redirection safety)
ping -n 4 127.0.0.1 >nul 2>&1

REM Verify it started
netstat -ano | findstr ":5000" | findstr "LISTENING" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo [PoultrySense AI] Server started successfully on http://localhost:5000
) else (
    echo [PoultrySense AI] Warning: Server may still be initializing...
)

exit /b 0
