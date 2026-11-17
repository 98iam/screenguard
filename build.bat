@echo off
title ScreenGuard Build Script
echo ========================================================
echo           Building ScreenGuard Standalone EXE
echo ========================================================
echo.

where pyinstaller >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Installing PyInstaller...
    python -m pip install pyinstaller
)

echo Building dist/ScreenGuard.exe ...
python -m PyInstaller --noconfirm --onedir --windowed --name "ScreenGuard" ^
    --add-data "config;config" ^
    --hidden-import "winocr" ^
    --hidden-import "winrt.windows.media.ocr" ^
    --hidden-import "winrt.windows.graphics.imaging" ^
    --hidden-import "winrt.windows.storage.streams" ^
    --hidden-import "winrt.windows.globalization" ^
    --hidden-import "pystray._win32" ^
    --hidden-import "psutil" ^
    --hidden-import "PIL" ^
    --hidden-import "mss" ^
    --hidden-import "keyboard" ^
    app/main.py

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo   BUILD SUCCESSFUL!
    echo   Executable located at: dist\ScreenGuard\ScreenGuard.exe
    echo ========================================================
) else (
    echo.
    echo [ERROR] Build failed. Check errors above.
)

pause
