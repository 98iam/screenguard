"""
ScreenGuard Configuration Manager
Handles persistent settings, blocked words, whitelists, and monitoring preferences.
"""

import json
import os
import sys
import threading
from typing import Any, Dict, List, Optional, Callable


DEFAULT_CONFIG: Dict[str, Any] = {
    "enabled": True,
    "scan_interval_ms": 400,
    "ocr_confidence": 50,  # 0 - 100
    "match_mode": "exact_word",  # "exact_word" | "substring"
    "monitor_mode": "active_window",  # "active_window" | "selected_apps" | "all_user_apps"
    "action": "graceful_close",  # "graceful_close" | "force_close"
    "force_terminate": False,
    "force_terminate_timeout_ms": 500,
    "consecutive_detection": False,
    "selected_monitor": 0,  # 0 = active window monitor, 1..N = specific monitor
    "hotkey": "Ctrl+Shift+F10",
    "start_with_windows": False,
    "blocked_words": [
        "TEST_BLOCKED_WORD",
        "confidential_leak",
        "secret_token"
    ],
    "whitelist": [
        "ScreenGuard.exe",
        "ScreenGuard",
        "explorer.exe",
        "Taskmgr.exe"
    ],
    "monitored_apps": [],
    "whitelisted_sites": [
        "chatgpt.com",
        "claude.ai",
        "github.com",
        "google.com",
        "stackoverflow.com",
        "wikipedia.org",
        "docs.google.com"
    ]
}


def get_config_dir() -> str:
    """Get the appropriate config directory depending on portable vs installed mode."""
    # If running as PyInstaller bundle or local development
    if getattr(sys, 'frozen', False):
        base_dir = os.path.dirname(sys.executable)
    else:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    config_dir = os.path.join(base_dir, "config")
    os.makedirs(config_dir, exist_ok=True)
    return config_dir


def get_config_path() -> str:
    return os.path.join(get_config_dir(), "settings.json")


