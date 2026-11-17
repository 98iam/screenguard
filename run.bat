@echo off
cd /d "%~dp0"
set "PYW="
if exist "%LOCALAPPDATA%\Programs\Python\Python311\pythonw.exe" set "PYW=%LOCALAPPDATA%\Programs\Python\Python311\pythonw.exe"
if not defined PYW if exist "%LOCALAPPDATA%\Programs\Python\Python312\pythonw.exe" set "PYW=%LOCALAPPDATA%\Programs\Python\Python312\pythonw.exe"
if not defined PYW for %%P in (pythonw.exe) do set "PYW=%%~$PATH:P"
if defined PYW (
    start "" "%PYW%" "%~dp0app\main.py"
) else if exist "%~dp0ScreenGuard.exe" (
    start "" "%~dp0ScreenGuard.exe"
) else (
    python "%~dp0app\main.py"
)
