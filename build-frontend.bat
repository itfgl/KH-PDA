@echo off
setlocal
chcp 65001 >nul

cd /d "%~dp0"

echo [1/3] Building frontend assets into www/ ...
call npm run build
if errorlevel 1 (
    echo [ERROR] Frontend build failed.
    exit /b 1
)

echo.
echo [2/3] Verifying generated files ...
if not exist "www\index.html" (
    echo [ERROR] www\index.html not found.
    exit /b 1
)
if not exist "www\plugins.js" (
    echo [ERROR] www\plugins.js not found.
    exit /b 1
)

echo.
echo [3/3] Frontend build complete.
echo Output:
echo   %CD%\www\index.html
echo   %CD%\www\plugins.js

endlocal
