"""
ScreenGuard Floating Desktop Focus Widget
Displays a mini, sleek, draggable pill on the desktop showing live focus allowance remaining.
Designed for 0% CPU overhead with smooth 1-second updates.
"""

import tkinter as tk
from typing import Callable, Optional
from app.focus_manager import FocusManager, is_focus_hours


class FloatingFocusWidget:
    """Compact, modern floating pill widget for Windows desktop."""

    def __init__(self, root: tk.Tk, focus_manager: FocusManager, on_open_gui: Callable[[], None]):
        self.focus_manager = focus_manager
        self.on_open_gui = on_open_gui

        self.window = tk.Toplevel(root)
        self.window.title("ScreenGuard Focus Widget")
        self.window.overrideredirect(True)      # Borderless floating pill
        self.window.attributes("-topmost", True) # Always visible above browser/apps
        self.window.attributes("-alpha", 0.95)  # Modern sleek opacity
        self.window.configure(bg="#090d16")

        # Initial positioning: top-right corner with 24px margin
        screen_w = self.window.winfo_screenwidth()
        width = 185
        height = 42
        init_x = max(10, screen_w - width - 28)
        init_y = 28
        self.window.geometry(f"{width}x{height}+{init_x}+{init_y}")
        self.window.update_idletasks()
        self.window.deiconify()
        self.window.lift()
        self.window.attributes("-topmost", True)

        try:
            import ctypes
            hwnd = self.window.winfo_id()
            ctypes.windll.user32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 | 0x0040)
        except Exception:
            pass

        # Container card
        self.card = tk.Frame(
            self.window,
            bg="#0f172a",
            highlightbackground="#38bdf8",
            highlightthickness=1,
            cursor="fleur"
        )
        self.card.pack(fill="both", expand=True)

        # Icon Label
        self.lbl_icon = tk.Label(
            self.card,
            text="⏳",
            font=("Segoe UI Emoji", 11),
            bg="#0f172a",
            fg="#38bdf8"
        )
        self.lbl_icon.pack(side="left", padx=(10, 4))

        # Main Text Label
        self.lbl_text = tk.Label(
            self.card,
            text="60m 0s left",
            font=("Segoe UI", 10, "bold"),
            bg="#0f172a",
            fg="#f8fafc"
        )
        self.lbl_text.pack(side="left", padx=(0, 10))

        # Dragging and single-tap mechanics
        self._drag_start_x = 0
        self._drag_start_y = 0
        self._moved = False

        for element in (self.window, self.card, self.lbl_icon, self.lbl_text):
            element.bind("<Button-1>", self._on_drag_start)
            element.bind("<B1-Motion>", self._on_drag_motion)
            element.bind("<ButtonRelease-1>", self._on_drag_release)
            element.bind("<Double-Button-1>", lambda e: self.on_open_gui())
            element.bind("<Button-3>", lambda e: self.on_open_gui())  # Right-click also opens GUI!

        # Start periodic tick
        self._tick()

    def _on_drag_start(self, event):
        self._drag_start_x = event.x_root - self.window.winfo_x()
        self._drag_start_y = event.y_root - self.window.winfo_y()
        self._moved = False

    def _on_drag_motion(self, event):
        self._moved = True
        new_x = event.x_root - self._drag_start_x
        new_y = event.y_root - self._drag_start_y
        self.window.geometry(f"+{new_x}+{new_y}")

    def _on_drag_release(self, event):
        if not self._moved:
            self.on_open_gui()

    def _tick(self):
        try:
            if not self.window.winfo_exists():
                return

            # Keep floating pill always on screen above all windows
            self.window.deiconify()
            self.window.attributes("-topmost", True)
            self.window.lift()

            try:
                import ctypes
                hwnd = self.window.winfo_id()
                ctypes.windll.user32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 | 0x0040)
            except Exception:
                pass

            in_focus = is_focus_hours()
            rem_sec = self.focus_manager.get_remaining_seconds()

            if not in_focus:
                self.lbl_icon.config(text="🌙", fg="#10b981")
                self.lbl_text.config(text="Free Hours", fg="#10b981")
            elif rem_sec <= 0:
                self.lbl_icon.config(text="🔒", fg="#ef4444")
                self.lbl_text.config(text="Time's Up!", fg="#ef4444")
            else:
                mins = rem_sec // 60
                secs = rem_sec % 60
                self.lbl_icon.config(text="⏳", fg="#38bdf8")
                if mins > 0:
                    self.lbl_text.config(text=f"{mins}m {secs}s left", fg="#38bdf8")
                else:
                    self.lbl_text.config(text=f"{secs}s left", fg="#f87171")
        except Exception:
            pass

        self.window.after(1000, self._tick)

    def show(self):
        try:
            self.window.deiconify()
            self.window.lift()
            self.window.attributes("-topmost", True)
            try:
                import ctypes
                hwnd = self.window.winfo_id()
                ctypes.windll.user32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 | 0x0040)
            except Exception:
                pass
        except Exception:
            pass

    def hide(self):
        try:
            self.window.withdraw()
        except Exception:
            pass
