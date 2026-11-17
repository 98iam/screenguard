# Build ScreenGuard: Fast Windows Word Detection & App Exit Utility

You are an expert Windows desktop software engineer.

Build a complete, working Windows application called **ScreenGuard**.

The user is a complete beginner and does not know programming. You must handle the technical implementation, dependency setup, testing, debugging, and packaging.

The purpose of ScreenGuard is extremely simple:

> Monitor visible screen content. If a user-defined blocked word or phrase is detected, immediately close the application/window currently displaying that content.

Do NOT blur the screen.

Do NOT show a blocking overlay.

Do NOT cover the detected word.

Do NOT merely warn the user.

The primary action after detection is:

**DETECT → IDENTIFY WINDOW → CLOSE APPLICATION**

---

# 1. Core Behavior

The application runs in the Windows background.

Example:

```text
User opens Chrome
        ↓
Chrome displays webpage
        ↓
ScreenGuard captures screen
        ↓
OCR detects text
        ↓
Blocked word found
        ↓
Identify the application/window
        ↓
Close that application
```

If the blocked word is detected while Chrome is the active application, close Chrome.

If the blocked word is detected while a PDF reader is active, close the PDF reader.

If the blocked word is detected while another application is active, close that application.

The application should react as quickly as reasonably possible.

---

# 2. Important Scope

The first version should focus on detecting **visible text**.

Do NOT attempt to understand the semantic meaning of an entire page.

Do NOT use a large AI vision model for basic detection.

Use fast local OCR.

The blocked-word system should work without an internet connection.

No screenshots should be uploaded anywhere.

No cloud OCR.

No external AI API.

---

# 3. Recommended Technology

Use Python for the first implementation because it allows rapid development and has mature libraries for:

* Windows screen capture
* OCR
* Windows window/process management
* System tray
* Global hotkeys
* Configuration
* Packaging

Target:

* Windows 10
* Windows 11

The final application should be packageable into:

```text
ScreenGuard.exe
```

The user should NOT need Python installed to use the final EXE.

---

# 4. Project Structure

Create a clean project.

Use approximately:

```text
ScreenGuard/
│
├── app/
│   ├── main.py
│   ├── config.py
│   ├── capture.py
│   ├── ocr.py
│   ├── detector.py
│   ├── window_manager.py
│   ├── monitor.py
│   ├── tray.py
│   ├── hotkeys.py
│   ├── logger.py
│   └── utils.py
│
├── config/
│   └── settings.json
│
├── tests/
│   ├── test_detector.py
│   ├── test_config.py
│   └── test_window_manager.py
│
├── requirements.txt
├── README.md
├── build.bat
├── run.bat
└── LICENSE
```

You may modify the architecture if you have a better implementation.

Keep the project simple.

Do not add unnecessary infrastructure.

---

# 5. Blocked Word System

The user must be able to create a list of blocked words and phrases.

Example:

```text
word1
word2
word3
blocked phrase
another phrase
```

Requirements:

* Case-insensitive matching
* Phrase matching
* Unicode support
* Ignore unnecessary whitespace differences
* Handle punctuation around words
* Avoid obvious false positives
* Support adding/removing words from the GUI
* Save the list permanently

Example:

If the blocked word is:

```text
cat
```

do not trigger simply because:

```text
education
```

contains similar characters.

Use proper word-boundary matching.

Allow an optional setting:

```text
Exact word matching
Substring matching
```

Default to exact word/phrase matching.

---

# 6. OCR Engine

Select the fastest practical local OCR solution for Windows.

Before implementing, evaluate suitable OCR engines and choose one that provides a good balance of:

* Speed
* Accuracy
* Installation simplicity
* Windows compatibility
* CPU usage
* Offline operation

Do not automatically choose a huge OCR/AI package.

The application should be lightweight.

OCR should return:

```text
recognized text
confidence
bounding boxes
```

when available.

---

# 7. Screen Capture

Implement efficient screen capture.

Requirements:

* Capture the screen without unnecessary delays.
* Support multiple monitors.
* Detect monitor resolution.
* Respect Windows DPI scaling.
* Avoid continuously creating unnecessary large image objects.
* Release resources correctly.
* Do not save screenshots to disk by default.

