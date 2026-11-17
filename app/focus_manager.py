"""
ScreenGuard Focus Manager - 1-Hour Daily Allowance System
Tracks screen time during focus hours (4:00 AM - 8:00 PM).
Whitelisted websites are 100% UNLIMITED.
All other desktop apps and unlisted websites share a 60-minute daily budget.
Enforces strict mode with no off-switch.
"""

import os
import sys
import time
import json
import threading
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, Callable, List

try:
    import win32gui
    import win32con
    import win32process
    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False

from app.config import ConfigManager, get_config_dir
from app.window_manager import WindowManager, WindowInfo, PROTECTED_PROCESSES


BROWSER_PROCESSES = {
    "chrome.exe",
    "msedge.exe",
    "brave.exe",
    "firefox.exe",
    "opera.exe",
    "operagx.exe",
    "vivaldi.exe",
    "arc.exe",
    "waterfox.exe"
}


def get_focus_day() -> str:
    """Returns the current focus day string (resets at 4:00 AM)."""
    now = datetime.now()
    focus_date = (now - timedelta(hours=4)).date()
    return focus_date.isoformat()


def is_focus_hours() -> bool:
    """Focus hours are 4:00 AM to 8:00 PM (04:00 <= hour < 20:00)."""
    now = datetime.now()
    return 4 <= now.hour < 20


def format_detailed_time(seconds: int) -> str:
    """Formats seconds into readable minutes and seconds."""
    sec = max(0, seconds)
    hours = sec // 3600
    minutes = (sec % 3600) // 60
    rem_secs = sec % 60
    if hours > 0 and minutes > 0:
        return f"{hours}h {minutes}m"
    elif hours > 0:
        return f"{hours}h"
    elif minutes > 0:
        return f"{minutes}m {rem_secs}s"
    else:
        return f"{rem_secs}s"


