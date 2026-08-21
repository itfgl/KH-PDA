@echo off
chcp 65001 >nul
title 凯航 PDA 更新服务器
cd /d "%~dp0"

echo ========================================
echo   凯航 PDA 更新服务器
echo ========================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Python，请先安装 Python 3.x 并勾选 "Add to PATH"
    echo 下载地址: https://www.python.org/downloads/
    echo.
    pause
    exit /b 1
)

echo 本机局域网 IP（供其他设备访问）:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    for /f "tokens=* delims= " %%b in ("%%a") do echo   http://%%b:9000
)
echo.
echo 手机 App 检查更新地址:
echo   http://192.168.2.138:9000/app-updates/version.json
echo.
echo 浏览器下载全部版本:
echo   http://192.168.2.138:9000/app-updates/
echo.
echo 按 Ctrl+C 停止服务
echo ========================================
echo.

python -m http.server 9000 --bind 0.0.0.0

echo.
echo 服务已停止
pause
