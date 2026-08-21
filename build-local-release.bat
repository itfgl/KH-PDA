@echo off
REM 本地一键构建 Release APK 入口（内部调用 PowerShell 脚本）
REM 用法：双击本文件，或在命令行执行 build-local-release.bat
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\build-local-release.ps1"
