@echo off
title ScreenGuard Android Build Script
echo ========================================================
echo           Building ScreenGuard Android APK
echo ========================================================
echo.

cd /d "%~dp0android"
call gradlew.bat assembleDebug

if %ERRORLEVEL% equ 0 (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "..\ScreenGuard.apk" >nul
    echo.
    echo ========================================================
    echo   BUILD SUCCESSFUL!
    echo   APK output: ScreenGuard.apk
    echo ========================================================
) else (
    echo.
    echo [ERROR] Android build failed. Check error logs above.
)

pause
