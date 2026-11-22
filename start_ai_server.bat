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

REM Hardware Optimization for AMD Ryzen 5 3500 & AMD Radeon RX 580 (DirectML)
set ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS=1
set YOLO_AUTOINSTALL=False
set POULTRYSENSE_BACKEND=directml
set OMP_NUM_THREADS=6
set MKL_NUM_THREADS=6

REM 3. Launch AI Server
echo [2/3] Detecting Local LAN IP and Publishing to Cloud Registry...
for /f "tokens=*" %%i in ('.\venv\Scripts\python.exe -c "import socket; s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(('8.8.8.8',1)); print(s.getsockname()[0]); s.close()" 2^>nul') do set "LOCAL_IP=%%i"
if "%LOCAL_IP%"=="" set "LOCAL_IP=127.0.0.1"

echo [3/3] Starting AI Server on port 5000...
echo.
echo =================================================================
echo   POULTRYSENSE AI VISION SERVER - ACTIVE
echo   Edge PC Local IP: %LOCAL_IP%
echo   App Base URL:     http://%LOCAL_IP%:5000
echo   Live Web Stream:  http://localhost:5000
echo =================================================================
echo.
echo  Tip: Ensure your Android phone is connected to the same Wi-Fi
echo       modem or router.
echo  Press Ctrl+C to stop the AI server.
echo -----------------------------------------------------------------
echo.

.\venv\Scripts\python.exe -c "import requests, time; requests.put('https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app/system_control/ai_server.json', json='on', timeout=3); requests.put('https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app/system_control/ai_server_ip.json', json={'ip': '%LOCAL_IP%', 'port': 5000, 'status': 'online', 'timestamp': int(time.time()*1000)}, timeout=3)" >nul 2>&1

.\venv\Scripts\python.exe poultrysense_ai.py

.\venv\Scripts\python.exe -c "import requests, time; requests.put('https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app/system_control/ai_server.json', json='off', timeout=3); requests.put('https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app/system_control/ai_server_ip.json', json={'ip': '%LOCAL_IP%', 'port': 5000, 'status': 'offline', 'timestamp': int(time.time()*1000)}, timeout=3)" >nul 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] AI Server exited with code %ERRORLEVEL%.
    pause
)
