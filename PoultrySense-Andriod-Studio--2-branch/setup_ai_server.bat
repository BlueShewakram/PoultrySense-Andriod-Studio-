@echo off
title "PoultrySense AI - Automated Environment & Model Setup"
echo =====================================================================
echo           POULTRYSENSE AI - ONE-CLICK ENVIRONMENT SETUP
echo =====================================================================
echo.

:: 1. Check Python installation
set "PYTHON_EXEC="
py -3.11 -c "import sys" >nul 2>&1
if %errorlevel% equ 0 (
    set "PYTHON_EXEC=py -3.11"
    goto :found_python
)

py -3.10 -c "import sys" >nul 2>&1
if %errorlevel% equ 0 (
    set "PYTHON_EXEC=py -3.10"
    goto :found_python
)

python -c "import sys; sys.exit(0 if sys.version_info[:2] in [(3, 10), (3, 11)] else 1)" >nul 2>&1
if %errorlevel% equ 0 (
    set "PYTHON_EXEC=python"
    goto :found_python
)

python --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [ERROR] Incompatible Python version detected!
    python --version
    echo PoultrySense AI requires Python 3.10 or 3.11 because core AI packages
    echo (NumPy, PyTorch, Mediapipe, OpenCV) require Python 3.10 or 3.11 prebuilt wheels.
    echo Please install Python 3.11 from https://www.python.org/
    pause
    exit /b 1
)

echo [ERROR] Python is not found in PATH!
echo Please install Python 3.10 or 3.11 from https://www.python.org/
echo Make sure to check "Add Python to PATH" during installation.
pause
exit /b 1

:found_python
echo [1/4] Verified Python installation (%PYTHON_EXEC%).
cd /d "%~dp0poultrysense_ai"

:: 2. Create virtual environment if missing
if not exist "venv\Scripts\python.exe" (
    echo [2/4] Creating Python virtual environment in poultrysense_ai\venv...
    %PYTHON_EXEC% -m venv venv
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to create virtual environment.
        pause
        exit /b 1
    )
    echo       Virtual environment created successfully.
) else (
    echo [2/4] Existing virtual environment found.
)

:: 3. Install required packages
echo [3/4] Installing dependencies from requirements.txt...
call venv\Scripts\python.exe -m pip install --upgrade pip --quiet
call venv\Scripts\pip.exe install -r requirements.txt
if %errorlevel% neq 0 (
    echo [ERROR] Failed to install dependencies.
    pause
    exit /b 1
)

:: 4. Verify & Download models and pipeline
echo [4/4] Verifying pipeline and downloading models...
call venv\Scripts\python.exe download_models.py

echo.
echo =====================================================================
echo [SUCCESS] PoultrySense AI setup is complete!
echo You can now run start_ai_server.bat anytime to launch the server.
echo =====================================================================
echo.
pause
