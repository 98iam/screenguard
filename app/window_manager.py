"""
ScreenGuard Window & Process Manager
Safely identifies foreground applications, checks protection & whitelists,
and executes graceful application close with safety safeguards.
"""

import os
import sys
import time
import ctypes
from typing import Optional, Dict, Any, Tuple, List
import psutil

# Win32 API imports with ctypes fallbacks
try:
    import win32gui
    import win32process
    import win32con
    import win32api
    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False


# Critical Windows System & Core Infrastructure Processes that MUST NEVER be terminated
PROTECTED_PROCESSES = {
    "explorer.exe",
    "csrss.exe",
    "wininit.exe",
    "services.exe",
    "lsass.exe",
    "smss.exe",
    "svchost.exe",
    "dwm.exe",
    "system",
    "system idle process",
    "idle",
    "registry",
    "fontdrvhost.exe",
    "sihost.exe",
    "ctfmon.exe",
    "taskhostw.exe",
    "screenguard.exe",
    "taskmgr.exe",
    "searchapp.exe",
    "shellexperiencehost.exe",
    "startmenuexperiencehost.exe"
}


class WindowInfo:
    """Represents metadata of a detected window and its process."""
    def __init__(
        self,
        hwnd: int,
        title: str,
        pid: int,
        process_name: str,
        exe_path: str,
        rect: Tuple[int, int, int, int]
    ):
        self.hwnd = hwnd
        self.title = title
        self.pid = pid
        self.process_name = process_name
        self.exe_path = exe_path
        self.rect = rect  # (left, top, right, bottom)

    @property
    def width(self) -> int:
        return max(0, self.rect[2] - self.rect[0])

    @property
    def height(self) -> int:
        return max(0, self.rect[3] - self.rect[1])

    def to_dict(self) -> Dict[str, Any]:
        return {
            "hwnd": hex(self.hwnd) if self.hwnd else "0x0",
            "title": self.title,
            "pid": self.pid,
            "process_name": self.process_name,
            "exe_path": self.exe_path,
            "rect": self.rect
        }

    def __repr__(self) -> str:
        return f"<WindowInfo title='{self.title[:30]}' proc='{self.process_name}' pid={self.pid} hwnd={hex(self.hwnd)}>"


