"""
Unit tests for Window Manager Safety Rules
"""

import pytest
from app.window_manager import WindowManager, PROTECTED_PROCESSES


def test_protected_process_detection():
    wm = WindowManager()

    # System processes must always be protected
    for proc in ["explorer.exe", "csrss.exe", "wininit.exe", "services.exe", "lsass.exe", "dwm.exe", "screenguard.exe"]:
        assert wm.is_protected_process(proc) is True
        assert wm.is_whitelisted(proc) is True

    # User applications should NOT be protected by default
    assert wm.is_protected_process("chrome.exe") is False
    assert wm.is_protected_process("firefox.exe") is False
    assert wm.is_protected_process("notepad.exe") is False
    assert wm.is_protected_process("WINWORD.EXE") is False


def test_custom_whitelist():
    wm = WindowManager(["notepad.exe", "calculator.exe"])

    assert wm.is_whitelisted("notepad.exe") is True
    assert wm.is_whitelisted("NOTEPAD.EXE") is True
    assert wm.is_whitelisted("calculator.exe") is True
    assert wm.is_whitelisted("chrome.exe") is False
