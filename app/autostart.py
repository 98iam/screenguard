"""
ScreenGuard Windows Startup Manager
Configures launch-at-login via Windows Registry HKCU\Software\Microsoft\Windows\CurrentVersion\Run.
"""

import sys
import os
import winreg
from typing import Optional

RUN_KEY_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_NAME = "ScreenGuard"


def get_executable_command() -> str:
    """Returns the command to run ScreenGuard."""
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --minimized'
    else:
        # Running via python
        py_exe = sys.executable
        main_py = os.path.join(os.path.dirname(os.path.abspath(__file__)), "main.py")
        return f'"{py_exe}" "{main_py}" --minimized'


def is_autostart_enabled() -> bool:
    """Checks if ScreenGuard is registered in Windows startup."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, winreg.KEY_READ) as key:
            val, _ = winreg.QueryValueEx(key, APP_NAME)
            return bool(val)
    except FileNotFoundError:
        return False
    except Exception as e:
        print(f"[Autostart] Query error: {e}")
        return False


def set_autostart(enable: bool) -> bool:
    """Enables or disables ScreenGuard startup with Windows."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, winreg.KEY_SET_VALUE) as key:
            if enable:
                cmd = get_executable_command()
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, cmd)
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
        return True
    except Exception as e:
        print(f"[Autostart] Set error: {e}")
        return False