class WindowManager:
    """Manages active window discovery and safe closing mechanisms."""

    def __init__(self, custom_whitelist: Optional[List[str]] = None, custom_site_whitelist: Optional[List[str]] = None):
        self.custom_whitelist = [w.lower() for w in (custom_whitelist or [])]
        self.custom_site_whitelist = [s.lower() for s in (custom_site_whitelist or [])]

    def set_whitelist(self, whitelist: List[str], site_whitelist: Optional[List[str]] = None) -> None:
        self.custom_whitelist = [w.lower() for w in whitelist]
        if site_whitelist is not None:
            self.custom_site_whitelist = [s.lower() for s in site_whitelist if s]

    def set_site_whitelist(self, site_whitelist: List[str]) -> None:
        """Updates the list of allowed websites/domains."""
        self.custom_site_whitelist = [s.lower() for s in site_whitelist if s]

    def is_site_whitelisted(self, title: str = "", ocr_text: str = "") -> bool:
        """Check if window title or visible text matches an allowed website/domain."""
        title_lower = title.lower() if title else ""
        ocr_lower = ocr_text.lower() if ocr_text else ""

        for site in self.custom_site_whitelist:
            site_clean = site.strip().lower()
            if not site_clean:
                continue

            # Extract clean host (e.g. chat.deepseek.com from https://chat.deepseek.com/abc)
            host = site_clean
            if "://" in host:
                host = host.split("://", 1)[1]
            host = host.split("/")[0].split("?")[0].replace("www.", "").strip()

            if host and (host in title_lower or (ocr_lower and host in ocr_lower)):
                return True

        return False

    def is_protected_process(self, process_name: str, exe_path: str = "") -> bool:
        """Check if process is in the critical system safety list."""
        p_name = process_name.lower()
        if p_name in PROTECTED_PROCESSES:
            return True
        if "screenguard" in p_name:
            return True
        if exe_path:
            base_name = os.path.basename(exe_path).lower()
            if base_name in PROTECTED_PROCESSES:
                return True
        return False

    def is_whitelisted(self, process_name: str, exe_path: str = "", title: str = "") -> bool:
        """Check if application is protected or user-whitelisted (System apps only, NOT websites)."""
        if self.is_protected_process(process_name, exe_path):
            return True
        
        p_name = process_name.lower()
        base_name = os.path.basename(exe_path).lower() if exe_path else ""
        title_lower = title.lower()

        for item in self.custom_whitelist:
            item_clean = item.strip().lower()
            if not item_clean:
                continue
            if item_clean == p_name or item_clean == base_name or item_clean in title_lower:
                return True
            if item_clean.endswith(".exe") and (item_clean == p_name or item_clean == base_name):
                return True
            if item_clean in p_name:
                return True

        return False

    def get_foreground_window_info(self) -> Optional[WindowInfo]:
        """Retrieve details of the currently active foreground window."""
        try:
            if HAS_WIN32:
                hwnd = win32gui.GetForegroundWindow()
                if not hwnd or not win32gui.IsWindow(hwnd):
                    return None
                
                # Check if visible
                if not win32gui.IsWindowVisible(hwnd):
                    return None

                title = win32gui.GetWindowText(hwnd)
                _, pid = win32process.GetWindowThreadProcessId(hwnd)
                rect = win32gui.GetWindowRect(hwnd)
            else:
                # Ctypes fallback
                user32 = ctypes.windll.user32
                hwnd = user32.GetForegroundWindow()
                if not hwnd:
                    return None
                
                length = user32.GetWindowTextLengthW(hwnd)
                buff = ctypes.create_unicode_buffer(length + 1)
                user32.GetWindowTextW(hwnd, buff, length + 1)
                title = buff.value

                pid = ctypes.c_ulong()
                user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                pid = pid.value

                class RECT(ctypes.Structure):
                    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                                ("right", ctypes.c_long), ("bottom", ctypes.c_long)]
                r = RECT()
                user32.GetWindowRect(hwnd, ctypes.byref(r))
                rect = (r.left, r.top, r.right, r.bottom)

            process_name = "Unknown"
            exe_path = ""
            if pid > 0:
                try:
                    proc = psutil.Process(pid)
                    process_name = proc.name()
                    try:
                        exe_path = proc.exe()
                    except (psutil.AccessDenied, psutil.NoSuchProcess):
                        exe_path = ""
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    process_name = "System"

            return WindowInfo(
                hwnd=hwnd,
                title=title or process_name,
                pid=pid,
                process_name=process_name,
                exe_path=exe_path,
                rect=rect
            )
        except Exception as e:
            return None

    def get_active_window(self) -> Optional[WindowInfo]:
        """Alias for get_foreground_window_info."""
        return self.get_foreground_window_info()

    def close_window(self, window_info: WindowInfo, force_terminate: bool = False, timeout_ms: int = 500) -> Tuple[bool, str]:
        """
        Closes the specified window.
        First sends WM_CLOSE gracefully.
        If force_terminate is True and process survives timeout, force kills (unless protected).
        """
        if not window_info or not window_info.hwnd:
            return False, "Invalid window info"

        # Safety Check: Never close protected processes
        if self.is_protected_process(window_info.process_name, window_info.exe_path):
            return False, f"Refused to close protected process '{window_info.process_name}'"

        # 1. Graceful close via WM_CLOSE
        graceful_sent = False
        try:
            if HAS_WIN32:
                # Post WM_CLOSE message
                win32gui.PostMessage(window_info.hwnd, win32con.WM_CLOSE, 0, 0)
                graceful_sent = True
            else:
                user32 = ctypes.windll.user32
                WM_CLOSE = 0x0010
                user32.PostMessageW(window_info.hwnd, WM_CLOSE, 0, 0)
                graceful_sent = True
        except Exception as e:
            pass

        # Wait a short duration to verify if window closes
        wait_sec = max(0.05, timeout_ms / 1000.0)
        time.sleep(min(0.2, wait_sec))

        # Check if process/window is still valid
        window_alive = False
        try:
            if HAS_WIN32:
                window_alive = bool(win32gui.IsWindow(window_info.hwnd))
            else:
                window_alive = bool(ctypes.windll.user32.IsWindow(window_info.hwnd))
        except Exception:
            window_alive = False

        if not window_alive:
            return True, f"Gracefully closed {window_info.process_name} (PID {window_info.pid})"

        # If window is still alive and force_terminate is requested
        if force_terminate and window_info.pid > 0:
            time.sleep(max(0.0, wait_sec - 0.2))
            try:
                if psutil.pid_exists(window_info.pid):
                    proc = psutil.Process(window_info.pid)
                    # Double check it's not a protected system process
                    if not self.is_protected_process(proc.name()):
                        proc.terminate()
                        try:
                            proc.wait(timeout=0.3)
                        except psutil.TimeoutExpired:
                            proc.kill()
                        return True, f"Force terminated {window_info.process_name} (PID {window_info.pid})"
            except (psutil.NoSuchProcess, psutil.AccessDenied) as e:
                return True, f"Process {window_info.process_name} ended ({e})"
            except Exception as e:
                return False, f"Force close failed: {e}"

        return graceful_sent, f"Sent close signal to {window_info.process_name} (PID {window_info.pid})"