class FocusManager:
    """Manages the 1-hour daily allowance for Windows apps and non-whitelisted websites."""

    DEFAULT_BUDGET_MINUTES = 60  # 1 Hour

    def __init__(self, config: ConfigManager, window_mgr: WindowManager):
        self.config = config
        self.window_mgr = window_mgr
        self.budget_minutes = self.DEFAULT_BUDGET_MINUTES
        self.used_seconds = 0
        self._unflushed_seconds = 0
        self._last_save_time = time.time()
        self._last_tick_time = 0.0

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._listeners: List[Callable[[int, int, bool], None]] = []
        self._last_blocked_hwnd = 0
        self._last_block_alert_time = 0.0

        # Browser extension real-time tab tracking
        self._last_browser_report_time = 0.0
        self._active_browser_domain = ""
        self._active_browser_url = ""
        self._browser_is_focused = False

        self._load_state()

    def _get_data_file_path(self) -> str:
        data_dir = get_config_dir()
        return os.path.join(data_dir, "focus_allowance.json")

    def _load_state(self) -> None:
        """Loads used seconds for today's focus day."""
        file_path = self._get_data_file_path()
        today_key = get_focus_day()
        self.used_seconds = 0

        if os.path.exists(file_path):
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if data.get("date") == today_key:
                        self.used_seconds = int(data.get("used_seconds", 0))
            except Exception:
                self.used_seconds = 0

    def _save_state(self) -> None:
        """Persists used seconds to disk."""
        file_path = self._get_data_file_path()
        today_key = get_focus_day()
        try:
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump({
                    "date": today_key,
                    "used_seconds": self.used_seconds,
                    "budget_minutes": self.budget_minutes,
                    "last_updated": datetime.now().isoformat()
                }, f, indent=2)
        except Exception:
            pass

    def add_listener(self, callback: Callable[[int, int, bool], None]) -> None:
        """Adds a listener callback: (remaining_seconds, used_seconds, in_focus_hours)."""
        if callback not in self._listeners:
            self._listeners.append(callback)

    def remove_listener(self, callback: Callable[[int, int, bool], None]) -> None:
        if callback in self._listeners:
            self._listeners.remove(callback)

    def _notify_listeners(self) -> None:
        remaining = self.get_remaining_seconds()
        in_focus = is_focus_hours()
        for cb in self._listeners:
            try:
                cb(remaining, self.used_seconds, in_focus)
            except Exception:
                pass

    def get_remaining_seconds(self) -> int:
        total_seconds = self.budget_minutes * 60
        return max(0, total_seconds - self.used_seconds)

    def is_time_up(self) -> bool:
        return is_focus_hours() and self.get_remaining_seconds() <= 0

    def is_domain_or_url_whitelisted(self, domain: str = "", url: str = "") -> bool:
        """Determines if a given domain or URL is in the allowed study whitelist."""
        dom_lower = (domain or "").strip().lower()
        if dom_lower.startswith("www."):
            dom_lower = dom_lower[4:]
        url_lower = (url or "").strip().lower()

        if not dom_lower and not url_lower:
            return False

        # Browser internal pages are never restricted
        for prefix in ("chrome://", "edge://", "brave://", "about:", "chrome-extension://", "devtools://"):
            if url_lower.startswith(prefix):
                return True

        req_host = dom_lower
        req_path = ""
        if url_lower:
            try:
                from urllib.parse import urlparse
                parsed = urlparse(url_lower)
                if parsed.netloc:
                    req_host = parsed.netloc.split(":")[0].lower().replace("www.", "")
                req_path = parsed.path.lower().rstrip("/")
            except Exception:
                pass

        sites = getattr(self.config, "whitelisted_sites", [])

        for site in sites:
            site_clean = site.strip().lower()
            if not site_clean:
                continue

            target_host = site_clean
            target_path = ""
            if "://" in site_clean or "/" in site_clean:
                target_str = site_clean if "://" in site_clean else ("https://" + site_clean)
                try:
                    from urllib.parse import urlparse
                    p = urlparse(target_str)
                    target_host = p.netloc.split(":")[0].replace("www.", "").strip()
                    target_path = p.path.rstrip("/")
                except Exception:
                    pass
            else:
                target_host = site_clean.replace("www.", "").strip()

            if not target_host:
                continue

            # Exact host match or proper subdomain match
            # e.g. target_host="deepseek.com" matches req_host="chat.deepseek.com"
            # target_host="chat.deepseek.com" DOES NOT match req_host="chat.qwen.ai"
            host_matches = (req_host == target_host) or req_host.endswith("." + target_host)

            if host_matches:
                if target_path:
                    if req_path.startswith(target_path):
                        return True
                else:
                    return True

        return False

    def report_browser_tab(self, domain: str, url: str, is_focused: bool) -> dict:
        """Called by browser extension via local API to report current active tab."""
        self._last_browser_report_time = time.time()
        self._active_browser_domain = (domain or "").strip().lower()
        self._active_browser_url = (url or "").strip().lower()
        self._browser_is_focused = bool(is_focused)

        is_whitelisted = self.is_domain_or_url_whitelisted(self._active_browser_domain, self._active_browser_url)
        time_up = self.is_time_up()

        return {
            "status": "ok",
            "is_whitelisted": is_whitelisted,
            "is_time_up": time_up and not is_whitelisted,
            "remaining_seconds": self.get_remaining_seconds(),
            "used_seconds": self.used_seconds,
            "in_focus_hours": is_focus_hours(),
            "active_domain": self._active_browser_domain
        }

    def get_status_dict(self) -> dict:
        """Returns comprehensive status dictionary for API clients and browser extensions."""
        remaining = self.get_remaining_seconds()
        in_focus = is_focus_hours()
        time_up = self.is_time_up()
        whitelisted = self.is_domain_or_url_whitelisted(self._active_browser_domain, self._active_browser_url)
        active_win = self.window_mgr.get_foreground_window_info()
        draining = self.is_window_draining_time(active_win)
        return {
            "status": "ok",
            "remaining_seconds": remaining,
            "used_seconds": self.used_seconds,
            "budget_minutes": self.budget_minutes,
            "in_focus_hours": in_focus,
            "is_time_up": time_up,
            "active_domain": self._active_browser_domain,
            "active_url": self._active_browser_url,
            "is_whitelisted": whitelisted,
            "whitelisted_sites": getattr(self.config, "whitelisted_sites", []),
            "active_window": active_win.to_dict() if active_win else None,
            "is_draining": draining
        }

    def is_site_whitelisted(self, title_lower: str) -> bool:
        """Determines if the active window title contains any allowed website domain or keyword."""
        if not title_lower:
            return False

        # 1. Check window_mgr site whitelist
        if hasattr(self.window_mgr, "is_site_whitelisted") and self.window_mgr.is_site_whitelisted(title=title_lower):
            return True

        # 2. Check config whitelisted sites directly with clean domain matching
        sites = getattr(self.config, "whitelisted_sites", [])

        for site in sites:
            site_clean = site.strip().lower()
            if not site_clean:
                continue

            target_host = site_clean
            if "://" in target_host:
                target_host = target_host.split("://", 1)[1]
            target_host = target_host.split("/")[0].split("?")[0].replace("www.", "").strip()

            if target_host and target_host in title_lower:
                return True

        return False

    def is_window_draining_time(self, win: Optional[WindowInfo]) -> bool:
        """
        Determines whether the active foreground window drains the daily allowance.
        - Whitelisted websites in any browser are 100% UNLIMITED (False).
        - Core Windows system processes & ScreenGuard are excluded (False).
        - Non-whitelisted websites and general desktop apps drain time (True).
        """
        if not win or not win.hwnd or not win.process_name:
            return False

        proc_lower = win.process_name.lower()
        title_lower = win.title.lower() if win.title else ""

        # System, shell, and ScreenGuard internal safety (never drains time)
        if (
            win.pid == os.getpid()
            or proc_lower in PROTECTED_PROCESSES
            or "screenguard" in proc_lower
            or "screenguard" in title_lower
            or proc_lower in ["shellexperiencehost.exe", "searchhost.exe", "startmenuexperiencehost.exe", "lockapp.exe"]
            or (title_lower and title_lower in ["task switching", "start", "windows input experience", "program manager"])
        ):
            return False

        now = time.time()

        # Browser active: check if current tab/site is whitelisted
        if proc_lower in BROWSER_PROCESSES:
            # 1. Real-time Browser Extension Report (highest priority & accuracy)
            # If the extension reported an active tab within the last 6 seconds:
            if (now - self._last_browser_report_time) < 6.0:
                if self.is_domain_or_url_whitelisted(self._active_browser_domain, self._active_browser_url):
                    return False  # Whitelisted study platform (100% Unlimited!)
                return True  # Distracting / regular website (YouTube, etc.) -> drains time!

            # 2. Browser active without extension: fallback to title matching
            if self.is_site_whitelisted(title_lower):
                return False
            if title_lower in ["new tab", "about:blank", "settings", "extensions"]:
                return False
            return True

        # Any general desktop application (Discord, games, terminal, IDEs, etc.)
        return True

    def block_window_if_limit_reached(self, win: Optional[WindowInfo]) -> None:
        """Minimizes distracting windows and displays an alert when daily allowance is up."""
        if not win or not win.hwnd or not HAS_WIN32:
            return

        now = time.time()
        try:
            # Minimize distracting application
            win32gui.ShowWindow(win.hwnd, win32con.SW_MINIMIZE)

            # Prevent spamming alert dialogs (at most once every 10 seconds)
            if now - self._last_block_alert_time > 10.0:
                self._last_block_alert_time = now
                self._show_limit_notification()
        except Exception:
            pass

    def _show_limit_notification(self) -> None:
        """Displays a clean Windows notification when daily limit is reached."""
        def _notify():
            try:
                import ctypes
                ctypes.windll.user32.MessageBoxW(
                    0,
                    "You have used your 1-hour daily allowance for focus hours (4:00 AM – 8:00 PM).\n\n"
                    "Distracting apps and unlisted sites are locked until 8:00 PM.\n"
                    "Whitelisted work/study sites remain completely accessible.",
                    "Focus Time Limit Reached ⏳",
                    0x00000030 | 0x00040000  # MB_ICONWARNING | MB_TOPMOST
                )
            except Exception:
                pass
        threading.Thread(target=_notify, daemon=True).start()

    def start(self) -> None:
        """Starts the background focus tracking loop."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_loop, name="FocusManagerThread", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stops the focus tracking loop and flushes data."""
        self._running = False
        self._save_state()

    def _run_loop(self) -> None:
        """Background monitoring and time deduction loop."""
        self._last_tick_time = time.time()

        while self._running:
            try:
                now_time = time.time()
                delta = now_time - self._last_tick_time
                self._last_tick_time = now_time

                # Active foreground window info
                active_win = self.window_mgr.get_foreground_window_info()
                in_focus = is_focus_hours()

                # Verify day rollover
                current_day = get_focus_day()
                file_path = self._get_data_file_path()
                if os.path.exists(file_path):
                    try:
                        with open(file_path, "r", encoding="utf-8") as f:
                            data = json.load(f)
                            if data.get("date") != current_day:
                                self.used_seconds = 0
                                self._save_state()
                    except Exception:
                        pass

                if in_focus:
                    if self.is_time_up():
                        # Time is up: block any distracting window
                        if self.is_window_draining_time(active_win):
                            self.block_window_if_limit_reached(active_win)
                    else:
                        # Time remains: check if current window drains time
                        if self.is_window_draining_time(active_win):
                            elapsed_sec = int(round(delta))
                            if elapsed_sec > 0:
                                valid_delta = min(elapsed_sec, 5)
                                self.used_seconds += valid_delta
                                self._unflushed_seconds += valid_delta

                                # Save to disk every 10 seconds or 15 seconds elapsed
                                if self._unflushed_seconds >= 10 or (now_time - self._last_save_time >= 15.0):
                                    self._unflushed_seconds = 0
                                    self._last_save_time = now_time
                                    self._save_state()

                                # Check if limit reached
                                if self.is_time_up():
                                    self.block_window_if_limit_reached(active_win)

                self._notify_listeners()
            except Exception as e:
                print(f"[FocusManager] Error in loop: {e}")

            time.sleep(1.0)  # Smooth 1-second live countdown
