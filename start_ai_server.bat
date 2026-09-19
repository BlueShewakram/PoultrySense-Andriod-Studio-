@echo off
REM ──────────────────────────────────────────────────────
REM  PoultrySense AI Server Auto-Launcher
REM  Starts the AI server if not already running (port 5050)
REM ──────────────────────────────────────────────────────

REM Check if the server is already running on port 5050
netstat -ano | findstr ":5050" | findstr "LISTENING" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo [PoultrySense AI] Server is already running on port 5050. Skipping.
    exit /b 0
)

echo [PoultrySense AI] Starting AI Server on port 5050...

REM Navigate to the AI directory
cd /d "%~dp0poultrysense_ai"

REM Start the server in background (new minimized window)
start "PoultrySense AI Server" /min cmd /c ".\venv\Scripts\python.exe poultrysense_ai.py"

REM Wait briefly for the server to initialize (using ping for redirection safety)
ping -n 4 127.0.0.1 >nul 2>&1

REM Verify it started
netstat -ano | findstr ":5050" | findstr "LISTENING" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo [PoultrySense AI] Server started successfully on http://localhost:5050
) else (
    echo [PoultrySense AI] Warning: Server may still be initializing...
)

exit /b 0
