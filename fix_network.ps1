# PoultrySense Network and Firewall Configuration
Write-Host "Configuring Windows Firewall rules for PoultrySense AI Server..." -ForegroundColor Cyan

# Remove old rules if they exist
Remove-NetFirewallRule -DisplayName "PoultrySense AI Port 5000" -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "PoultrySense UDP Port 5005" -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "PoultrySense Python Venv" -ErrorAction SilentlyContinue

# Allow TCP Port 5000 (Flask AI Stream & API) on all network profiles (Domain, Private, Public)
New-NetFirewallRule -DisplayName "PoultrySense AI Port 5000" `
    -Direction Inbound `
    -LocalPort 5000 `
    -Protocol TCP `
    -Action Allow `
    -Profile Any `
    -Description "Allows incoming connections to PoultrySense AI Server on port 5000"

# Allow UDP Port 5005 (PoultrySense Auto-Discovery Beacon)
New-NetFirewallRule -DisplayName "PoultrySense UDP Port 5005" `
    -Direction Inbound `
    -LocalPort 5005 `
    -Protocol UDP `
    -Action Allow `
    -Profile Any `
    -Description "Allows UDP auto-discovery beacon for PoultrySense Android App"

# Allow Python Virtual Environment Executable
$venvPython = "$PSScriptRoot\poultrysense_ai\venv\Scripts\python.exe"
if (Test-Path $venvPython) {
    New-NetFirewallRule -DisplayName "PoultrySense Python Venv" `
        -Direction Inbound `
        -Program $venvPython `
        -Action Allow `
        -Profile Any `
        -Description "Allows PoultrySense Python AI Server process"
}

# Switch active Ethernet/Wi-Fi connection to Private profile so local devices can communicate freely
try {
    Get-NetConnectionProfile | Where-Object { $_.IPv4Connectivity -ne 'NoTraffic' } | Set-NetConnectionProfile -NetworkCategory Private -ErrorAction SilentlyContinue
    Write-Host "Set active network profiles to Private." -ForegroundColor Green
} catch {
    Write-Host "Notice: Could not change network category: $_" -ForegroundColor Yellow
}

Write-Host "Firewall and Network configuration completed successfully!" -ForegroundColor Green
