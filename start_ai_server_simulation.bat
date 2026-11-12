@echo off
TITLE PoultrySense AI Vision Server (Moving Chicken Simulation)
COLOR 0B

echo =================================================================
echo    POULTRYSENSE AI - CHICKEN VISION SERVER (MOVING CHICKEN FEED)
echo =================================================================
echo [INFO] Video source: moving chicken.mp4
echo [INFO] Real-time HUD Detection Feed, conf=0.22, 384px High-FPS
echo.

set POULTRYSENSE_VIDEO_SOURCE=moving chicken.mp4
set POULTRYSENSE_DETECTOR_CONF=0.18
set POULTRYSENSE_DETECTION_CONF=0.22
set POULTRYSENSE_DETECTOR_IMGSZ=384
set POULTRYSENSE_COAST_MAX_FRAMES=2
set POULTRYSENSE_MAX_TRACKED_CHICKENS=3
set POULTRYSENSE_VETO_HZ=2.0
set POULTRYSENSE_OBSERVATION_WINDOW=6.0
set POULTRYSENSE_STATIONARY_TRIGGER_SEC=6.0
set GLOBAL_ALERT_COOLDOWN_SEC=60.0
call "%~dp0start_ai_server.bat"
