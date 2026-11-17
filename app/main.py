"""
ScreenGuard Main Entry Point
Integrates Configuration, Background OCR Monitoring, System Tray, Hotkeys, and GUI.
"""

import sys
import os
import multiprocessing

# Safe stdout / stderr redirect for Windows --windowed / no-console mode
_log_dir = os.path.dirname(os.path.abspath(__file__))
if sys.stdout is None:
    try:
        sys.stdout = open(os.path.join(_log_dir, "screenguard_stdout.log"), "a", encoding="utf-8")
    except Exception:
        pass

if sys.stderr is None:
    try:
        sys.stderr = open(os.path.join(_log_dir, "screenguard_error.log"), "a", encoding="utf-8")
    except Exception:
        pass

# Crucial for PyInstaller Windows frozen binaries
multiprocessing.freeze_support()

try:
    import ctypes
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    try:
        import ctypes
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

import argparse
import threading

# Ensure project root is in sys.path
if getattr(sys, "frozen", False):
    BASE_DIR = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
else:
    _file_dir = os.path.dirname(os.path.abspath(__file__))
    if os.path.basename(_file_dir).lower() == "app":
        BASE_DIR = os.path.dirname(_file_dir)
    else:
        BASE_DIR = _file_dir

if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from app.config import ConfigManager
from app.logger import default_logger
from app.monitor import Monitor
from app.hotkeys import HotkeyManager
from app.tray import TrayManager
from app.gui import ModernGUI
from app.focus_manager import FocusManager
from app.widget import FloatingFocusWidget


class ScreenGuardApp:
    """Master application coordinator."""

    def __init__(self, start_minimized: bool = False):
        self.start_minimized = start_minimized
        
        # 1. Config & Logger
        self.config = ConfigManager()
        self.logger = default_logger

        # 2. Background Monitor
        self.monitor = Monitor(self.config, self.logger)

        # 3. 1-Hour Focus Allowance Manager
        self.focus_manager = FocusManager(self.config, self.monitor.window_mgr)

        # 4. Hotkey Manager
        self.hotkeys = HotkeyManager(callback=self.toggle_protection)
        try:
            self.hotkeys.register(self.config.get("hotkey", "Ctrl+Shift+F10"))
        except Exception:
            pass

        # 5. GUI
        self.gui = ModernGUI(
            config=self.config,
            monitor=self.monitor,
            logger=self.logger,
            on_toggle_protection=self.toggle_protection,
            on_hotkey_changed=self._on_hotkey_changed,
            focus_manager=self.focus_manager
        )

        # 6. Floating Desktop Focus Widget
        self.widget = FloatingFocusWidget(
            root=self.gui.root,
            focus_manager=self.focus_manager,
            on_open_gui=self.show_gui
        )
        self.gui.widget = self.widget

        # 7. Tray Manager
        self.tray = TrayManager(
            on_toggle_protection=self.toggle_protection,
            on_open_gui=self.show_gui,
            on_exit=self.exit_app,
            is_enabled_fn=lambda: self.config.is_enabled,
            focus_manager=self.focus_manager
        )

    def _on_hotkey_changed(self, new_hotkey: str) -> None:
        try:
            self.hotkeys.register(new_hotkey)
        except Exception:
            pass

    def toggle_protection(self) -> None:
        """Toggles protection state globally across Monitor, GUI, Tray, and Config."""
        new_state = not self.config.is_enabled
        self.config.set("enabled", new_state)
        self.logger.log(f"Protection toggled: {'ENABLED' if new_state else 'DISABLED'}")
        self.tray.update_status()
        self.gui._update_protection_status_visuals()

    def show_gui(self) -> None:
        """Opens and brings the GUI window to the foreground."""
        self.gui.root.after(0, self.gui.show)

    def _start_ipc_server(self) -> None:
        """Starts local HTTP API server for browser extension and single-instance IPC."""
        ScreenGuardAPIHandler.app_instance = self
        try:
            ThreadingHTTPServer.allow_reuse_address = True
            self.http_server = ThreadingHTTPServer(("127.0.0.1", IPC_PORT), ScreenGuardAPIHandler)
            t = threading.Thread(target=self.http_server.serve_forever, name="ScreenGuardHTTPServer", daemon=True)
            t.start()
            self.logger.log(f"ScreenGuard API server listening on 127.0.0.1:{IPC_PORT}")
        except Exception as e:
            self.logger.log(f"Warning: could not bind HTTP API on port {IPC_PORT}: {e}")

    def exit_app(self) -> None:
        """Cleanly shuts down all background threads and processes."""
        self.logger.log("Shutting down ScreenGuard...")
        try:
            if hasattr(self, "http_server") and self.http_server:
                self.http_server.shutdown()
        except Exception:
            pass
        self.focus_manager.stop()
        self.monitor.stop()
        self.hotkeys.stop()
        self.tray.stop()
        self.gui.close()
        sys.exit(0)

    def run(self) -> None:
        """Launches background services and GUI."""
        self.logger.log("ScreenGuard launching...")
        
        # Start IPC listener for instant single-click window restore
        self._start_ipc_server()

        # Start background monitor & focus manager
        self.monitor.start()
        self.focus_manager.start()

        # Start tray icon after small delay so Tkinter window initializes first
        self.gui.root.after(300, self.tray.start)

        if self.start_minimized:
            self.gui.hide()
        else:
            self.gui.show()

        if hasattr(self, "widget") and self.widget:
            self.gui.root.after(100, self.widget.show)

        # Main Tkinter Loop
        try:
            self.gui.run()
        except KeyboardInterrupt:
            self.exit_app()


