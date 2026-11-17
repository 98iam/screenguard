"""
ScreenGuard Background Monitor Loop
Coordinates screen capture, OCR, word matching, and application termination in a dedicated thread.
Optimized for ultra-low power consumption and zero-latency window title detection.
"""

import time
import threading
import ctypes
from typing import Optional, Dict, Any, Callable
import psutil
from PIL import Image

class _LASTINPUTINFO(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

def get_system_idle_seconds() -> float:
    """Returns number of seconds since last keyboard or mouse input."""
    try:
        lii = _LASTINPUTINFO()
        lii.cbSize = ctypes.sizeof(_LASTINPUTINFO)
        if ctypes.windll.user32.GetLastInputInfo(ctypes.byref(lii)):
            millis = ctypes.windll.kernel32.GetTickCount() - lii.dwTime
            return max(0.0, millis / 1000.0)
    except Exception:
        pass
    return 0.0

from app.config import ConfigManager
from app.capture import ScreenCapture
from app.ocr import OCREngine
from app.detector import Detector
from app.window_manager import WindowManager, WindowInfo
from app.logger import Logger, default_logger


class MonitorStats:
    """Live performance and operation statistics."""
    def __init__(self):
        self.total_scans: int = 0
        self.detections_count: int = 0
        self.apps_closed_count: int = 0
        self.avg_scan_latency_ms: float = 0.0
        self.cpu_percent: float = 0.0
        self.memory_mb: float = 0.0
        self.active_window_title: str = "None"
        self.active_app_name: str = "None"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_scans": self.total_scans,
            "detections_count": self.detections_count,
            "apps_closed_count": self.apps_closed_count,
            "avg_scan_latency_ms": round(self.avg_scan_latency_ms, 1),
            "cpu_percent": round(self.cpu_percent, 1),
            "memory_mb": round(self.memory_mb, 1),
            "active_window_title": self.active_window_title,
            "active_app_name": self.active_app_name
        }


