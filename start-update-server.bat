@echo off
title KH-PDA Update Server
cd /d "%~dp0"

echo ========================================
echo   KH-PDA Update Server (port 9000)
echo ========================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.x and enable "Add to PATH".
    echo https://www.python.org/downloads/
    echo.
    pause
    exit /b 1
)

echo LAN IP of this machine:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    for /f "tokens=* delims= " %%b in ("%%a") do echo   http://%%b:9000
)
echo.
echo App update check URL:
echo   http://192.168.2.138:9000/app-updates/version.json
echo.
echo Browse all versions in a web browser:
echo   http://192.168.2.138:9000/app-updates/
echo.
echo Press Ctrl+C to stop.
echo ========================================
echo.

python -m http.server 9000 --bind 0.0.0.0

echo.
echo Server stopped.
pause
