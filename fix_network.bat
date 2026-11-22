@echo off
TITLE PoultrySense Network & Firewall Fix
echo =================================================================
echo         POULTRYSENSE AI - NETWORK & FIREWALL CONFIGURATION       
echo =================================================================
echo.
echo Requesting administrator privileges to allow AI Server traffic...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"\"%~dp0fix_network.ps1\"\"' -Verb RunAs -Wait"

echo.
echo =================================================================
echo Configuration applied! Port 5000 and 5005 are now unblocked.
echo =================================================================
echo.
pause
