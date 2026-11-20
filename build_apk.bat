@echo off
title ScreenGuard Android Build & Google Drive Sync
echo ========================================================
echo       Building ScreenGuard Child & Parent APKs
echo ========================================================
echo.

cd /d "%~dp0android"
call gradlew.bat assembleChildDebug assembleParentDebug --no-configuration-cache

if %ERRORLEVEL% equ 0 (
    copy /Y "app\build\outputs\apk\child\debug\app-child-debug.apk" "..\ScreenGuard-Child.apk" >nul
    copy /Y "app\build\outputs\apk\parent\debug\app-parent-debug.apk" "..\ScreenGuard-Parent.apk" >nul
    echo.
    echo ========================================================
    echo   BUILD SUCCESSFUL!
    echo   Generated: ScreenGuard-Child.apk
    echo   Generated: ScreenGuard-Parent.apk
    echo ========================================================
    echo.
    echo Syncing to Google Drive...
    powershell.exe -ExecutionPolicy Bypass -File "%~dp0push_apks_to_gdrive.ps1"
) else (
    echo.
    echo [ERROR] Android build failed. Check error logs above.
)

pause
