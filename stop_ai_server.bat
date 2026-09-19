@echo off
REM ──────────────────────────────────────────────────────
REM  PoultrySense AI Server - Stop Script
REM  Finds and terminates the AI server to release the camera
REM ──────────────────────────────────────────────────────

echo [PoultrySense AI] Stopping AI server and releasing camera...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5050" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo [PoultrySense AI] Server stopped. Camera released.
timeout /t 2 /nobreak >nul
