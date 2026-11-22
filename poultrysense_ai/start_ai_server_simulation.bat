@echo off
TITLE PoultrySense AI Vision Server (5mins Chicken Simulation)
COLOR 0B

echo =================================================================
echo    POULTRYSENSE AI - CHICKEN VISION SERVER (5MINS CHICKEN FEED)
echo =================================================================
echo [INFO] Video source: 5mins chicken.mp4 (5.7 min real footage)
echo [INFO] Real-time HUD Detection Feed, conf=0.22, 640px High-Accuracy DirectML
echo.

set ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS=1
set YOLO_AUTOINSTALL=False
set POULTRYSENSE_BACKEND=directml
set POULTRYSENSE_VIDEO_SOURCE=bantres.jpg
set POULTRYSENSE_DETECTOR_CONF=0.18
set POULTRYSENSE_DETECTION_CONF=0.22
set POULTRYSENSE_DETECTOR_IMGSZ=640
set POULTRYSENSE_COAST_MAX_FRAMES=2
set POULTRYSENSE_MAX_TRACKED_CHICKENS=3
set POULTRYSENSE_VETO_HZ=2.0
set POULTRYSENSE_OBSERVATION_WINDOW=6.0
set POULTRYSENSE_STATIONARY_TRIGGER_SEC=6.0
set GLOBAL_ALERT_COOLDOWN_SEC=60.0
cd /d "%~dp0"
call "..\start_ai_server.bat"