class Monitor:
    """Core background monitoring engine."""

    def __init__(self, config: ConfigManager, logger: Optional[Logger] = None):
        self.config = config
        self.logger = logger or default_logger
        self.capture = ScreenCapture()
        self.ocr = OCREngine()
        self.detector = Detector()
        self.window_mgr = WindowManager(
            self.config.whitelist,
            custom_site_whitelist=self.config.whitelisted_sites
        )
        self.stats = MonitorStats()

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._process = psutil.Process()
        self._stats_callbacks: list[Callable[[MonitorStats], None]] = []

        # Sync whitelist updates from config
        self.config.register_callback(self._on_config_changed)

    def _on_config_changed(self, new_config: Dict[str, Any]) -> None:
        self.window_mgr.set_whitelist(
            new_config.get("whitelist", []),
            site_whitelist=new_config.get("whitelisted_sites", [])
        )

    def add_stats_listener(self, cb: Callable[[MonitorStats], None]) -> None:
        if cb not in self._stats_callbacks:
            self._stats_callbacks.append(cb)

    def remove_stats_listener(self, cb: Callable[[MonitorStats], None]) -> None:
        if cb in self._stats_callbacks:
            self._stats_callbacks.remove(cb)

    def _notify_stats(self) -> None:
        for cb in list(self._stats_callbacks):
            try:
                cb(self.stats)
            except Exception:
                pass

    def start(self) -> None:
        """Starts the background monitoring thread."""
        if self._running:
            return
        self._running = True
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="ScreenGuardMonitorThread", daemon=True)
        self._thread.start()
        self.logger.log("ScreenGuard protection thread started")

    def stop(self) -> None:
        """Stops the background monitoring thread."""
        if not self._running:
            return
        self._running = False
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self.capture.close()
        self.logger.log("ScreenGuard protection thread stopped")

    def is_running(self) -> bool:
        return self._running

    def _update_system_stats(self) -> None:
        """Updates internal memory and CPU usage metrics."""
        try:
            self.stats.cpu_percent = psutil.cpu_percent(interval=None)
            mem_info = self._process.memory_info()
            self.stats.memory_mb = mem_info.rss / (1024 * 1024)
        except Exception:
            pass

    def _run_loop(self) -> None:
        """Main monitoring loop."""
        latencies = []
        last_stats_update = time.time()
        last_frame_hash: Optional[bytes] = None
        last_app_key: Optional[str] = None
        last_rect: Optional[Tuple[int, int, int, int]] = None

        while not self._stop_event.is_set():
            loop_start = time.time()

            # Dynamic battery-aware scan interval calculation
            battery = psutil.sensors_battery()
            is_on_battery = battery is not None and not battery.power_plugged
            base_interval_ms = self.config.scan_interval_ms
            effective_interval_sec = (max(600, base_interval_ms) if is_on_battery else base_interval_ms) / 1000.0

            # 1. If protection is disabled in settings, sleep and skip
            if not self.config.is_enabled:
                self.detector.clear_state()
                last_frame_hash = None
                time.sleep(0.3)
                continue

            # 1.5 Battery Optimization: Sleep when user is idle (reading or away > 35s)
            idle_seconds = get_system_idle_seconds()
            if idle_seconds > 35.0:
                time.sleep(1.5)
                continue

            # 2. Get active foreground window info
            win_info = self.window_mgr.get_foreground_window_info()
            if win_info:
                self.stats.active_window_title = win_info.title[:40]
                self.stats.active_app_name = win_info.process_name
            else:
                self.stats.active_window_title = "None"
                self.stats.active_app_name = "None"

            # Check if foreground application is valid and not whitelisted/protected
            if not win_info or not win_info.process_name:
                time.sleep(effective_interval_sec)
                continue

            # Check whitelist / system protection
            if self.window_mgr.is_whitelisted(win_info.process_name, win_info.exe_path, win_info.title):
                self.detector.clear_state()
                last_frame_hash = None
                time.sleep(effective_interval_sec)
                continue

            # Check monitoring mode
            monitor_mode = self.config.monitor_mode
            if monitor_mode == "selected_apps":
                monitored = [a.lower() for a in self.config.get("monitored_apps", [])]
                if win_info.process_name.lower() not in monitored:
                    time.sleep(effective_interval_sec)
                    continue

            blocked_words = self.config.blocked_words
            match_mode = self.config.match_mode
            app_key = f"{win_info.process_name}:{win_info.pid}"

            # 2.5 Quick check on Window Title (Instant, 0ms latency, zero OCR overhead)
            if win_info.title:
                title_matches = self.detector.find_matches(win_info.title, blocked_words, match_mode=match_mode)
                if title_matches:
                    matched_str = ", ".join(title_matches)
                    self.logger.log(f"BLOCKED WORD DETECTED IN TITLE: '{matched_str}' in {win_info.process_name} (PID: {win_info.pid})", "WARN")

                    if not self.window_mgr.is_protected_process(win_info.process_name, win_info.exe_path):
                        force = self.config.force_terminate
                        timeout = self.config.force_terminate_timeout_ms
                        success, _ = self.window_mgr.close_window(win_info, force_terminate=force, timeout_ms=timeout)
                        
                        self.stats.detections_count += 1
                        if success:
                            self.stats.apps_closed_count += 1

                        self.logger.log_detection(
                            blocked_word=matched_str,
                            app_name=win_info.process_name,
                            pid=win_info.pid,
                            action="Closed" if success else "Close Failed",
                            window_title=win_info.title
                        )
                        last_frame_hash = None
                        time.sleep(0.5)
                        continue

            # 3. Capture Screen (Region or Full Screen)
            scan_t0 = time.time()
            img = None
            if win_info.width > 80 and win_info.height > 80 and self.config.get("selected_monitor", 0) == 0:
                # Optimized capture: only the active window bounding rect
                img = self.capture.capture_region(win_info.rect)

            if img is None:
                # Fallback to full active monitor capture
                img = self.capture.capture_screen(self.config.get("selected_monitor", 0))

            if img is None:
                time.sleep(effective_interval_sec)
                continue

            # Optimization A: Fast Frame-Diff Hash Check
            # If the window content hasn't changed at all, skip OCR entirely to save CPU and battery
            try:
                thumb = img.resize((32, 32), Image.Resampling.BOX).convert("L")
                curr_hash = thumb.tobytes()
            except Exception:
                curr_hash = None

            if (curr_hash is not None and 
                app_key == last_app_key and 
                curr_hash == last_frame_hash and 
                win_info.rect == last_rect):
                # Static screen: user is idle or reading, skip OCR
                self.detector.check_consecutive_confirmation(app_key=app_key, is_detected=False)
                if time.time() - last_stats_update > 1.0:
                    self._update_system_stats()
                    self._notify_stats()
                    last_stats_update = time.time()
                
                duration = time.time() - loop_start
                target_sleep = effective_interval_sec - duration
                time.sleep(max(0.05, target_sleep))
                continue

            last_frame_hash = curr_hash
            last_app_key = app_key
            last_rect = win_info.rect

            # 4. OCR text recognition
            ocr_result = self.ocr.recognize(img)
            
            # 5. Detect blocked words
            matches = self.detector.find_matches(ocr_result.full_text, blocked_words, match_mode=match_mode)

            scan_elapsed = (time.time() - scan_t0) * 1000.0
            latencies.append(scan_elapsed)
            if len(latencies) > 20:
                latencies.pop(0)
            self.stats.avg_scan_latency_ms = sum(latencies) / len(latencies)
            self.stats.total_scans += 1

            if matches:
                # Check consecutive frame confirmation if enabled
                is_confirmed = self.detector.check_consecutive_confirmation(
                    app_key=app_key,
                    is_detected=True,
                    required_consecutive=2 if self.config.consecutive_detection else 1
                )

                if is_confirmed:
                    matched_str = ", ".join(matches)
                    self.logger.log(f"BLOCKED WORD DETECTED: '{matched_str}' in {win_info.process_name} (PID: {win_info.pid})", "WARN")

                    # Double check protection
                    if self.window_mgr.is_protected_process(win_info.process_name, win_info.exe_path):
                        self.logger.log(f"Protected system process '{win_info.process_name}' was NOT closed for safety.", "INFO")
                    else:
                        # Close Application
                        force = self.config.force_terminate
                        timeout = self.config.force_terminate_timeout_ms
                        success, result_msg = self.window_mgr.close_window(win_info, force_terminate=force, timeout_ms=timeout)
                        
                        self.stats.detections_count += 1
                        if success:
                            self.stats.apps_closed_count += 1

                        self.logger.log_detection(
                            blocked_word=matched_str,
                            app_name=win_info.process_name,
                            pid=win_info.pid,
                            action="Closed" if success else "Close Failed",
                            window_title=win_info.title
                        )
                        last_frame_hash = None
            else:
                self.detector.check_consecutive_confirmation(app_key=app_key, is_detected=False)

            # Update CPU & Memory stats periodically (every 1s)
            if time.time() - last_stats_update > 1.0:
                self._update_system_stats()
                self._notify_stats()
                last_stats_update = time.time()

            # Dynamic interval sleep
            duration = time.time() - loop_start
            target_sleep = effective_interval_sec - duration
            if target_sleep > 0:
                time.sleep(target_sleep)
            else:
                time.sleep(0.01)
