"""
ScreenGuard System Tray Manager
Provides system tray icon with dynamic protection indicator and menu actions.
"""

import threading
from typing import Callable, Optional
from PIL import Image, ImageDraw
import pystray
from pystray import MenuItem as item, Menu


def create_tray_icon_image(enabled: bool = True) -> Image.Image:
    """Generates a clean 64x64 dynamic status icon."""
    size = (64, 64)
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Draw rounded shield / circle
    # Outer circle
    draw.ellipse((4, 4, 60, 60), fill=(20, 24, 33), outline=(60, 70, 90), width=2)
    
    if enabled:
        # Green active indicator
        draw.ellipse((16, 16, 48, 48), fill=(34, 197, 94))
        # Inner glow
        draw.ellipse((22, 22, 42, 42), fill=(74, 222, 128))
    else:
        # Gray / Red paused indicator
        draw.ellipse((16, 16, 48, 48), fill=(239, 68, 68))
        draw.ellipse((22, 22, 42, 42), fill=(248, 113, 113))

    return image


class TrayManager:
    """Manages the Windows System Tray icon."""

    def __init__(
        self,
        on_toggle_protection: Callable[[], None],
        on_open_gui: Callable[[], None],
        on_exit: Callable[[], None],
        is_enabled_fn: Callable[[], bool],
        focus_manager: Optional[object] = None
    ):
        self.on_toggle_protection = on_toggle_protection
        self.on_open_gui = on_open_gui
        self.on_exit = on_exit
        self.is_enabled_fn = is_enabled_fn
        self.focus_manager = focus_manager
        self._icon: Optional[pystray.Icon] = None
        self._thread: Optional[threading.Thread] = None

    def _create_menu(self) -> Menu:
        enabled = self.is_enabled_fn()
        status_text = "● Word Guard: ON" if enabled else "○ Word Guard: OFF"
        toggle_text = "Stop Word Guard" if enabled else "Start Word Guard"

        focus_items = []
        if self.focus_manager and hasattr(self.focus_manager, "get_remaining_seconds"):
            rem = self.focus_manager.get_remaining_seconds()
            mins = rem // 60
            focus_items = [
                item(f"⏳ Focus Time: {mins}m left", lambda _: None, enabled=False),
                Menu.SEPARATOR
            ]

        menu_items = [
            item("ScreenGuard", lambda _: None, enabled=False),
            Menu.SEPARATOR,
            *focus_items,
            item(status_text, lambda _: None, enabled=False),
            item(toggle_text, lambda icon, item: self.on_toggle_protection()),
            Menu.SEPARATOR,
            item("Open ScreenGuard", lambda icon, item: self.on_open_gui(), default=True),
            Menu.SEPARATOR,
            item("Exit", lambda icon, item: self.on_exit())
        ]
        return Menu(*menu_items)

    def _get_title(self) -> str:
        enabled = self.is_enabled_fn()
        if not enabled:
            return "ScreenGuard - Off"
        if self.focus_manager and hasattr(self.focus_manager, "get_remaining_seconds"):
            rem = self.focus_manager.get_remaining_seconds()
            return f"ScreenGuard - Focus: {rem // 60}m left"
        return "ScreenGuard - Active"

    def start(self) -> None:
        """Starts the system tray icon in a dedicated daemon thread."""
        enabled = self.is_enabled_fn()
        img = create_tray_icon_image(enabled)
        
        self._icon = pystray.Icon(
            "ScreenGuard",
            img,
            self._get_title(),
            menu=self._create_menu()
        )

        self._thread = threading.Thread(target=self._icon.run, daemon=True, name="TrayThread")
        self._thread.start()

    def update_status(self) -> None:
        """Refreshes tray icon visual and context menu."""
        if self._icon is not None:
            try:
                enabled = self.is_enabled_fn()
                self._icon.icon = create_tray_icon_image(enabled)
                self._icon.title = self._get_title()
                self._icon.menu = self._create_menu()
            except Exception as e:
                print(f"[Tray] Error updating tray: {e}")

    def stop(self) -> None:
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception:
                pass
            self._icon = None
