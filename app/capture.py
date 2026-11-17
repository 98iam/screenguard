"""
ScreenGuard Screen Capture System
High-performance screen capture using MSS with multi-monitor & DPI scaling support.
"""

import sys
import ctypes
from typing import Optional, Tuple, Dict, Any, List
import mss
from PIL import Image

# Enable Per-Monitor DPI Awareness for accurate window coords & high-DPI monitors
try:
    # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
    ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
except Exception:
    try:
        # DPI_AWARENESS_PER_MONITOR_AWARE = 2
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


class ScreenCapture:
    """Provides fast, non-leaking screen frame capture."""

    def __init__(self):
        self._sct: Optional[mss.mss] = None

    def _get_sct(self) -> mss.mss:
        if self._sct is None:
            self._sct = mss.mss()
        return self._sct

    def get_monitors(self) -> List[Dict[str, int]]:
        """Returns list of monitor coordinates."""
        sct = self._get_sct()
        return sct.monitors  # monitors[0] is all, monitors[1..N] are individual

    def capture_screen(self, monitor_idx: int = 0) -> Optional[Image.Image]:
        """
        Capture a full monitor or virtual screen.
        monitor_idx: 0 for all monitors / default virtual desktop, 1..N for specific monitor.
        """
        try:
            sct = self._get_sct()
            monitors = sct.monitors
            if monitor_idx < 0 or monitor_idx >= len(monitors):
                monitor_idx = 0
            
            bbox = monitors[monitor_idx]
            sct_img = sct.grab(bbox)
            # Convert directly to RGB PIL Image
            img = Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")
            return img
        except Exception as e:
            # Re-initialize MSS in case of display reconfiguration
            self.close()
            return None

    def capture_region(self, rect: Tuple[int, int, int, int]) -> Optional[Image.Image]:
        """
        Capture a specific region bounding box (left, top, right, bottom).
        """
        left, top, right, bottom = rect
        width = right - left
        height = bottom - top
        if width <= 10 or height <= 10:
            return None

        try:
            sct = self._get_sct()
            monitor = {"top": top, "left": left, "width": width, "height": height}
            sct_img = sct.grab(monitor)
            img = Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")
            return img
        except Exception:
            self.close()
            return None

    def close(self) -> None:
        if self._sct is not None:
            try:
                self._sct.close()
            except Exception:
                pass
            self._sct = None

    def __del__(self):
        self.close()
