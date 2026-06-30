@echo off
setlocal
chcp 65001 >nul

set REMOTE_USER=root
set REMOTE_HOST=115.29.178.34
set REMOTE_PATH=/root/scanner/server/h5
set REMOTE_PORT=22

set LOCAL_STATIC=..\server\h5

echo [1/4] Building web assets and syncing Android project ...
call npm run sync
if errorlevel 1 (
    echo [ERROR] Build or Android sync failed.
    pause & exit /b 1
)

echo.
echo [2/4] Copying to local server\static ...
if not exist "%LOCAL_STATIC%" (
    echo [ERROR] %LOCAL_STATIC% not found.
    pause & exit /b 1
)
copy /Y www\index.html "%LOCAL_STATIC%\index.html"
copy /Y www\plugins.js  "%LOCAL_STATIC%\plugins.js"
echo Copied: index.html + plugins.js

echo.
echo [3/4] Verifying Android asset timestamps ...
dir /T:W android\app\src\main\assets\public\index.html
dir /T:W android\app\src\main\assets\public\plugins.js

echo.
echo [4/4] Pushing to remote %REMOTE_HOST% ...
if "%REMOTE_HOST%"=="" (
    echo [SKIP] REMOTE_HOST not set.
    goto done
)

where scp >nul 2>&1
if errorlevel 1 (
    echo [WARN] scp not found. Upload manually:
    echo   www\index.html -^> %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%/index.html
    echo   www\plugins.js  -^> %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%/plugins.js
    goto done
)

scp -P %REMOTE_PORT% www\index.html %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%/index.html
scp -P %REMOTE_PORT% www\plugins.js  %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%/plugins.js
if errorlevel 1 (
    echo [ERROR] Remote push failed.
    pause & exit /b 1
)
echo Remote push done.

:done
echo.
echo ============================================================
echo   Deploy complete!
echo   Local : %LOCAL_STATIC%\index.html + plugins.js
if not "%REMOTE_HOST%"=="" echo   Remote: %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%
echo ============================================================
pause

