# 🛡 ScreenGuard

**ScreenGuard** is a fast, lightweight, and offline Windows utility designed to protect your privacy and screen content. It monitors the active screen in real-time, recognizes visible text using native Windows OCR, and immediately closes the active window if a user-defined blocked word or phrase appears.

---

## ⚡ Key Features

- **Instant Detection & Exit**: Immediately identifies and sends a graceful `WM_CLOSE` signal to any user application (Chrome, Edge, Word, PDF Reader, Notepad, etc.) displaying blocked keywords.
- **100% Offline & Local**: Uses native Windows 10/11 hardware-accelerated OCR (`Windows.Media.Ocr`). No screenshots are ever saved to disk or uploaded anywhere.
- **Critical System Safety**: Built-in protection prevents closing critical Windows system processes (e.g., `explorer.exe`, `dwm.exe`, `services.exe`, `csrss.exe`).
- **Precision Matching**: Supports exact word boundaries (prevents false matches like `cat` in `education`), phrase matching, case insensitivity, punctuation trimming, and unicode normalization.
- **Modern Desktop GUI**: Sleek dashboard with live CPU/RAM stats, scan latency metrics, blocked word manager, and detection history.
- **System Tray & Global Hotkeys**: Runs quietly in the background; toggle protection instantly with `Ctrl + Shift + F10` or via the system tray.
- **Customizable Speed**: Choose between *Lightning (100ms)*, *Fast (200ms)*, *Balanced (400ms)*, or *Battery Saver (1000ms)*.
- **Standalone Portable EXE**: Can be built into a standalone executable with zero external installation requirements for end users.

---

## 🚀 Quick Start Guide

### Option 1: Running from Python

1. **Install Requirements**:
   ```bash
   pip install -r requirements.txt
   ```
2. **Launch ScreenGuard**:
   - Double-click `run.bat`, or run:
   ```bash
   python app/main.py
   ```

### Option 2: Building Standalone Executable (`ScreenGuard.exe`)

1. Double-click `build.bat` (or run `python -m PyInstaller ...`).
2. The compiled executable will be generated in:
   ```text
   dist/ScreenGuard/ScreenGuard.exe
   ```
3. Run `ScreenGuard.exe` on any Windows 10/11 computer—no Python installation needed!

---

## 🖥 How ScreenGuard Works

```text
                  VISIBLE SCREEN
                        ↓
                 FAST CAPTURE (MSS)
                        ↓
            WINDOWS MEDIA OCR (OFFLINE)
                        ↓
                BLOCKED WORD FOUND?
                 ↙             ↘
               NO               YES
                ↓                ↓
            Continue       Identify Foreground Window
                                 ↓
                          Is App Protected / Whitelisted?
                           ↙             ↘
                         YES              NO
                          ↓                ↓
                      Do Nothing      Close Application (WM_CLOSE)
                                           ↓
                                      Log Event to History
```

---

## 🎯 How to Use ScreenGuard

### 1. Adding Blocked Words
1. Open ScreenGuard from the desktop or tray icon.
2. In the **Dashboard & Words** tab, click **`+ Add Word`**.
3. Type the word or phrase (e.g., `confidential_project` or `my_secret_token`) and press **OK**.
4. The list is automatically saved to `config/settings.json`.

### 2. Toggling Protection
- **From GUI**: Click the large **Stop Protection** / **Start Protection** button in the header.
- **From System Tray**: Right-click the ScreenGuard shield icon in the taskbar tray and click **Toggle Protection**.
- **Via Global Hotkey**: Press **`Ctrl + Shift + F10`** anywhere in Windows.

### 3. Adding Apps to Whitelist
If you have an application that should never be closed:
1. Go to the **App Whitelist** tab.
2. Click **`+ Add App`** and enter the process name (e.g., `notepad.exe` or `calculator.exe`).

### 4. Adjusting Performance & Speed
1. Navigate to the **Monitoring Settings** tab.
2. Under **Scan Speed Interval**, select your preferred mode:
   - **Lightning (100 ms)**: Fastest possible reaction; higher CPU usage.
   - **Fast (200 ms)**: Recommended for fast-paced browsing.
   - **Balanced (400 ms)**: Default balance of speed and low CPU usage.
   - **Battery Saver (1000 ms)**: Minimal CPU impact for laptops.

---

## 🧪 Testing ScreenGuard

To test detection with a sample file:
1. Ensure ScreenGuard protection is **ACTIVE**.
2. Open `test_files/sample_test.txt` in Notepad or your web browser.
3. The sample file contains `TEST_BLOCKED_WORD`.
4. ScreenGuard will immediately detect the word and close the window!

---

## ⚙ Configuration (`config/settings.json`)

Settings are stored locally in JSON format:

```json
{
    "enabled": true,
    "scan_interval_ms": 400,
    "ocr_confidence": 50,
    "match_mode": "exact_word",
    "monitor_mode": "active_window",
    "action": "graceful_close",
    "force_terminate": false,
    "force_terminate_timeout_ms": 500,
    "consecutive_detection": false,
    "selected_monitor": 0,
    "hotkey": "Ctrl+Shift+F10",
    "start_with_windows": false,
    "blocked_words": [
        "TEST_BLOCKED_WORD",
        "confidential_leak",
        "secret_token"
    ],
    "whitelist": [
        "ScreenGuard.exe",
        "explorer.exe",
        "Taskmgr.exe"
    ]
}
```

---

## 🔒 Privacy Guarantee

- **Zero Network Calls**: ScreenGuard has no network libraries and makes zero cloud connections.
- **No Screenshot Storage**: Screenshots are processed in volatile RAM and immediately discarded.
- **No Telemetry**: No tracking, analytics, or user accounts.

---

## ⚠️ Known Technical Limitations

OCR technology operates by visually reading rendered pixels on screen. Detection may be impacted if:
- Text is extremely small (e.g., < 8px font).
- Text is heavily blurred, distorted, or rendered in complex artistic cursive.
- Text is shown within fast-moving video frames before a scan cycle triggers.
- Protected Windows system processes cannot be closed to prevent operating system instability.

---

## 📄 License

MIT License. Free for personal and commercial use.