import ctypes
import json
import urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

IPC_PORT = 49221
_singleton_mutex = None


class ScreenGuardAPIHandler(BaseHTTPRequestHandler):
    """Handles REST API requests from the browser extension and single-instance launcher."""

    protocol_version = "HTTP/1.1"
    app_instance = None  # Reference to ScreenGuardApp

    def log_message(self, format, *args):
        # Suppress noisy standard HTTP request console spam
        pass

    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Connection", "close")

    def _send_json_response(self, code: int, data: dict):
        try:
            body = json.dumps(data).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except Exception:
            pass

    def do_OPTIONS(self):
        try:
            self.send_response(204)
            self._send_cors_headers()
            self.send_header("Content-Length", "0")
            self.end_headers()
        except Exception:
            pass

    def do_GET(self):
        try:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")

            if path in ("/api/show", "/show"):
                if self.app_instance:
                    self.app_instance.show_gui()
                self._send_json_response(200, {"status": "ok", "message": "GUI restored"})
            elif path in ("/api/status", "/status"):
                if self.app_instance and hasattr(self.app_instance, "focus_manager"):
                    res = self.app_instance.focus_manager.get_status_dict()
                    self._send_json_response(200, res)
                else:
                    self._send_json_response(200, {"status": "ok", "ready": True})
            elif path in ("/api/ping", "/ping"):
                self._send_json_response(200, {"status": "ok", "app": "ScreenGuard"})
            else:
                self._send_json_response(404, {"error": "Not Found"})
        except Exception:
            pass

    def do_POST(self):
        try:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")

            if path in ("/api/show", "/show"):
                if self.app_instance:
                    self.app_instance.show_gui()
                self._send_json_response(200, {"status": "ok", "message": "GUI restored"})
            elif path in ("/api/heartbeat", "/heartbeat"):
                content_length = int(self.headers.get("Content-Length", 0))
                payload = {}
                if content_length > 0:
                    try:
                        raw_data = self.rfile.read(content_length)
                        payload = json.loads(raw_data.decode("utf-8"))
                    except Exception:
                        pass
                domain = payload.get("domain", "")
                url = payload.get("url", "")
                is_focused = bool(payload.get("is_focused", True))
                if self.app_instance and hasattr(self.app_instance, "focus_manager"):
                    res = self.app_instance.focus_manager.report_browser_tab(domain=domain, url=url, is_focused=is_focused)
                    self._send_json_response(200, res)
                else:
                    self._send_json_response(200, {"status": "ok"})
            else:
                self._send_json_response(404, {"error": "Not Found"})
        except Exception:
            pass


def try_send_show_to_existing_instance() -> bool:
    """Tries to connect to an existing ScreenGuard process via HTTP API to bring it to foreground."""
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{IPC_PORT}/api/show", method="GET")
        with urllib.request.urlopen(req, timeout=0.8) as resp:
            if resp.status == 200:
                return True
    except Exception:
        pass
    return False


def ensure_single_instance(mutex_name: str = "ScreenGuard_Global_Mutex") -> bool:
    """Prevents duplicate ScreenGuard instances from running concurrently."""
    global _singleton_mutex
    kernel32 = ctypes.windll.kernel32
    ERROR_ALREADY_EXISTS = 183

    _singleton_mutex = kernel32.CreateMutexW(None, False, mutex_name)
    if kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
        return False
    return True


def main():
    import traceback
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    log_file = os.path.join(base_dir, "startup_trace.log")
    try:
        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Entered main()\n")
            f.flush()

        multiprocessing.freeze_support()

        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Checking if already running...\n")
            f.flush()

        # 1. If another instance is running, signal it to show GUI and exit immediately
        if try_send_show_to_existing_instance():
            with open(log_file, "a") as f:
                f.write(f"[{os.getpid()}] Exited: try_send_show_to_existing_instance was True\n")
                f.flush()
            sys.exit(0)

        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Checking single instance mutex...\n")
            f.flush()

        # 2. Mutex safety check
        if not ensure_single_instance():
            with open(log_file, "a") as f:
                f.write(f"[{os.getpid()}] Exited: ensure_single_instance was False\n")
                f.flush()
            sys.exit(0)

        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Single instance verified, parsing args...\n")
            f.flush()

        parser = argparse.ArgumentParser(description="ScreenGuard: Fast Windows Word Detection & App Exit Utility")
        parser.add_argument("--minimized", action="store_true", help="Start minimized in system tray")
        args = parser.parse_args()

        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Initializing ScreenGuardApp...\n")
            f.flush()

        app = ScreenGuardApp(start_minimized=args.minimized)

        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] Calling app.run()...\n")
            f.flush()

        app.run()
    except SystemExit:
        raise
    except Exception as e:
        with open(log_file, "a") as f:
            f.write(f"[{os.getpid()}] EXCEPTION in main(): {traceback.format_exc()}\n")
            f.flush()


if __name__ == "__main__":
    main()
