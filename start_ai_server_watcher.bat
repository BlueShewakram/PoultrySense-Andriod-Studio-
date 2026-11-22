@echo off
TITLE PoultrySense AI Server Watcher
COLOR 0B

echo =================================================================
echo       POULTRYSENSE AI - BACKGROUND AUTO-LAUNCHER & WATCHER       
echo =================================================================
echo.
echo [INFO] This service listens to your Mobile App toggle.
echo [INFO] When you tap 'Turn On' in the app, this PC will automatically
echo        launch start_ai_server.bat!
echo.
echo  Keep this running or minimize it.
echo -----------------------------------------------------------------
echo.

cd /d "%~dp0"
poultrysense_ai\venv\Scripts\python.exe poultrysense_watcher.py
pause
