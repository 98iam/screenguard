"""
ScreenGuard Main Desktop GUI
Modern, clean Windows interface with real-time stats, blocked words manager,
whitelist editor, detection history, and settings controls.
"""

import sys
import os
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from typing import Optional, Callable

from app.config import ConfigManager
from app.logger import Logger, default_logger, DetectionEvent
from app.monitor import Monitor, MonitorStats
from app.autostart import is_autostart_enabled, set_autostart
from app.focus_manager import FocusManager, format_detailed_time, is_focus_hours


class ModernGUI:
    """Main GUI Application window."""

    def __init__(
        self,
        config: ConfigManager,
        monitor: Monitor,
        logger: Optional[Logger] = None,
        on_toggle_protection: Optional[Callable[[], None]] = None,
        on_hotkey_changed: Optional[Callable[[str], None]] = None,
        focus_manager: Optional[FocusManager] = None
    ):
        self.config = config
        self.monitor = monitor
        self.logger = logger or default_logger
        self.on_toggle_protection_callback = on_toggle_protection
        self.on_hotkey_changed = on_hotkey_changed
        self.focus_manager = focus_manager

        self.root = tk.Tk()
        self.root.title("ScreenGuard - Word Detection & Safety Utility")
        
        # Center window on screen
        width = 820
        height = 680
        screen_w = self.root.winfo_screenwidth()
        screen_h = self.root.winfo_screenheight()
        x = max(0, (screen_w - width) // 2)
        y = max(0, (screen_h - height) // 2)
        self.root.geometry(f"{width}x{height}+{x}+{y}")
        self.root.minsize(760, 600)

        # Set window background
        self.root.configure(bg="#0f172a")

        self._setup_theme()
        self._build_ui()
        self._bind_events()

        # Connect live log and stats listeners
        self.logger.add_listener(self._on_log_event)
        self.monitor.add_stats_listener(self._on_stats_update)

        # Start periodic GUI refresh timer
        self.root.after(500, self._periodic_refresh)

    def _setup_theme(self) -> None:
        """Configures ttk styles for a sleek dark modern aesthetic."""
        style = ttk.Style()
        style.theme_use("clam")

        # Palette: Slate Dark
        # Background: #0f172a, Card: #1e293b, Accent: #3b82f6, Success: #10b981, Danger: #ef4444, Text: #f8fafc
        style.configure(".", background="#0f172a", foreground="#f8fafc", font=("Segoe UI", 10))
        
        # Notebook / Tabs
        style.configure("TNotebook", background="#0f172a", borderwidth=0)
        style.configure("TNotebook.Tab", background="#1e293b", foreground="#94a3b8", padding=[16, 8], font=("Segoe UI", 10, "bold"), borderwidth=0)
        style.map("TNotebook.Tab", background=[("selected", "#2563eb")], foreground=[("selected", "#ffffff")])

        # Frames & Cards
        style.configure("Card.TFrame", background="#1e293b", relief="flat")
        style.configure("Header.TFrame", background="#0b1329", relief="flat")

        # Labels
        style.configure("Title.TLabel", font=("Segoe UI", 16, "bold"), background="#0b1329", foreground="#38bdf8")
        style.configure("Subtitle.TLabel", font=("Segoe UI", 9), background="#0b1329", foreground="#94a3b8")
        style.configure("CardTitle.TLabel", font=("Segoe UI", 11, "bold"), background="#1e293b", foreground="#e2e8f0")
        style.configure("StatNum.TLabel", font=("Segoe UI", 14, "bold"), background="#1e293b", foreground="#38bdf8")
        style.configure("StatLbl.TLabel", font=("Segoe UI", 8), background="#1e293b", foreground="#94a3b8")
        style.configure("Card.TLabel", background="#1e293b", foreground="#f8fafc")

        # Buttons
        style.configure("Primary.TButton", font=("Segoe UI", 10, "bold"), background="#2563eb", foreground="#ffffff", padding=[14, 6], borderwidth=0)
        style.map("Primary.TButton", background=[("active", "#1d4ed8"), ("pressed", "#1e40af")])

        style.configure("Success.TButton", font=("Segoe UI", 10, "bold"), background="#059669", foreground="#ffffff", padding=[14, 6], borderwidth=0)
        style.map("Success.TButton", background=[("active", "#047857"), ("pressed", "#065f46")])

        style.configure("Danger.TButton", font=("Segoe UI", 10, "bold"), background="#dc2626", foreground="#ffffff", padding=[14, 6], borderwidth=0)
        style.map("Danger.TButton", background=[("active", "#b91c1c"), ("pressed", "#991b1b")])

        style.configure("Secondary.TButton", font=("Segoe UI", 9), background="#334155", foreground="#f8fafc", padding=[10, 5], borderwidth=0)
        style.map("Secondary.TButton", background=[("active", "#475569"), ("pressed", "#1e293b")])

        # Treeview / Lists
        style.configure("Treeview", background="#0f172a", foreground="#f8fafc", fieldbackground="#0f172a", rowheight=26, font=("Segoe UI", 9), borderwidth=0)
        style.configure("Treeview.Heading", background="#1e293b", foreground="#93c5fd", font=("Segoe UI", 9, "bold"), borderwidth=0)
        style.map("Treeview", background=[("selected", "#2563eb")], foreground=[("selected", "#ffffff")])

        # Combobox
        style.configure("TCombobox", fieldbackground="#0f172a", background="#1e293b", foreground="#ffffff", padding=4)
        style.map("TCombobox", fieldbackground=[("readonly", "#0f172a")], foreground=[("readonly", "#ffffff")])

        # Checkbutton
        style.configure("TCheckbutton", background="#1e293b", foreground="#f8fafc", font=("Segoe UI", 9))

    def _build_ui(self) -> None:
        """Constructs the complete application layout."""
        # Top Header Bar
        header_frame = ttk.Frame(self.root, style="Header.TFrame", padding=[20, 14])
        header_frame.pack(fill="x", side="top")

        title_box = ttk.Frame(header_frame, style="Header.TFrame")
        title_box.pack(side="left", fill="y")
        ttk.Label(title_box, text="🛡 SCREENguard", style="Title.TLabel").pack(anchor="w")
        ttk.Label(title_box, text="Fast Windows Word Detection & Application Safety Guard", style="Subtitle.TLabel").pack(anchor="w")

        # Protection Toggle Button & Status Badge in Header
        status_box = ttk.Frame(header_frame, style="Header.TFrame")
        status_box.pack(side="right", fill="y")

        self.status_badge = tk.Label(
            status_box,
            text="● ACTIVE",
            font=("Segoe UI", 11, "bold"),
            bg="#065f46",
            fg="#6ee7b7",
            padx=12,
            pady=4,
            relief="flat"
        )
        self.status_badge.pack(side="left", padx=(0, 12))

        self.toggle_btn = ttk.Button(
            status_box,
            text="Stop Protection",
            style="Danger.TButton",
            command=self._on_toggle_protection_clicked
        )
        self.toggle_btn.pack(side="left")

        # Main Tabbed Container
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill="both", expand=True, padx=16, pady=12)

        # Tab 1: ⏳ 1-Hour Focus Allowance
        self.tab_focus = ttk.Frame(self.notebook, style="Card.TFrame", padding=14)
        self.notebook.add(self.tab_focus, text="  ⏳ 1-Hour Focus Allowance  ")
        self._build_focus_tab()

        # Tab 2: Dashboard & Blocked Words
        self.tab_dashboard = ttk.Frame(self.notebook, style="Card.TFrame", padding=14)
        self.notebook.add(self.tab_dashboard, text="  🛡️ Dashboard & Words  ")
        self._build_dashboard_tab()

        # Tab 3: Settings & Behavior
        self.tab_settings = ttk.Frame(self.notebook, style="Card.TFrame", padding=14)
        self.notebook.add(self.tab_settings, text="  Monitoring Settings  ")
        self._build_settings_tab()

        # Tab 4: Whitelist & Safety
        self.tab_whitelist = ttk.Frame(self.notebook, style="Card.TFrame", padding=14)
        self.notebook.add(self.tab_whitelist, text="  App Whitelist  ")
        self._build_whitelist_tab()

        # Tab 5: Detection History & Logs
        self.tab_history = ttk.Frame(self.notebook, style="Card.TFrame", padding=14)
        self.notebook.add(self.tab_history, text="  History & Logs  ")
        self._build_history_tab()

        # Bottom Quick Status Bar
        self.statusbar = tk.Label(
            self.root,
            text="ScreenGuard Ready | Monitoring active window...",
            font=("Segoe UI", 8),
            bg="#0b1329",
            fg="#94a3b8",
            anchor="w",
            padx=16,
            pady=4
        )
        self.statusbar.pack(fill="x", side="bottom")

    def _build_focus_tab(self) -> None:
        """Dedicated tab for the 1-hour daily focus allowance."""
        # Top Header Card
        card = tk.Frame(self.tab_focus, bg="#1e293b", padx=16, pady=16, highlightbackground="#334155", highlightthickness=1)
        card.pack(fill="x", pady=(0, 14))

        # Title Row
        title_row = tk.Frame(card, bg="#1e293b")
        title_row.pack(fill="x", pady=(0, 10))

        tk.Label(
            title_row,
            text="⏳ Daily Focus Allowance (1 Hour)",
            font=("Segoe UI", 15, "bold"),
            bg="#1e293b",
            fg="#f8fafc"
        ).pack(side="left")

        # Strict Mode Badge
        strict_badge = tk.Frame(title_row, bg="#3f1a1a", padx=8, pady=3, highlightbackground="#7f1d1d", highlightthickness=1)
        strict_badge.pack(side="right")
        tk.Label(
            strict_badge,
            text="🔒 STRICT MODE (NO OFF SWITCH)",
            font=("Segoe UI", 9, "bold"),
            bg="#3f1a1a",
            fg="#f87171"
        ).pack()

        # Stats Sub-Row
        stats_sub = tk.Frame(card, bg="#1e293b")
        stats_sub.pack(fill="x", pady=(4, 10))

        # Remaining box
        rem_col = tk.Frame(stats_sub, bg="#1e293b")
        rem_col.pack(side="left", fill="y")

        tk.Label(rem_col, text="TIME REMAINING TODAY", font=("Segoe UI", 8, "bold"), bg="#1e293b", fg="#94a3b8").pack(anchor="w")
        self.lbl_focus_remaining = tk.Label(rem_col, text="60m 0s", font=("Segoe UI", 24, "bold"), bg="#1e293b", fg="#38bdf8")
        self.lbl_focus_remaining.pack(anchor="w")

        self.lbl_focus_window = tk.Label(rem_col, text="Restricted window: 4:00 AM – 8:00 PM", font=("Segoe UI", 9), bg="#1e293b", fg="#94a3b8")
        self.lbl_focus_window.pack(anchor="w")

        # Used box
        used_col = tk.Frame(stats_sub, bg="#1e293b")
        used_col.pack(side="right", anchor="e")

        tk.Label(used_col, text="USED TODAY", font=("Segoe UI", 8, "bold"), bg="#1e293b", fg="#94a3b8").pack(anchor="e")
        self.lbl_focus_used = tk.Label(used_col, text="0s of 1h", font=("Segoe UI", 13, "bold"), bg="#1e293b", fg="#f8fafc")
        self.lbl_focus_used.pack(anchor="e", pady=(4, 0))

        tk.Label(used_col, text="Resets daily at 4:00 AM", font=("Segoe UI", 8), bg="#1e293b", fg="#64748b").pack(anchor="e")

        # Smooth Progress Bar
        self.focus_progress = ttk.Progressbar(card, maximum=3600, value=0)
        self.focus_progress.pack(fill="x", pady=(8, 4))

        # Bottom Allowed Sites Section (Whitelist for websites ONLY)
        header = ttk.Frame(self.tab_focus, style="Card.TFrame")
        header.pack(fill="x", pady=(8, 6))

        ttk.Label(header, text="Allowed Websites (100% Unlimited)", style="CardTitle.TLabel").pack(side="left")

        btn_box = ttk.Frame(header, style="Card.TFrame")
        btn_box.pack(side="right")

        ttk.Button(btn_box, text="+ Add Website", style="Success.TButton", command=self._on_add_whitelist_site).pack(side="left", padx=3)
        ttk.Button(btn_box, text="🗑️ Remove", style="Danger.TButton", command=self._on_remove_whitelist_site).pack(side="left", padx=3)

        info_lbl = ttk.Label(
            self.tab_focus,
            text="Any time spent on these whitelisted websites is 100% UNLIMITED and never drains your 1-hour timer.\nAll other websites and general desktop apps draw from your 1-hour daily allowance.",
            style="Subtitle.TLabel"
        )
        info_lbl.pack(anchor="w", pady=(0, 6))

        tree_frame = ttk.Frame(self.tab_focus, style="Card.TFrame")
        tree_frame.pack(fill="both", expand=True, pady=4)

        self.site_whitelist_tree = ttk.Treeview(tree_frame, columns=("index", "site"), show="headings", height=8)
        self.site_whitelist_tree.heading("index", text="#")
        self.site_whitelist_tree.heading("site", text="Allowed Website Domain / URL Keyword")
        self.site_whitelist_tree.column("index", width=50, stretch=False, anchor="center")
        self.site_whitelist_tree.column("site", width=500, anchor="w")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.site_whitelist_tree.yview)
        self.site_whitelist_tree.configure(yscrollcommand=scrollbar.set)

        self.site_whitelist_tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self._refresh_site_whitelist()

    def _build_dashboard_tab(self) -> None:
        """Dashboard tab: Live Stats + Blocked Words Manager."""
        # Top Stats Row
        stats_frame = ttk.Frame(self.tab_dashboard, style="Card.TFrame")
        stats_frame.pack(fill="x", pady=(0, 14))

        cards = [
            ("Detections", "lbl_stat_detections", "0"),
            ("Apps Closed", "lbl_stat_closed", "0"),
            ("Total Scans", "lbl_stat_scans", "0"),
            ("Scan Latency", "lbl_stat_latency", "0 ms"),
            ("System CPU", "lbl_stat_cpu", "0%"),
            ("Memory Usage", "lbl_stat_ram", "0 MB"),
        ]

        for i, (label_text, attr_name, default_val) in enumerate(cards):
            card = tk.Frame(stats_frame, bg="#0f172a", padx=10, pady=8, highlightbackground="#334155", highlightthickness=1)
            card.pack(side="left", fill="both", expand=True, padx=3)
            
            val_lbl = ttk.Label(card, text=default_val, style="StatNum.TLabel")
            val_lbl.configure(background="#0f172a")
            val_lbl.pack(anchor="w")
            
            txt_lbl = ttk.Label(card, text=label_text.upper(), style="StatLbl.TLabel")
            txt_lbl.configure(background="#0f172a")
            txt_lbl.pack(anchor="w")
            
            setattr(self, attr_name, val_lbl)

        # Main Blocked Words Area
        words_header = ttk.Frame(self.tab_dashboard, style="Card.TFrame")
        words_header.pack(fill="x", pady=(4, 6))

        ttk.Label(words_header, text="Blocked Words & Phrases", style="CardTitle.TLabel").pack(side="left")

        # Action Buttons
        btn_box = ttk.Frame(words_header, style="Card.TFrame")
        btn_box.pack(side="right")

        ttk.Button(btn_box, text="+ Add Word", style="Success.TButton", command=self._on_add_word).pack(side="left", padx=3)
        ttk.Button(btn_box, text="✏ Edit", style="Secondary.TButton", command=self._on_edit_word).pack(side="left", padx=3)
        ttk.Button(btn_box, text="🗑 Delete", style="Danger.TButton", command=self._on_delete_word).pack(side="left", padx=3)

        # Word List Treeview
        tree_frame = ttk.Frame(self.tab_dashboard, style="Card.TFrame")
        tree_frame.pack(fill="both", expand=True, pady=4)

        self.words_tree = ttk.Treeview(tree_frame, columns=("index", "word"), show="headings", height=8)
        self.words_tree.heading("index", text="#")
        self.words_tree.heading("word", text="Blocked Word or Phrase")
        self.words_tree.column("index", width=50, stretch=False, anchor="center")
        self.words_tree.column("word", width=500, anchor="w")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.words_tree.yview)
        self.words_tree.configure(yscrollcommand=scrollbar.set)

        self.words_tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self._refresh_words_list()

    def _build_settings_tab(self) -> None:
        """Settings tab: scan speed, matching mode, action, hotkey, autostart."""
        # General Settings Grid
        grid_frame = ttk.Frame(self.tab_settings, style="Card.TFrame")
        grid_frame.pack(fill="x", pady=6)

        # 1. Monitoring Mode
        ttk.Label(grid_frame, text="Monitoring Mode:", style="CardTitle.TLabel").grid(row=0, column=0, sticky="w", pady=8, padx=6)
        self.mode_var = tk.StringVar(value=self.config.monitor_mode)
        mode_cb = ttk.Combobox(
            grid_frame,
            textvariable=self.mode_var,
            values=["active_window", "all_user_apps", "selected_apps"],
            state="readonly",
            width=26
        )
        mode_cb.grid(row=0, column=1, sticky="w", pady=8, padx=6)
        mode_cb.bind("<<ComboboxSelected>>", lambda e: self._save_settings())

        # 2. Scan Speed / Interval
        ttk.Label(grid_frame, text="Scan Speed Interval:", style="CardTitle.TLabel").grid(row=1, column=0, sticky="w", pady=8, padx=6)
        
        speed_map = {
            100: "Lightning (100 ms) - Ultra Fast",
            200: "Fast (200 ms)",
            400: "Balanced (400 ms) - Recommended",
            700: "Low CPU (700 ms)",
            1000: "Battery Saver (1000 ms)"
        }
        self.speed_var = tk.StringVar(value=speed_map.get(self.config.scan_interval_ms, "Balanced (400 ms) - Recommended"))
        speed_cb = ttk.Combobox(
            grid_frame,
            textvariable=self.speed_var,
            values=list(speed_map.values()),
            state="readonly",
            width=26
        )
        speed_cb.grid(row=1, column=1, sticky="w", pady=8, padx=6)
        speed_cb.bind("<<ComboboxSelected>>", lambda e: self._on_speed_selected())

        # 3. Matching Mode
        ttk.Label(grid_frame, text="Word Matching Mode:", style="CardTitle.TLabel").grid(row=2, column=0, sticky="w", pady=8, padx=6)
        self.match_var = tk.StringVar(value=self.config.match_mode)
        match_cb = ttk.Combobox(
            grid_frame,
            textvariable=self.match_var,
            values=["exact_word", "substring"],
            state="readonly",
            width=26
        )
        match_cb.grid(row=2, column=1, sticky="w", pady=8, padx=6)
        match_cb.bind("<<ComboboxSelected>>", lambda e: self._save_settings())

        # 4. Action Behavior
        ttk.Label(grid_frame, text="Action on Detection:", style="CardTitle.TLabel").grid(row=3, column=0, sticky="w", pady=8, padx=6)
        self.action_var = tk.StringVar(value=self.config.action)
        action_cb = ttk.Combobox(
            grid_frame,
            textvariable=self.action_var,
            values=["graceful_close", "force_close"],
            state="readonly",
            width=26
        )
        action_cb.grid(row=3, column=1, sticky="w", pady=8, padx=6)
        action_cb.bind("<<ComboboxSelected>>", lambda e: self._save_settings())

        # 5. Global Hotkey
        ttk.Label(grid_frame, text="Global Toggle Hotkey:", style="CardTitle.TLabel").grid(row=4, column=0, sticky="w", pady=8, padx=6)
        hotkey_box = ttk.Frame(grid_frame, style="Card.TFrame")
        hotkey_box.grid(row=4, column=1, sticky="w", pady=8, padx=6)

        self.hotkey_entry = tk.Entry(hotkey_box, bg="#0f172a", fg="#f8fafc", insertbackground="#38bdf8", font=("Segoe UI", 10), width=18)
        self.hotkey_entry.insert(0, self.config.get("hotkey", "Ctrl+Shift+F10"))
        self.hotkey_entry.pack(side="left", padx=(0, 6))

        ttk.Button(hotkey_box, text="Set Hotkey", style="Secondary.TButton", command=self._on_save_hotkey).pack(side="left")

        # Toggles frame
        toggle_frame = ttk.Frame(self.tab_settings, style="Card.TFrame")
        toggle_frame.pack(fill="x", pady=(14, 4))

        # Force terminate check
        self.force_var = tk.BooleanVar(value=self.config.force_terminate)
        chk_force = ttk.Checkbutton(
            toggle_frame,
            text="Force terminate process if graceful WM_CLOSE does not respond within 500ms",
            variable=self.force_var,
            command=self._save_settings
        )
        chk_force.pack(anchor="w", pady=4)

        # Consecutive detection check
        self.consecutive_var = tk.BooleanVar(value=self.config.consecutive_detection)
        chk_consec = ttk.Checkbutton(
            toggle_frame,
            text="Require detection in 2 consecutive scans (Reduces potential false positives)",
            variable=self.consecutive_var,
            command=self._save_settings
        )
        chk_consec.pack(anchor="w", pady=4)

        # Start with Windows check
        self.autostart_var = tk.BooleanVar(value=is_autostart_enabled())
        chk_auto = ttk.Checkbutton(
            toggle_frame,
            text="Start ScreenGuard automatically when logging into Windows",
            variable=self.autostart_var,
            command=self._on_autostart_toggle
        )
        chk_auto.pack(anchor="w", pady=4)

    def _build_whitelist_tab(self) -> None:
        """Whitelist tab: safe application list management."""
        header = ttk.Frame(self.tab_whitelist, style="Card.TFrame")
        header.pack(fill="x", pady=(4, 6))

        ttk.Label(header, text="Whitelisted Applications (Never Closed)", style="CardTitle.TLabel").pack(side="left")

        btn_box = ttk.Frame(header, style="Card.TFrame")
        btn_box.pack(side="right")

        ttk.Button(btn_box, text="+ Add App", style="Success.TButton", command=self._on_add_whitelist_app).pack(side="left", padx=3)
        ttk.Button(btn_box, text="🗑 Remove", style="Danger.TButton", command=self._on_remove_whitelist_app).pack(side="left", padx=3)

        info_lbl = ttk.Label(
            self.tab_whitelist,
            text="Note: Core Windows system processes (explorer.exe, dwm.exe, services.exe, etc.) are permanently protected.",
            style="Subtitle.TLabel"
        )
        info_lbl.pack(anchor="w", pady=(0, 6))

        tree_frame = ttk.Frame(self.tab_whitelist, style="Card.TFrame")
        tree_frame.pack(fill="both", expand=True, pady=4)

        self.whitelist_tree = ttk.Treeview(tree_frame, columns=("index", "app"), show="headings", height=8)
        self.whitelist_tree.heading("index", text="#")
        self.whitelist_tree.heading("app", text="Application Name / Exe")
        self.whitelist_tree.column("index", width=50, stretch=False, anchor="center")
        self.whitelist_tree.column("app", width=500, anchor="w")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.whitelist_tree.yview)
        self.whitelist_tree.configure(yscrollcommand=scrollbar.set)

        self.whitelist_tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self._refresh_whitelist()

    def _build_history_tab(self) -> None:
        """Detection history & event log viewer."""
        header = ttk.Frame(self.tab_history, style="Card.TFrame")
        header.pack(fill="x", pady=(4, 6))

        ttk.Label(header, text="Detection & Action History", style="CardTitle.TLabel").pack(side="left")

        ttk.Button(header, text="🗑 Clear History", style="Secondary.TButton", command=self._on_clear_history).pack(side="right")

        # History Treeview
        tree_frame = ttk.Frame(self.tab_history, style="Card.TFrame")
        tree_frame.pack(fill="both", expand=True, pady=4)

        self.history_tree = ttk.Treeview(
            tree_frame,
            columns=("time", "word", "app", "pid", "action"),
            show="headings",
            height=10
        )
        self.history_tree.heading("time", text="Time")
        self.history_tree.heading("word", text="Detected Word")
        self.history_tree.heading("app", text="Application")
        self.history_tree.heading("pid", text="PID")
        self.history_tree.heading("action", text="Action Result")

        self.history_tree.column("time", width=90, stretch=False, anchor="center")
        self.history_tree.column("word", width=160, anchor="w")
        self.history_tree.column("app", width=180, anchor="w")
        self.history_tree.column("pid", width=70, stretch=False, anchor="center")
        self.history_tree.column("action", width=140, anchor="w")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.history_tree.yview)
        self.history_tree.configure(yscrollcommand=scrollbar.set)

        self.history_tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

    def _bind_events(self) -> None:
        """Binds window close to minimize to tray."""
        self.root.protocol("WM_DELETE_WINDOW", self.hide)

    def _periodic_refresh(self) -> None:
        """Periodic background status updater."""
        try:
            self._update_protection_status_visuals()
            active_app = self.monitor.stats.active_app_name
            active_title = self.monitor.stats.active_window_title
            if active_app and active_app != "None":
                self.statusbar.config(text=f"ScreenGuard Monitoring | Active App: {active_app} ({active_title})")
            else:
                self.statusbar.config(text="ScreenGuard Monitoring | Looking for foreground application...")

            if self.focus_manager:
                rem_sec = self.focus_manager.get_remaining_seconds()
                used_sec = self.focus_manager.used_seconds
                in_focus = is_focus_hours()

                if in_focus:
                    if rem_sec <= 0:
                        self.lbl_focus_remaining.config(text="Time's Up! ⏳", fg="#ef4444")
                        self.lbl_focus_window.config(text="Daily 1-Hour Allowance Exhausted (Locked until 8:00 PM)")
                    else:
                        self.lbl_focus_remaining.config(text=format_detailed_time(rem_sec), fg="#38bdf8")
                        self.lbl_focus_window.config(text="Restricted focus hours active (4:00 AM – 8:00 PM)")
                else:
                    self.lbl_focus_remaining.config(text="Leisure Window 🌙", fg="#10b981")
                    self.lbl_focus_window.config(text="Free usage until 4:00 AM")

                self.lbl_focus_used.config(text=f"{format_detailed_time(used_sec)} of 1h")
                self.focus_progress["value"] = min(3600, used_sec)
        except Exception:
            pass
        self.root.after(1000, self._periodic_refresh)

    def _on_stats_update(self, stats: MonitorStats) -> None:
        """Thread-safe UI stats update."""
        def update():
            try:
                self.lbl_stat_detections.config(text=str(stats.detections_count))
                self.lbl_stat_closed.config(text=str(stats.apps_closed_count))
                self.lbl_stat_scans.config(text=str(stats.total_scans))
                self.lbl_stat_latency.config(text=f"{stats.avg_scan_latency_ms:.0f} ms")
                self.lbl_stat_cpu.config(text=f"{stats.cpu_percent:.0f}%")
                self.lbl_stat_ram.config(text=f"{stats.memory_mb:.0f} MB")
            except Exception:
                pass
        self.root.after_idle(update)

    def _on_log_event(self, msg: str, event: Optional[DetectionEvent]) -> None:
        """Callback for new log/detection events."""
        if event:
            def add_history():
                try:
                    self.history_tree.insert(
                        "",
                        0,
                        values=(event.time_str, event.blocked_word, event.app_name, event.pid, event.action)
                    )
                except Exception:
                    pass
            self.root.after_idle(add_history)

    def _update_protection_status_visuals(self) -> None:
        """Refreshes status badge and toggle button text/color."""
        is_enabled = self.config.is_enabled
        if is_enabled:
            self.status_badge.config(text="● ACTIVE", bg="#065f46", fg="#6ee7b7")
            self.toggle_btn.config(text="Stop Protection", style="Danger.TButton")
        else:
            self.status_badge.config(text="○ PAUSED", bg="#450a0a", fg="#fca5a5")
            self.toggle_btn.config(text="Start Protection", style="Success.TButton")

    def _on_toggle_protection_clicked(self) -> None:
        if self.on_toggle_protection_callback:
            self.on_toggle_protection_callback()
        else:
            new_state = not self.config.is_enabled
            self.config.set("enabled", new_state)
        self._update_protection_status_visuals()

    def _refresh_words_list(self) -> None:
        self.words_tree.delete(*self.words_tree.get_children())
        words = self.config.blocked_words
        for idx, w in enumerate(words, 1):
            self.words_tree.insert("", "end", values=(idx, w))

    def _on_add_word(self) -> None:
        word = simpledialog.askstring("Add Blocked Word", "Enter word or phrase to block:", parent=self.root)
        if word and word.strip():
            if self.config.add_blocked_word(word.strip()):
                self._refresh_words_list()
                self.logger.log(f"Added blocked word: '{word.strip()}'")
            else:
                messagebox.showwarning("Duplicate Word", "Word already exists in blocked list.", parent=self.root)

    def _on_edit_word(self) -> None:
        selected = self.words_tree.selection()
        if not selected:
            messagebox.showinfo("Select Word", "Please select a word to edit.", parent=self.root)
            return
        item_vals = self.words_tree.item(selected[0], "values")
        old_word = item_vals[1]

        new_word = simpledialog.askstring("Edit Word", "Modify blocked word/phrase:", initialvalue=old_word, parent=self.root)
        if new_word and new_word.strip() and new_word.strip() != old_word:
            if self.config.edit_blocked_word(old_word, new_word.strip()):
                self._refresh_words_list()
                self.logger.log(f"Updated blocked word: '{old_word}' -> '{new_word.strip()}'")

    def _on_delete_word(self) -> None:
        selected = self.words_tree.selection()
        if not selected:
            messagebox.showinfo("Select Word", "Please select a word to delete.", parent=self.root)
            return
        item_vals = self.words_tree.item(selected[0], "values")
        word = item_vals[1]

        if messagebox.askyesno("Delete Word", f"Remove '{word}' from blocked list?", parent=self.root):
            self.config.remove_blocked_word(word)
            self._refresh_words_list()
            self.logger.log(f"Deleted blocked word: '{word}'")

    def _refresh_whitelist(self) -> None:
        self.whitelist_tree.delete(*self.whitelist_tree.get_children())
        apps = self.config.whitelist
        for idx, app in enumerate(apps, 1):
            self.whitelist_tree.insert("", "end", values=(idx, app))

    def _on_add_whitelist_app(self) -> None:
        app = simpledialog.askstring("Add Allowed App", "Enter executable name (e.g. notepad.exe):", parent=self.root)
        if app and app.strip():
            if self.config.add_whitelist_app(app.strip()):
                self._refresh_whitelist()
                self.logger.log(f"Added whitelist application: '{app.strip()}'")
            else:
                messagebox.showwarning("Duplicate", "App already in whitelist.", parent=self.root)

    def _on_remove_whitelist_app(self) -> None:
        selected = self.whitelist_tree.selection()
        if not selected:
            messagebox.showinfo("Select App", "Please select an app to remove.", parent=self.root)
            return
        item_vals = self.whitelist_tree.item(selected[0], "values")
        app = item_vals[1]

        if messagebox.askyesno("Remove App", f"Remove '{app}' from whitelist?", parent=self.root):
            self.config.remove_whitelist_app(app)
            self._refresh_whitelist()
            self.logger.log(f"Removed whitelist application: '{app}'")


    def _build_site_whitelist_tab(self) -> None:
        """Site whitelist tab: safe websites and domains management."""
        header = ttk.Frame(self.tab_site_whitelist, style="Card.TFrame")
        header.pack(fill="x", pady=(4, 6))

        ttk.Label(header, text="Whitelisted Websites & Domains (Never Closed)", style="CardTitle.TLabel").pack(side="left")

        btn_box = ttk.Frame(header, style="Card.TFrame")
        btn_box.pack(side="right")

        ttk.Button(btn_box, text="+ Add Website", style="Success.TButton", command=self._on_add_whitelist_site).pack(side="left", padx=3)
        ttk.Button(btn_box, text="🗑 Remove", style="Danger.TButton", command=self._on_remove_whitelist_site).pack(side="left", padx=3)

        info_lbl = ttk.Label(
            self.tab_site_whitelist,
            text="Enter websites or domains (e.g. youtube.com, wikipedia.org, github.com). If you visit these sites, they will never be closed.",
            style="Subtitle.TLabel"
        )
        info_lbl.pack(anchor="w", pady=(0, 6))

        tree_frame = ttk.Frame(self.tab_site_whitelist, style="Card.TFrame")
        tree_frame.pack(fill="both", expand=True, pady=4)

        self.site_whitelist_tree = ttk.Treeview(tree_frame, columns=("index", "site"), show="headings", height=8)
        self.site_whitelist_tree.heading("index", text="#")
        self.site_whitelist_tree.heading("site", text="Website Domain / Title Keyword")
        self.site_whitelist_tree.column("index", width=50, stretch=False, anchor="center")
        self.site_whitelist_tree.column("site", width=500, anchor="w")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.site_whitelist_tree.yview)
        self.site_whitelist_tree.configure(yscrollcommand=scrollbar.set)

        self.site_whitelist_tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self._refresh_site_whitelist()

    def _refresh_site_whitelist(self) -> None:
        self.site_whitelist_tree.delete(*self.site_whitelist_tree.get_children())
        sites = self.config.whitelisted_sites
        for idx, site in enumerate(sites, 1):
            self.site_whitelist_tree.insert("", "end", values=(idx, site))

    def _on_add_whitelist_site(self) -> None:
        site = simpledialog.askstring("Add Allowed Website", "Enter domain or website name (e.g. youtube.com, wikipedia.org):", parent=self.root)
        if site and site.strip():
            if self.config.add_whitelisted_site(site.strip()):
                self._refresh_site_whitelist()
                self.logger.log(f"Added whitelist website: '{site.strip()}'")
            else:
                messagebox.showwarning("Duplicate", "Website is already in whitelist.", parent=self.root)

    def _on_remove_whitelist_site(self) -> None:
        selected = self.site_whitelist_tree.selection()
        if not selected:
            messagebox.showinfo("Select Website", "Please select a website to remove.", parent=self.root)
            return
        item_vals = self.site_whitelist_tree.item(selected[0], "values")
        site = item_vals[1]

        if messagebox.askyesno("Remove Website", f"Remove '{site}' from allowed websites?", parent=self.root):
            self.config.remove_whitelisted_site(site)
            self._refresh_site_whitelist()
            self.logger.log(f"Removed whitelist website: '{site}'")

    def _on_clear_history(self) -> None:
        self.logger.clear_history()
        self.history_tree.delete(*self.history_tree.get_children())

    def _on_speed_selected(self) -> None:
        txt = self.speed_var.get()
        if "100 ms" in txt:
            interval = 100
        elif "200 ms" in txt:
            interval = 200
        elif "400 ms" in txt:
            interval = 400
        elif "700 ms" in txt:
            interval = 700
        elif "1000 ms" in txt:
            interval = 1000
        else:
            interval = 400

        self.config.set("scan_interval_ms", interval)
        self.logger.log(f"Scan interval set to {interval}ms")

    def _save_settings(self) -> None:
        self.config.update({
            "monitor_mode": self.mode_var.get(),
            "match_mode": self.match_var.get(),
            "action": self.action_var.get(),
            "force_terminate": self.force_var.get(),
            "consecutive_detection": self.consecutive_var.get()
        })

    def _on_save_hotkey(self) -> None:
        new_hk = self.hotkey_entry.get().strip()
        if new_hk:
            self.config.set("hotkey", new_hk)
            if self.on_hotkey_changed:
                self.on_hotkey_changed(new_hk)
            messagebox.showinfo("Hotkey Saved", f"Global toggle hotkey updated to: {new_hk}", parent=self.root)

    def _on_autostart_toggle(self) -> None:
        enable = self.autostart_var.get()
        set_autostart(enable)
        self.config.set("start_with_windows", enable)
        self.logger.log(f"Start with Windows set to {enable}")

    def show(self) -> None:
        """Unhides and focuses the window."""
        try:
            self.root.deiconify()
            self.root.state("normal")
            self.root.attributes("-topmost", True)
            self.root.lift()
            self.root.focus_force()
            self.root.after(100, lambda: self.root.attributes("-topmost", False))
            if hasattr(self, "widget") and self.widget and hasattr(self.widget, "show"):
                self.root.after(50, self.widget.show)
        except Exception as e:
            print(f"[GUI] show error: {e}")

    def hide(self) -> None:
        """Minimizes the window to the system tray while keeping the floating widget alive."""
        self.root.withdraw()
        if hasattr(self, "widget") and self.widget and hasattr(self.widget, "show"):
            self.root.after(50, self.widget.show)

    def run(self) -> None:
        """Starts the Tkinter event loop."""
        self.root.mainloop()

    def close(self) -> None:
        try:
            self.root.quit()
            self.root.destroy()
        except Exception:
            pass