Allow the user to select:

```text
Monitor 1
Monitor 2
All monitors
```

Default:

```text
Active monitor
```

---

# 8. Detection Speed

Performance is extremely important.

Do not perform expensive OCR on the screen at maximum possible FPS.

Use an efficient scanning loop.

Default scan interval:

```text
400 ms
```

Allow:

```text
100 ms
200 ms
400 ms
700 ms
1000 ms
Custom
```

Explain to the user:

* Lower interval = faster detection but more CPU usage
* Higher interval = lower CPU usage but slower detection

Do not allow OCR jobs to pile up.

If an OCR operation is still running, intelligently handle the next frame rather than creating an unlimited queue.

---

# 9. Active Window Detection

Before OCR processing, determine the currently active foreground Windows application.

Record:

```text
Window title
Process ID
Process name
Executable path
Window handle
```

For example:

```text
Window:
Google Chrome

Process:
chrome.exe

PID:
12345

Window Handle:
0x000123
```

The OCR result should be associated with the foreground window being monitored.

---

# 10. Critical Safety Rule

Do NOT blindly terminate arbitrary Windows processes.

The application must distinguish between:

### Normal user applications

Examples:

```text
chrome.exe
msedge.exe
firefox.exe
notepad.exe
AcroRd32.exe
WINWORD.EXE
```

and:

### Protected/system processes

Never automatically terminate important Windows system processes such as:

```text
explorer.exe
csrss.exe
wininit.exe
services.exe
lsass.exe
smss.exe
svchost.exe
dwm.exe
System
System Idle Process
```

Create a protected-process safety list.

If the foreground window belongs to a protected process:

**Do not terminate it.**

Instead log:

```text
Blocked content detected, but protected process was not terminated.
```

---

# 11. Closing Behavior

When a blocked word is detected:

### First attempt

Gracefully close the application/window.

Use the normal Windows close mechanism where possible.

For example:

```text
WM_CLOSE
```

or equivalent Windows API behavior.

Wait a short configurable period.

Default:

```text
500 ms
```

### If the application refuses to close

Provide an optional setting:

```text
Force terminate if application does not close
```

Default:

```text
OFF
```

If enabled, terminate only the identified user application process.

Never force terminate protected/system processes.

---

# 12. False Positive Protection

OCR is imperfect.

Do not close applications because of a weak OCR result.

Use:

* OCR confidence threshold
* Word boundaries
* Phrase normalization
* Optional repeated-frame confirmation

Settings:

```text
OCR confidence:
Low
Medium
High
```

Default:

```text
Medium
```

Add an optional:

```text
Require detection in 2 consecutive scans
```

setting.

However, provide:

```text
Instant detection
```

for users who prioritize speed.

Default should balance speed and accuracy.

---

# 13. Action Settings

The application should primarily support:

```text
Action:

● Close application
○ Gracefully close, then force close
```

Do NOT implement blur or overlay as the default architecture.

Those are outside the main purpose of this application.

---

# 14. Application Whitelist

Provide a whitelist.

Example:

```text
Allowed applications:

notepad.exe
ScreenGuard.exe
calculator.exe
```

If an application is whitelisted:

```text
Detect word
     ↓
Do nothing
```

The application should continue monitoring normally.

Provide an easy GUI for adding/removing applications.

---

# 15. Monitoring Modes

Provide:

### Mode 1: Active Window

Only monitor the current foreground application.

This should be the default.

### Mode 2: Selected Applications

Allow the user to select applications to monitor.

### Mode 3: All User Applications

Monitor the foreground user application regardless of which application it is.

Do not monitor Windows system processes.

---

# 16. System Tray

ScreenGuard should run in the Windows system tray.

Tray menu:

```text
ScreenGuard

Protection: ON

Start Protection
Stop Protection

Open ScreenGuard

Settings

Exit
```

When protection is active, clearly show:

```text
● Protection ON
```

When disabled:

```text
○ Protection OFF
```

---

# 17. Main GUI

Create a clean modern Windows interface.

The main screen should be simple.

Example:

```text
SCREENguard

Protection
────────────────────

● ACTIVE

[ Stop Protection ]

Blocked Words
────────────────────

word1
word2
word3
blocked phrase

[ + Add Word ]

Monitoring
────────────────────

Mode:
[ Active Window ▼ ]

Scan speed:
[ Balanced ▼ ]

OCR confidence:
[ Medium ▼ ]

Action:
[ Close Application ]

Statistics
────────────────────

Detections:
12

Applications closed:
12

Current monitor:
Monitor 1

CPU:
8%

Memory:
85 MB
```

Do not create a complicated enterprise dashboard.

Prioritize clarity.

---

# 18. Add Word Interface

The user should be able to click:

```text
+ Add Word
```

Then see:

```text
Add blocked word or phrase

[________________________]

[ Cancel ]   [ Add ]
```

After adding it, immediately save the configuration.

Allow deleting words.

Allow editing words.

---

# 19. Detection History

Provide a small local history.

Example:

```text
Detection History

19:01:32
Detected: word1
Application: chrome.exe
Action: Closed

19:04:12
Detected: blocked phrase
Application: WINWORD.EXE
Action: Closed
```

Do NOT store screenshots.

Do NOT store unnecessary sensitive text.

Allow:

```text
Clear History
```

---

# 20. Privacy

This is a local privacy utility.

Requirements:

* No cloud services
* No telemetry
* No analytics
* No screenshots uploaded
* No OCR data uploaded
* No remote server
* No account required

Everything should remain on the user's Windows computer.

---

# 21. Configuration

Use a simple local JSON configuration.

Example:

```json
{
    "enabled": true,
    "scan_interval": 400,
    "ocr_confidence": 70,
    "monitor_mode": "active_window",
    "action": "close_application",
    "force_terminate": false,
    "consecutive_detection": false,
    "blocked_words": [],
    "whitelist": []
}
```

Do not use a database unless there is a strong technical reason.

---

# 22. Global Hotkey

Add a global keyboard shortcut.

Default:

```text
Ctrl + Shift + F10
```

Function:

```text
Protection ON
        ↕
Protection OFF
```

Allow the user to change the shortcut.

Do not interfere with normal keyboard input.

---

# 23. Start With Windows

Provide an option:

```text
[ ] Start ScreenGuard automatically with Windows
```

Do not enable this automatically.

Use an appropriate Windows startup mechanism.

---

# 24. Performance Requirements

The application should aim for:

* Low idle CPU usage
* Low RAM usage
* Fast detection
* No UI freezing
* No memory leaks
* No screenshot accumulation
* No OCR queue buildup

When protection is OFF:

```text
OCR = OFF
Screen capture = OFF
```

The GUI/tray can remain running.

When protection is ON:

```text
Capture → OCR → Match → Close
```

Run monitoring work outside the GUI thread.

---

# 25. Error Handling

Handle:

* OCR engine missing
* OCR engine crash
* Screen capture failure
* Monitor disconnection
* Invalid settings
* Permission problems
* Application already closed
* Application refuses to close
* DPI changes
* Multiple monitors
* Empty blocked-word list
* Invalid hotkey
* Unexpected exceptions

Never allow a background exception to silently kill protection.

Show a clear error message.

---

# 26. Logging

Use lightweight local logging.

Example:

```text
[19:01:32] Protection started
[19:01:40] Monitoring chrome.exe
[19:01:42] Blocked word detected
[19:01:42] Attempting graceful close
[19:01:43] Application closed
```

Do not log full screen contents.

Do not save screenshots.

Make logging configurable.

---

# 27. Security

Do not require administrator privileges unless absolutely necessary.

Prefer normal Windows-user permissions.

If a particular application cannot be closed without elevated privileges, explain the limitation.

Do not create persistence mechanisms beyond the user's explicit startup setting.

Do not modify unrelated Windows settings.

---

# 28. Testing

Create automated unit tests for:

* Case-insensitive matching
* Exact word matching
* Phrase matching
* Punctuation
* Whitespace
* Unicode
* False-positive prevention
* Configuration
* Whitelist
* Protected process detection

Create manual tests for:

