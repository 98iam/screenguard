"""
ScreenGuard Logging & Detection History System
Lightweight, thread-safe logger with in-memory buffer for GUI and disk persistence.
"""

import os
import sys
import threading
import time
from datetime import datetime
from typing import Callable, List, Optional, Dict, Any


def get_log_dir() -> str:
    if getattr(sys, 'frozen', False):
        base_dir = os.path.dirname(sys.executable)
    else:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    log_dir = os.path.join(base_dir, "logs")
    os.makedirs(log_dir, exist_ok=True)
    return log_dir


class DetectionEvent:
    """Represents a single blocked-word detection and action event."""
    def __init__(self, blocked_word: str, app_name: str, pid: int, action: str, window_title: str):
        self.timestamp = datetime.now()
        self.time_str = self.timestamp.strftime("%H:%M:%S")
        self.blocked_word = blocked_word
        self.app_name = app_name
        self.pid = pid
        self.action = action
        self.window_title = window_title

    def to_dict(self) -> Dict[str, Any]:
        return {
            "time": self.time_str,
            "blocked_word": self.blocked_word,
            "app_name": self.app_name,
            "pid": self.pid,
            "action": self.action,
            "window_title": self.window_title
        }

    def __str__(self) -> str:
        return f"[{self.time_str}] Detected: '{self.blocked_word}' | App: {self.app_name} (PID: {self.pid}) | Action: {self.action}"


class Logger:
    """Thread-safe event and detection logger."""
    
    def __init__(self, log_file: Optional[str] = None, max_history: int = 300):
        self.log_file = log_file or os.path.join(get_log_dir(), "screenguard.log")
        self.max_history = max_history
        self._lock = threading.RLock()
        self._history: List[DetectionEvent] = []
        self._general_logs: List[str] = []
        self._listeners: List[Callable[[str, Optional[DetectionEvent]], None]] = []

    def add_listener(self, listener: Callable[[str, Optional[DetectionEvent]], None]) -> None:
        with self._lock:
            if listener not in self._listeners:
                self._listeners.append(listener)

    def remove_listener(self, listener: Callable[[str, Optional[DetectionEvent]], None]) -> None:
        with self._lock:
            if listener in self._listeners:
                self._listeners.remove(listener)

    def _notify(self, message: str, event: Optional[DetectionEvent] = None) -> None:
        for cb in list(self._listeners):
            try:
                cb(message, event)
            except Exception as e:
                print(f"[Logger] Listener error: {e}")

    def log(self, message: str, level: str = "INFO") -> None:
        now_str = datetime.now().strftime("%H:%M:%S")
        entry = f"[{now_str}] [{level}] {message}"
        with self._lock:
            self._general_logs.append(entry)
            if len(self._general_logs) > self.max_history:
                self._general_logs.pop(0)
            
            # Append to file
            try:
                with open(self.log_file, "a", encoding="utf-8") as f:
                    f.write(entry + "\n")
            except Exception as e:
                print(f"[Logger] File write failed: {e}")
                
        self._notify(entry, None)

    def log_detection(self, blocked_word: str, app_name: str, pid: int, action: str, window_title: str = "") -> DetectionEvent:
        event = DetectionEvent(blocked_word, app_name, pid, action, window_title)
        entry_str = str(event)
        with self._lock:
            self._history.insert(0, event)
            if len(self._history) > self.max_history:
                self._history.pop()
            
            self._general_logs.append(entry_str)
            if len(self._general_logs) > self.max_history:
                self._general_logs.pop(0)

            try:
                with open(self.log_file, "a", encoding="utf-8") as f:
                    f.write(entry_str + "\n")
            except Exception as e:
                print(f"[Logger] File write failed: {e}")

        self._notify(entry_str, event)
        return event

    def get_history(self) -> List[DetectionEvent]:
        with self._lock:
            return list(self._history)

    def get_general_logs(self) -> List[str]:
        with self._lock:
            return list(self._general_logs)

    def clear_history(self) -> None:
        with self._lock:
            self._history.clear()
            self._general_logs.clear()
            try:
                if os.path.exists(self.log_file):
                    with open(self.log_file, "w", encoding="utf-8") as f:
                        f.write(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] Log cleared\n")
            except Exception as e:
                print(f"[Logger] Clear file error: {e}")
        self._notify("[Log cleared]", None)


# Global default logger instance
default_logger = Logger()