class ConfigManager:
    """Thread-safe configuration manager."""
    
    def __init__(self, config_path: Optional[str] = None):
        self.config_path = config_path or get_config_path()
        self._lock = threading.RLock()
        self._config: Dict[str, Any] = {}
        self._callbacks: List[Callable[[Dict[str, Any]], None]] = []
        self.load()

    def register_callback(self, callback: Callable[[Dict[str, Any]], None]) -> None:
        """Register a callback for configuration updates."""
        with self._lock:
            if callback not in self._callbacks:
                self._callbacks.append(callback)

    def unregister_callback(self, callback: Callable[[Dict[str, Any]], None]) -> None:
        with self._lock:
            if callback in self._callbacks:
                self._callbacks.remove(callback)

    def _notify(self) -> None:
        """Notify registered listeners of changes."""
        config_copy = self.get_all()
        for cb in list(self._callbacks):
            try:
                cb(config_copy)
            except Exception as e:
                print(f"[Config] Error in callback: {e}")

    def load(self) -> Dict[str, Any]:
        """Load configuration from disk with fallback to defaults."""
        with self._lock:
            self._config = DEFAULT_CONFIG.copy()
            if os.path.exists(self.config_path):
                try:
                    with open(self.config_path, "r", encoding="utf-8") as f:
                        data = json.load(f)
                        if isinstance(data, dict):
                            self._config.update(data)
                except Exception as e:
                    print(f"[Config] Failed to load {self.config_path}, using defaults: {e}")
            else:
                self.save()
            return self._config.copy()

    def save(self) -> bool:
        """Save configuration to disk."""
        with self._lock:
            try:
                os.makedirs(os.path.dirname(self.config_path), exist_ok=True)
                with open(self.config_path, "w", encoding="utf-8") as f:
                    json.dump(self._config, f, indent=4, ensure_ascii=False)
                return True
            except Exception as e:
                print(f"[Config] Failed to save {self.config_path}: {e}")
                return False

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._config.get(key, default)

    def get_all(self) -> Dict[str, Any]:
        with self._lock:
            return self._config.copy()

    def set(self, key: str, value: Any, auto_save: bool = True) -> None:
        with self._lock:
            self._config[key] = value
            if auto_save:
                self.save()
        self._notify()

    def update(self, new_data: Dict[str, Any], auto_save: bool = True) -> None:
        with self._lock:
            self._config.update(new_data)
            if auto_save:
                self.save()
        self._notify()

    # Convenience accessors
    @property
    def is_enabled(self) -> bool:
        return bool(self.get("enabled", True))

    @property
    def scan_interval_ms(self) -> int:
        return max(50, int(self.get("scan_interval_ms", 400)))

    @property
    def ocr_confidence(self) -> int:
        return int(self.get("ocr_confidence", 50))

    @property
    def match_mode(self) -> str:
        return str(self.get("match_mode", "exact_word"))

    @property
    def monitor_mode(self) -> str:
        return str(self.get("monitor_mode", "active_window"))

    @property
    def action(self) -> str:
        return str(self.get("action", "graceful_close"))

    @property
    def force_terminate(self) -> bool:
        return bool(self.get("force_terminate", False))

    @property
    def force_terminate_timeout_ms(self) -> int:
        return int(self.get("force_terminate_timeout_ms", 500))

    @property
    def consecutive_detection(self) -> bool:
        return bool(self.get("consecutive_detection", False))

    @property
    def blocked_words(self) -> List[str]:
        return list(self.get("blocked_words", []))

    @property
    def whitelist(self) -> List[str]:
        return list(self.get("whitelist", []))

    def add_blocked_word(self, word: str) -> bool:
        cleaned = word.strip()
        if not cleaned:
            return False
        with self._lock:
            words = list(self.get("blocked_words", []))
            if cleaned not in words:
                words.append(cleaned)
                self.set("blocked_words", words, auto_save=True)
                return True
        return False

    def remove_blocked_word(self, word: str) -> bool:
        with self._lock:
            words = list(self.get("blocked_words", []))
            if word in words:
                words.remove(word)
                self.set("blocked_words", words, auto_save=True)
                return True
        return False

    def edit_blocked_word(self, old_word: str, new_word: str) -> bool:
        cleaned = new_word.strip()
        if not cleaned:
            return False
        with self._lock:
            words = list(self.get("blocked_words", []))
            if old_word in words:
                idx = words.index(old_word)
                words[idx] = cleaned
                self.set("blocked_words", words, auto_save=True)
                return True
        return False

    def add_whitelist_app(self, app_name: str) -> bool:
        cleaned = app_name.strip()
        if not cleaned:
            return False
        with self._lock:
            apps = list(self.get("whitelist", []))
            if cleaned.lower() not in [a.lower() for a in apps]:
                apps.append(cleaned)
                self.set("whitelist", apps, auto_save=True)
                return True
        return False

    def remove_whitelist_app(self, app_name: str) -> bool:
        with self._lock:
            apps = list(self.get("whitelist", []))
            for a in apps:
                if a.lower() == app_name.strip().lower():
                    apps.remove(a)
                    self.set("whitelist", apps, auto_save=True)
                    return True
        return False


    @property
    def whitelisted_sites(self) -> List[str]:
        return list(self.get("whitelisted_sites", []))

    def add_whitelisted_site(self, site: str) -> bool:
        cleaned = site.strip()
        if not cleaned:
            return False
        with self._lock:
            sites = list(self.get("whitelisted_sites", []))
            if cleaned.lower() not in [s.lower() for s in sites]:
                sites.append(cleaned)
                self.set("whitelisted_sites", sites, auto_save=True)
                return True
        return False

    def remove_whitelisted_site(self, site: str) -> bool:
        with self._lock:
            sites = list(self.get("whitelisted_sites", []))
            for s in list(sites):
                if s.lower() == site.strip().lower():
                    sites.remove(s)
                    self.set("whitelisted_sites", sites, auto_save=True)
                    return True
        return False