```text
Chrome
Edge
Firefox
Notepad
Microsoft Word
PDF reader
Image viewer
Video player
Multiple monitors
100% DPI
125% DPI
150% DPI
1920x1080
2560x1440
3840x2160
```

---

# 29. Important Test Scenario

Create a simple test page/file containing:

```text
NORMAL TEXT

TEST_BLOCKED_WORD

MORE NORMAL TEXT
```

Add:

```text
TEST_BLOCKED_WORD
```

to the ScreenGuard blocked list.

Open the test content.

Expected behavior:

```text
OCR
 ↓
TEST_BLOCKED_WORD detected
 ↓
Current application identified
 ↓
Application closes
```

This should be tested before declaring the application complete.

---

# 30. Build System

Create:

```text
build.bat
```

The command should build a standalone Windows executable.

Example final output:

```text
dist/
└── ScreenGuard.exe
```

The user should be able to double-click the EXE.

The EXE should not require the user to install Python.

If the OCR engine requires additional runtime files, package them properly or provide a simple first-run setup.

Do not leave the user manually configuring complicated dependencies.

---

# 31. Installation

Create a beginner-friendly installation process.

The final user experience should ideally be:

```text
Install ScreenGuard
        ↓
Open ScreenGuard
        ↓
Add blocked words
        ↓
Start Protection
        ↓
Done
```

No command-line knowledge should be required for normal use.

---

# 32. README

Create a very clear README explaining:

* What ScreenGuard does
* How it detects text
* How OCR works
* How to install
* How to add blocked words
* How to enable protection
* How to configure speed
* How to whitelist an application
* How to build from source
* How to troubleshoot OCR
* How to uninstall
* Privacy behavior
* Known limitations

Use beginner-friendly language.

---

# 33. Known Technical Limitation

Clearly document that OCR cannot guarantee perfect detection.

Detection may fail when:

* Text is extremely small
* Text is heavily distorted
* Text has unusual fonts
* Text is hidden inside complex graphics
* Video is moving quickly
* The screen is blurry
* OCR confidence is poor

Do not claim 100% detection accuracy.

---

# 34. Do Not Over-Engineer

Do NOT add:

* Cloud APIs
* AI vision APIs
* Databases
* Web servers
* Electron
* Docker
* Kubernetes
* Remote services
* User accounts
* Telemetry
* Analytics

unless there is a genuinely necessary technical reason.

The goal is a small, fast Windows utility.

---

# 35. Development Instructions

Work autonomously through the implementation.

First:

1. Inspect the available Windows/Python environment.
2. Check Python version.
3. Check whether required tools are installed.
4. Select the OCR engine.
5. Explain briefly why you selected it.
6. Create the project.
7. Install only required dependencies.
8. Implement the application.
9. Run tests.
10. Launch the application.
11. Test the detection system.
12. Fix errors.
13. Optimize performance.
14. Build the EXE.
15. Verify the EXE.

Do not stop after generating source code.

Actually run and test the project whenever the environment allows it.

If you encounter an error, diagnose and fix it rather than asking the beginner to debug it manually.

---

# 36. Final Deliverables

The finished project must contain:

```text
ScreenGuard/
│
├── source code
├── GUI
├── OCR engine
├── screen monitoring
├── word matching
├── window identification
├── application closing
├── system tray
├── settings
├── whitelist
├── logging
├── tests
├── requirements.txt
├── README.md
├── run.bat
└── build.bat
```

And preferably:

```text
dist/
└── ScreenGuard.exe
```

The final application must prioritize:

**FAST DETECTION**

**FAST APPLICATION CLOSING**

**LOW CPU/RAM USAGE**

**LOCAL PROCESSING**

**SIMPLE USER EXPERIENCE**

The core behavior is:

```text
                 SCREEN
                    ↓
              FAST CAPTURE
                    ↓
                  OCR
                    ↓
            BLOCKED WORD?
              ↙         ↘
            NO           YES
            ↓             ↓
         Continue    Identify Window
                           ↓
                    Is it protected?
                      ↙         ↘
                    YES          NO
                    ↓             ↓
                 Do NOT       Close App
                 terminate        ↓
                              Log event
```

Build this as a real working Windows utility, not a mockup or pseudocode demonstration.
