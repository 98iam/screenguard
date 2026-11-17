@echo off
cd /d "%~dp0"
if exist "C:\Users\hzran\AppData\Local\Programs\Python\Python311\pythonw.exe" (
    start "" "C:\Users\hzran\AppData\Local\Programs\Python\Python311\pythonw.exe" "%~dp0app\main.py"
) else (
    start "" "%~dp0ScreenGuard.exe"
)
