# ScreenGuard Comprehensive Bug, Flaw & Vulnerability Report

This document outlines all identified bugs, architectural flaws, security vulnerabilities, memory/resource leaks, and dead code discovered across the ScreenGuard project (Windows desktop utility, Android child/parent applications, Chrome extension, and cloud infrastructure).

---

## Table of Contents

- [Summary Matrix](#summary-matrix)
- [Critical Bugs & Security Vulnerabilities (Must Fix)](#critical-bugs--security-vulnerabilities-must-fix)
  - [1. Family DNS VPN Breaks Device Internet via Missing IPv4 Checksum](#1-family-dns-vpn-breaks-device-internet-via-missing-ipv4-checksum)
  - [2. DNS Forwarder Thread Blocks on Lost Queries and Misroutes UDP Replies](#2-dns-forwarder-thread-blocks-on-lost-queries-and-misroutes-udp-replies)
  - [3. Crash from Recycling Bitmaps While Background Upload Threads Are Reading Them](#3-crash-from-recycling-bitmaps-while-background-upload-threads-are-reading-them)
  - [4. Public Firebase Database & Storage Rules Expose All Data to Anyone](#4-public-firebase-database--storage-rules-expose-all-data-to-anyone)
  - [5. Desktop Website Whitelist Is Completely Ignored During Screen Word Scanning](#5-desktop-website-whitelist-is-completely-ignored-during-screen-word-scanning)
  - [6. Desktop IPC Server CORS Wildcard & Missing Auth Exposes Active Windows to Malicious Webpages](#6-desktop-ipc-server-cors-wildcard--missing-auth-exposes-active-windows-to-malicious-webpages)
  - [7. Close Signal Spam Loop When Applications Do Not Close Instantly](#7-close-signal-spam-loop-when-applications-do-not-close-instantly)
- [Architecture, Performance & Logic Flaws (Should Fix)](#architecture-performance--logic-flaws-should-fix)
  - [8. ModernGUI Crashes Calling Non-Existent Site Whitelist Tab](#8-moderngui-crashes-calling-non-existent-site-whitelist-tab)
  - [9. Desktop FocusManager Reads and Parses Disk JSON Every Second](#9-desktop-focusmanager-reads-and-parses-disk-json-every-second)
  - [10. Browser Extension Worker Suspends After 30s, Draining Allowance on Whitelisted Study Sites](#10-browser-extension-worker-suspends-after-30s-draining-allowance-on-whitelisted-study-sites)
  - [11. Heavy Bitmap Manipulation Executed on Android Main UI Thread](#11-heavy-bitmap-manipulation-executed-on-android-main-ui-thread)
  - [12. Double Incident Dispatch When Child Device Is Not Paired](#12-double-incident-dispatch-when-child-device-is-not-paired)
  - [13. Expired Pairing Codes Are Never Cleaned Up in Firebase](#13-expired-pairing-codes-are-never-cleaned-up-in-firebase)
  - [14. 2,620 Lines of Dead Legacy Android Activities Compiled into Both APKs](#14-2620-lines-of-dead-legacy-android-activities-compiled-into-both-apks)
  - [15. Duplicate Entrypoint main.py at Repository Root Out of Sync](#15-duplicate-entrypoint-mainpy-at-repository-root-out-of-sync)
- [Minor Flaws & Usability Enhancements (Nice to Have)](#minor-flaws--usability-enhancements-nice-to-have)
  - [16. Autostart Registry Launches python.exe Instead of pythonw.exe](#16-autostart-registry-launches-pythonexe-instead-of-pythonwexe)
  - [17. Floating Desktop Widget Steals Focus and Flickers Every Second](#17-floating-desktop-widget-steals-focus-and-flickers-every-second)
- [Additional Android Findings — Deep Audit (18–54)](#additional-android-findings--deep-audit-1854)

---

## Summary Matrix

| ID | Title | Component | Severity | Impact |
|---|---|---|---|---|
| **1** | Missing IPv4 Checksum in VPN Proxy | Android | Critical | Total loss of DNS resolution on child device |
| **2** | Head-of-Line Blocking in DNS Socket Forwarder | Android | Critical | Severe network lag, packet misrouting |
| **3** | Bitmap Recycling Race Condition / Crash | Android | Critical | App crash on every Vision AI detection |
| **4** | Public Firebase RTDB & Storage Security Rules | Cloud / Infra | Critical | Data theft, screenshot leak, unauthorized rule wipe |
| **5** | Desktop Site Whitelist Ignored in Screen Scanner | Windows Desktop | Critical | Whitelisted websites still get force-closed |
| **6** | IPC HTTP Server Info Disclosure & Origin Bypass | Windows Desktop | Critical | Any webpage can spy on active window titles |
| **7** | Rapid WM_CLOSE Spam on Unresponsive Windows | Windows Desktop | High | Event log flooding, infinite close loop |
| **8** | GUI AttributeError on Uninitialized Tab | Windows Desktop | High | Crash if site whitelist tab function is called |
| **9** | FocusManager 1Hz Disk Read/Write Thrashing | Windows Desktop | Medium | 86,400 disk I/O reads/day, battery drain |
| **10** | MV3 Extension Worker Suspension Drains Time | Chrome Extension | Medium | Study websites drain 1-hour focus budget |
| **11** | Bitmap Processing on Main UI Thread | Android | Medium | UI stutter, dropped frames, potential ANR |
| **12** | Duplicate Incident Uploads When Unpaired | Android | Medium | Double network bandwidth and storage costs |
| **13** | Pairing Code Accumulation & Namespace Leaks | Android / Firebase | Low | Risk of pairing code collisions |
| **14** | 2,620 Lines of Dead Android Code in Release APKs | Android | Low | Bloated APK size, code confusion |
| **15** | Duplicate Root `main.py` vs `app/main.py` | Windows Desktop | Low | Divergent entry points, confusing maintenance |
| **16** | Autostart Registry Launches Console Window | Windows Desktop | Low | Intrusive command prompt window on startup |
| **17** | Widget 1Hz `SetWindowPos` Stutter/Flicker | Windows Desktop | Low | Full-screen app micro-stutter / focus theft |

### Additional Android Findings (Deep Audit, 18–54)

| ID | Title | Severity | Impact |
|---|---|---|---|
| **18** | Parent pairing listener removed immediately | Critical | Parent never observes a successful pairing |
| **19** | Parent-approved pause never reaches child | Critical | Break/pause feature is a no-op |
| **20** | Block screenshot uses HARDWARE bitmap | Critical | Every word-block screenshot is lost |
| **21** | `allowBackup` with no backup rules | High | PIN hash and prefs can be backed up/exfiltrated |
| **22** | Static-salt PIN hashing | High | 4–6 digit PIN brute-forced offline in seconds |
| **23** | Vision loop ignores pause and screen-off | High | Screenshots every 2 s during approved break |
| **24** | Child rules listener never retried | High | Background rules stop applying after activity dies |
| **25** | `blockIncognito`/`familyDns` never applied | High | Remote toggles are silently ignored |
| **26** | Overbroad default blocked words | High | Browser closed by cookie banners/news text |
| **27** | Unpair clears parent locally only | High | Child keeps uploading after "unpair" |
| **28** | Cloudflare device ID mismatch | Medium | Devices cannot be correlated across backends |
| **29** | Missing `<queries>` declaration | High | Home-launcher detection fails on Android 11+ |
| **30** | Play Store toggle state desync | Medium | Toggle disagrees with actual blocked packages |
| **31** | VPN reports "Active" when tunnel failed | High | User believes filtering is on while DNS is broken |
| **32** | Pairing is unauthenticated/hijackable | Critical | Attacker can pair a child to themselves |
| **33** | Firebase request ID sent to Cloudflare | Medium | Cloudflare approvals fail or target wrong request |
| **34** | `confThreshold` ignored; 0.05 compound hints | Medium | Untunable, high false-positive vision blocking |
| **35** | Background polling loops | Medium | Battery drain while activity is stopped |
| **36** | Stale `MainActivity` state | Low | Stale unlock/pause UI (dead code) |
| **37** | DNS-only filtering is bypassable | Medium | DoH/DoT bypasses the family filter |
| **38** | Screenshots never deleted | Medium | Unbounded public storage growth |
| **39** | Incident history capped at 60 | Medium | Older incidents silently disappear |
| **40** | Notification permission never requested | Medium | Service notification hidden on Android 13+ |
| **41** | `startForeground` failure swallowed | Medium | Half-started VPN, service killed |
| **42** | No PIN gate on disabling protection | High | Child can switch protection off |
| **43** | `commit()` on caller thread | Low | Main-thread disk write on first run |
| **44** | Screenshot cadence mismatch | Low | Duplicate blocks/incidents |
| **45** | 12 MB ONNX model fully buffered | Medium | Memory spike / silent vision disable |
| **46** | No tests for risky subsystems | Low | Pairing/VPN/rules regressions undetected |
| **47** | Accessibility service broadly exported | Medium | Privacy exposure amplified by #4 |
| **48** | DNS forwarder: no backoff, fixed buffer, no TC | Medium | Large replies fail; CPU spin on errors |
| **49** | Launcher cache refreshed every event | Low | Binder IPC on each accessibility event |
| **50** | `TEST_BLOCKED_WORD` ships by default | Low | Placeholder causes false blocks |
| **51** | Misspelled duplicate blocklist entries | Low | Noise/maintenance |
| **52** | `String.format` without `Locale` | Low | Non-ASCII digits in some locales |
| **53** | ONNX `.so` not 16 KB aligned | Low | Load failure on 16 KB-page devices |
| **54** | Release build unminified | Low | Larger APK, easier reverse engineering |

---

## Critical Bugs & Security Vulnerabilities (Must Fix)

### 1. Family DNS VPN Breaks Device Internet via Missing IPv4 Checksum
- **Location:** `android/app/src/main/java/com/example/screenguard/service/FamilyDnsVpnService.kt:L111-137`
- **What this is:** An Android local VPN service that intercepts UDP DNS traffic on port 53 and proxies requests to Cloudflare Family DNS (`1.1.1.3`) to block adult domains.
- **Problem:** When constructing the synthetic IPv4 response packet to write back to the TUN virtual interface (`outputStream.write(respPacketBytes)`), bytes 10 and 11 (the IPv4 header checksum field) are left zeroed (`0x0000`). The Linux and Android kernel IP stack verifies the header checksum on incoming TUN packets. Because the checksum is invalid, the kernel drops the packet silently.
- **Concrete Fix:**
  Compute the standard 16-bit one's complement sum of the 20-byte IP header and write it into `respPacketBytes[10]` and `respPacketBytes[11]`:
  ```kotlin
  // Compute IPv4 header checksum (words 0..9)
  var sum = 0
  for (i in 0 until 20 step 2) {
      if (i != 10) {
          sum += ((respPacketBytes[i].toInt() and 0xFF) shl 8) or (respPacketBytes[i + 1].toInt() and 0xFF)
      }
  }
  while (sum shr 16 > 0) {
      sum = (sum and 0xFFFF) + (sum shr 16)
  }
  val checksum = (sum.inv()) and 0xFFFF
  respPacketBytes[10] = (checksum shr 8).toByte()
  respPacketBytes[11] = (checksum and 0xFF).toByte()
  ```
- **If we skip it:** As soon as Family DNS VPN connects, all internet lookups on the child device fail completely.

---

### 2. DNS Forwarder Thread Blocks on Lost Queries and Misroutes UDP Replies
- **Location:** `android/app/src/main/java/com/example/screenguard/service/FamilyDnsVpnService.kt:L79-146`
- **What this is:** The loop forwarding DNS packets between the local TUN interface and the upstream DNS server over UDP.
- **Problem:**
  1. It uses a single `DatagramSocket` in a synchronous lockstep loop (`read()` from TUN -> `send()` to upstream -> `receive()` from upstream).
  2. The socket timeout is set to `2500ms`. If any DNS query packet is dropped or delayed on the network, the thread blocks for 2.5 seconds. During this time, no other DNS queries can be read from the TUN interface, freezing all app network activity across the entire device.
  3. If responses arrive out of order (e.g. query 2 finishes before query 1), the response is wrapped with query 1's source IP and port from `buffer`, routing mismatched answers to applications.
- **Concrete Fix:**
  Decouple packet intake and packet receiving into two separate coroutines or threads, using a concurrent cache (`ConcurrentHashMap<Int, QueryMetadata>`) keyed by the 16-bit DNS Transaction ID to restore the correct client port and destination IP upon reply arrival.
- **If we skip it:** The child experiences severe internet lag and constant DNS resolution timeouts during routine browsing.

---

### 3. Crash from Recycling Bitmaps While Background Upload Threads Are Reading Them
- **Location:** `android/app/src/main/java/com/example/screenguard/service/ScreenGuardAccessibilityService.kt:L520-569`
- **What this is:** The background vision scan routine that runs YOLOv8 NudeNet on screenshots and uploads incident proofs.
- **Problem:**
  In `checkScreenForExplicitVision`:
  ```kotlin
  dispatchToBackends(
      targetPkg = targetPkg,
      appLabel = appLabel,
      matchedWord = "[AI VISION: ...]",
      bitmap = fullScaledBitmap ?: cropScaledBitmap
  )
  ...
  } finally {
      try { cropScaledBitmap?.recycle() } catch (ignored: Exception) {}
      try { fullScaledBitmap?.recycle() } catch (ignored: Exception) {}
      isVisionScanning = false
  }
  ```
  `dispatchToBackends` delegates to `FamilyRepository.pushIncident` and `FirebaseIncidentDispatcher.dispatchIncident`, both of which launch asynchronous coroutines (`scope.launch(Dispatchers.IO) { ... }`). The `finally` block runs immediately and calls `.recycle()`, while the background thread calls `scaled.compress(...)` on the recycled bitmap.
- **Concrete Fix:**
  Do not call `.recycle()` on bitmaps passed to `dispatchToBackends`, or compress the bitmap into a `ByteArray` synchronously before dispatching.
- **If we skip it:** The accessibility service crashes with `IllegalStateException: Can't compress a recycled bitmap` or a native SIGSEGV crash every time explicit visual content is caught.

---

### 4. Public Firebase Database & Storage Rules Expose All Data to Anyone
- **Location:** `database.rules.json:L1-24`, `storage.rules:L1-8`
- **What this is:** Cloud security policies controlling access to Firebase Realtime Database and Cloud Storage.
- **Problem:**
  Both files specify `.read: true, .write: true` globally:
  ```json
  {
    "rules": {
      "pairings": { ".read": true, ".write": true },
      "families": { ".read": true, ".write": true },
      "incidents": { ".read": true, ".write": true },
      "devices": { ".read": true, ".write": true }
    }
  }
  ```
  Anyone who extracts the Firebase project ID from `google-services.json` inside the APK can connect with any REST client or SDK without logging in and read all incident logs, download all captured screen thumbnails, or delete pairing records and parental rules.
- **Concrete Fix:**
  Implement Firebase Authentication (anonymous or email/password) and scope read/write permissions so that children can only write their own incident records and parents can only read/write their paired family tree:
  ```json
  {
    "rules": {
      "devices": {
        "families": {
          "$parentId": {
            ".read": "auth != null && auth.uid == $parentId",
            ".write": "auth != null && auth.uid == $parentId"
          }
        }
      }
    }
  }
  ```
- **If we skip it:** Anyone on the internet can read captured screenshots of the child's screen and manipulate parental control locks.

---

### 5. Desktop Website Whitelist Is Completely Ignored During Screen Word Scanning
- **Location:** `app/monitor.py:L185-189`, `app/window_manager.py:L141-161`
- **What this is:** The whitelist feature in ScreenGuard intended to allow safe sites (e.g., `google.com`, `wikipedia.org`, `github.com`) to never be closed.
- **Problem:**
  `WindowManager.is_whitelisted()` explicitly checks process executable names only and does not check `custom_site_whitelist`. Furthermore, `Monitor._run_loop()` never invokes `WindowManager.is_site_whitelisted()`. As a result, if a blocked word appears on any whitelisted website in Chrome or Edge, ScreenGuard closes the browser anyway.
- **Concrete Fix:**
  In `app/monitor.py:L185`, check `self.window_mgr.is_site_whitelisted(win_info.title)` before scanning screen OCR text:
  ```python
  if self.window_mgr.is_site_whitelisted(win_info.title):
      self.detector.clear_state()
      last_frame_hash = None
      time.sleep(effective_interval_sec)
      continue
  ```
- **If we skip it:** Users adding websites to the whitelist find that their browser still gets closed when those sites display blocked keywords.

---

### 6. Desktop IPC Server CORS Wildcard & Missing Auth Exposes Active Windows to Malicious Webpages
- **Location:** `app/main.py:L207-241`
- **What this is:** The local HTTP server on `127.0.0.1:49221` used for communication with the Chrome extension and single-instance IPC.
- **Problem:**
  `_is_origin_allowed` returns `True` when the `Origin` header is absent or `"null"`. Additionally, `_send_cors_headers` outputs `Access-Control-Allow-Origin: *` when no origin is present. Any webpage visited by the user in any browser can execute a background fetch to `http://127.0.0.1:49221/api/status` to read the active window title, running process name, and focus usage statistics, or send POST requests to `/api/heartbeat` to spoof tracking.
- **Concrete Fix:**
  Generate a random session token stored in local user app data that the Chrome extension must send in an `X-ScreenGuard-Token` header, and strictly reject any HTTP request missing the token or bearing an unauthorized origin.
- **If we skip it:** Any malicious website visited by the user can secretly track the user's active window titles, applications, and browsing time.

---

### 7. Close Signal Spam Loop When Applications Do Not Close Instantly
- **Location:** `app/monitor.py:L305-318`, `app/window_manager.py:L257-293`
- **What this is:** The routine that sends `WM_CLOSE` to a window displaying a blocked keyword.
- **Problem:**
  When `force_terminate` is `False` (the default setting), `close_window()` sends `WM_CLOSE`. If the application takes longer than 200ms to exit (or presents a modal dialog such as "Save before exiting?"), `close_window()` returns `True` because the message was posted. However, on the very next monitor iteration (100–400ms later), the window is still in the foreground with the blocked word visible. Because `last_frame_hash` was reset to `None`, ScreenGuard repeats the OCR scan and sends another `WM_CLOSE` every loop cycle.
- **Concrete Fix:**
  Add a cooldown dictionary `_recently_closed_windows: Dict[int, float]` in `WindowManager`. If a `hwnd` was signaled within the last 2.5 seconds, skip sending further close messages.
- **If we skip it:** Applications get bombarded with dozens of `WM_CLOSE` messages, and the detection event log is flooded with duplicate entries.

---

## Architecture, Performance & Logic Flaws (Should Fix)

### 8. ModernGUI Crashes Calling Non-Existent Site Whitelist Tab
- **Location:** `app/gui.py:L688-723`
- **What this is:** The `_build_site_whitelist_tab()` method in the desktop GUI.
- **Problem:**
  The method attempts to attach widgets to `self.tab_site_whitelist`:
  ```python
  header = ttk.Frame(self.tab_site_whitelist, style="Card.TFrame")
  ```
  `self.tab_site_whitelist` was never initialized in `_build_ui()`. Calling this method raises `AttributeError: 'ModernGUI' object has no attribute 'tab_site_whitelist'`. Furthermore, the site whitelist table is already built in `_build_focus_tab()`.
- **Concrete Fix:**
  Delete `_build_site_whitelist_tab()` completely.
- **If we skip it:** Broken dead code remains in the GUI codebase that crashes if triggered.

---

### 9. Desktop FocusManager Reads and Parses Disk JSON Every Second
- **Location:** `app/focus_manager.py:L400-410`
- **What this is:** The day-rollover check inside the 1-second monitoring loop.
- **Problem:**
  Every single second, `FocusManager._run_loop()` executes:
  ```python
  current_day = get_focus_day()
  file_path = self._get_data_file_path()
  if os.path.exists(file_path):
      with open(file_path, "r", encoding="utf-8") as f:
          data = json.load(f)
  ```
  Opening, reading, and parsing a JSON file from disk 86,400 times a day causes unnecessary disk wear, CPU wakeups, and battery drain.
- **Concrete Fix:**
  Keep `_current_day` in memory. Only compare `get_focus_day() != self._current_day`, and only read from or write to disk when a rollover occurs.
- **If we skip it:** Constant 1Hz disk I/O thrashing on the host machine.

---

### 10. Browser Extension Worker Suspends After 30s, Draining Allowance on Whitelisted Study Sites
- **Location:** `extension/background.js:L161-164`, `app/focus_manager.py:L323-333`
- **What this is:** The Manifest V3 background service worker syncing active tab URLs with the desktop app.
- **Problem:**
  Chrome automatically suspends idle MV3 service workers after 30 seconds of inactivity, which stops `setInterval()`. When a user spends more than 6 seconds reading a long article on a whitelisted site without switching tabs, heartbeats stop. `FocusManager` then falls back to window title matching. Because modern browser titles often omit the domain (e.g. "Article Title - Google Chrome"), `is_site_whitelisted()` returns `False` and the 1-hour daily budget starts draining.
- **Concrete Fix:**
  Have `FocusManager` retain the last reported domain for that window handle as long as the browser window remains focused, and use `chrome.alarms` in the extension.
- **If we skip it:** Students quietly reading textbooks or documentation on whitelisted platforms have their focus allowance drained.

---

### 11. Heavy Bitmap Manipulation Executed on Android Main UI Thread
- **Location:** `android/app/src/main/java/com/example/screenguard/service/ScreenGuardAccessibilityService.kt:L480-515`
- **What this is:** The callback handling screenshot results for the visual AI screen analysis.
- **Problem:**
  The `TakeScreenshotCallback` executes on `applicationContext.mainExecutor` (the main Android UI thread). Within this callback, it performs:
  1. `rawBitmap.copy(Bitmap.Config.ARGB_8888, false)` (10–18 MB full-screen buffer copy).
  2. `Bitmap.createScaledBitmap(softCopy, 320, 320, true)`
  3. `Bitmap.createBitmap(softCopy, 0, startY, cropSize, cropSize)`
  4. Another `Bitmap.createScaledBitmap(...)`
  Executing heavy image allocations and scaling on the main thread every 1.5–2 seconds causes frame drops and risks triggering an ANR (Application Not Responding) crash.
- **Concrete Fix:**
  Immediately dispatch the buffer processing and scaling to `Dispatchers.Default` inside a coroutine.
- **If we skip it:** Severe UI stutter, dropped animations, and potential Accessibility Service kills by the OS.

---

### 12. Double Incident Dispatch When Child Device Is Not Paired
- **Location:** `android/app/src/main/java/com/example/screenguard/service/ScreenGuardAccessibilityService.kt:L692-716`
- **What this is:** The routine that notifies parents and Firebase when content is blocked.
- **Problem:**
  `executeAppBlockWithScreenshot()` calls both `FamilyRepository.pushIncident()` and `FirebaseIncidentDispatcher.dispatchIncident()`. When the child is not paired, both methods write separate records with different UUIDs to the same `/incidents` database path.
- **Concrete Fix:**
  Check `prefs.isPairedWithParent()`. If paired, call `FamilyRepository.pushIncident()`; otherwise, call `FirebaseIncidentDispatcher.dispatchIncident()`.
- **If we skip it:** Every violation generates two database entries and uploads two copies of the screenshot, doubling network and storage costs.

---

### 13. Expired Pairing Codes Are Never Cleaned Up in Firebase
- **Location:** `android/app/src/main/java/com/example/screenguard/network/PairingManager.kt:L41-57`
- **What this is:** The 6-digit session codes generated to pair child and parent devices.
- **Problem:**
  `createPairingCode` writes to `devices/pairings/{rawCode}`. Once generated, these records are never removed, even after expiration or successful pairing. Over time, the 6-digit namespace fills up, increasing the likelihood of collisions.
- **Concrete Fix:**
  Delete the code node upon successful completion of `linkChildWithCode`, and implement a periodic cleanup for expired sessions.
- **If we skip it:** Stale pairing entries pollute the database and random code collisions increase over time.

---

### 14. 2,620 Lines of Dead Legacy Android Activities Compiled into Both APKs
- **Location:** `android/app/src/main/java/com/example/screenguard/MainActivity.kt` (1,845 lines), `android/app/src/main/java/com/example/screenguard/GuardianActivity.kt` (775 lines)
- **What this is:** Legacy monolithic activities from v1.0 that were superseded by `ChildMainActivity` and `ParentMainActivity`.
- **Problem:**
  Both activities are completely unused by the `child` and `parent` flavor manifests, but are still compiled into both APKs, inflating download sizes and confusing future maintenance.
- **Concrete Fix:**
  Delete both legacy activity files and remove their declarations from `android/app/src/main/AndroidManifest.xml`.
- **If we skip it:** 2,620 lines of dead code bloat the release APKs and create technical debt.

---

### 15. Duplicate Entrypoint main.py at Repository Root Out of Sync
- **Location:** `main.py` vs `app/main.py`
- **What this is:** Two identical 408-line copies of the desktop entry point located at the root and inside `app/`.
- **Problem:**
  `build.bat`, `run.bat`, and `ScreenGuard.spec` execute `app/main.py`. Any modifications or bug fixes made to the root `main.py` have zero effect on builds or execution.
- **Concrete Fix:**
  Remove the root `main.py` and standardize all scripts on `app/main.py`.
- **If we skip it:** Developers will mistakenly edit root `main.py` without realizing it is not executed by the build scripts.

---

## Minor Flaws & Usability Enhancements (Nice to Have)

### 16. Autostart Registry Launches python.exe Instead of pythonw.exe
- **Location:** `app/autostart.py:L21-23`
- **What this is:** Windows startup registry configuration for launch-at-login.
- **Problem:**
  When running from source, `get_executable_command()` registers `sys.executable` (`python.exe`). On Windows boot, a blank black console window appears and remains on screen.
- **Concrete Fix:**
  Detect if running from Python source and substitute `pythonw.exe` for `python.exe` so the process starts without a console window.
- **If we skip it:** Users running the Python version see a permanent black command prompt window every time Windows boots.

---

### 17. Floating Desktop Widget Steals Focus and Flickers Every Second
- **Location:** `app/widget.py:L111-120`
- **What this is:** The desktop timer pill displaying remaining focus minutes.
- **Problem:**
  Every second in `_tick()`, the widget executes `deiconify()`, `attributes("-topmost", True)`, `lift()`, and calls Win32 `SetWindowPos()`. Calling these repeatedly can cause visual micro-flickering and steal focus from full-screen applications or games.
- **Concrete Fix:**
  Configure topmost status and window positioning once during widget creation, and only update label text during 1Hz ticks.
- **If we skip it:** Full-screen games and video players may experience micro-stuttering or focus loss every second.

---

## Additional Android Findings — Deep Audit (18–54)

> These entries were added after a line-by-line re-audit of the Android sources at `00b09d7`. Findings already covered above are intentionally **not repeated**: #1 (IPv4 checksum), #2 (DNS head-of-line blocking/misroute), #3 (bitmap recycle), #4 (open Firebase rules), #11 (main-thread bitmap work), #12 (double dispatch), #13 (pairing cleanup), #14 (dead activities).
>
> Note on #12: the duplicate write also happens while the child **is** paired — `FamilyRepository` writes the family node and `FirebaseIncidentDispatcher` writes the global `/incidents` node — so the duplication is not limited to unpaired devices.

### 18. Parent Pairing Listener Is Removed the Moment the Code Appears
- **Location:** `android/app/src/main/java/com/example/screenguard/ui/ParentMainActivity.kt:L114-124`
- **Problem:** The `DisposableEffect` is keyed on `pairingCode`. When a code is generated, Compose disposes the previous effect; `onDispose` reads the **updated** `pairingCode` and removes `listenerRef` — the listener that was just registered for that same code.
- **Impact:** The parent never observes `status == "PAIRED"`. `onPaired` never fires and onboarding can never complete, even though the child writes `PAIRED`.
- **Suggested direction:** Key the effect on `Unit` (or capture the code in a local `val`) and only remove the listener when leaving the screen; register the listener outside the keyed effect.

### 19. Parent-Approved Pause Never Reaches the Child
- **Location:** `android/app/src/main/java/com/example/screenguard/network/FirebaseIncidentDispatcher.kt:L168` (definition only); writer at `GuardianActivity.kt:L228-229`
- **Problem:** `attachPauseStatusListener` has zero call sites. The guardian writes `devices/{deviceId}/isPaused` and `pauseUntil`, but no child component listens to that node.
- **Impact:** The "Break"/pause approval is a no-op on the child device.
- **Suggested direction:** Attach the listener from the child service/activity at startup (and after pairing), or have the service observe `devices/{deviceId}` directly.

### 20. Word-Block Screenshot Path Still Feeds a HARDWARE Bitmap to Software APIs
- **Location:** `android/app/src/main/java/com/example/screenguard/service/ScreenGuardAccessibilityService.kt:L639-652`
- **Problem:** `Bitmap.wrapHardwareBuffer()` returns `Config.HARDWARE` on API 30+. The code then calls `Bitmap.createScaledBitmap(rawBitmap, …)` and `rawBitmap.copy(…)`, both of which throw `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`. The exception is caught and `bitmap` stays `null`.
- **Impact:** Every word-triggered block loses its screenshot; only text incidents are uploaded. (The vision path was fixed at `:487` by making an `ARGB_8888` copy first; this path was missed.)
- **Suggested direction:** Copy to `ARGB_8888` before scaling/copying, exactly as the vision path does.

### 21. `allowBackup=true` With No Backup Rules
- **Location:** `android/app/src/main/AndroidManifest.xml:L8`; `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exist but are unreferenced
- **Problem:** Neither `android:fullBackupContent` nor `android:dataExtractionRules` is set. SharedPreferences — including the guardian PIN hash, parent ID, device ID, blocklists and endpoints — are included in cloud/adb backups.
- **Impact:** Credential and configuration exfiltration from a backup; combined with #22 the PIN is recoverable.
- **Suggested direction:** Set `android:dataExtractionRules`/`android:fullBackupContent` and exclude the prefs file, or set `allowBackup="false"`.

### 22. PIN Hashed With a Hard-Coded Salt and Fast Hash
- **Location:** `android/app/src/main/java/com/example/screenguard/data/PreferencesManager.kt:L147-152`
- **Problem:** `hashPin` uses the constant `"screenguard_salt_v1_"` and a single SHA-256 pass over a 4–6 digit PIN.
- **Impact:** At most 1.1M candidates; a static salt makes offline brute force trivial (seconds on a phone). A leaked backup (see #21) exposes the PIN.
- **Suggested direction:** Per-install random salt plus a slow KDF (PBKDF2/Argon2) with a meaningful iteration count.

### 23. Vision Loop Ignores Pause and Screen-Off
- **Location:** `android/app/src/main/java/com/example/screenguard/service/ScreenGuardAccessibilityService.kt:L159-162`, `:464`
- **Problem:** The periodic loop checks `isFreeTimeActive()` but never `isAppPaused()`, and runs regardless of screen state. `checkScreenForExplicitVision` also only checks free time.
- **Impact:** During a parent-approved break — and with the screen off — the service still captures a screenshot every 2 seconds, wasting battery and violating the pause contract.
- **Suggested direction:** Add `isAppPaused()` and a screen-on check; stop the loop when the display is off.

### 24. Child Rules Listener Attached Once, Before Pairing, Never Retried
- **Location:** `ScreenGuardAccessibilityService.kt:L129`; `FamilyRepository.kt:L226` returns `null` when `parentId` is empty
- **Problem:** If the accessibility service starts before pairing (the normal onboarding order), the listener is `null` and is never retried. Only the activity's listener applies rules, and it dies with the activity.
- **Impact:** Background free-time/uninstall approvals stop working once the child activity is closed.
- **Suggested direction:** Attach on pairing success and retry when `parentId` changes; re-attach in `onServiceConnected` if needed.

### 25. `blockIncognito` and `familyDns` Rules Are Read but Never Applied
- **Location:** `FamilyRepository.kt:L235-243` (parsed), `:238-239` (only two values persisted); `ChildMainActivity.kt:L131-134`
- **Problem:** Neither flag is written to `PreferencesManager`, and the VPN starts unconditionally when paired (`ChildMainActivity.kt:L113-124`). The only writer of `preferences.blockIncognito` is the dead `MainActivity.kt:L1320`.
- **Impact:** Remote incognito/DNS toggles are silently ignored; `familyDns=false` does not stop the VPN.
- **Suggested direction:** Persist both flags and gate incognito detection / VPN start on them.

### 26. Default Blocked Words Match Ordinary Page Text
- **Location:** `PreferencesManager.kt:L55-82`
- **Problem:** The default set includes `adult`, `mature`, `sex`, `cookie`, `bikini`, `swimsuit`, `lingerie`, plus explicit terms. Matching is substring/exact over all on-screen text (`ScreenGuardAccessibilityService.kt:L428-451`), and the default whitelist contains only ScreenGuard packages and SystemUI.
- **Impact:** `cookie` alone matches every cookie banner and most news/e-commerce pages; the browser is closed within seconds of normal browsing. With the settings UI unreachable (#14), the list cannot be edited.
- **Suggested direction:** Ship a minimal list, require whole-word matches for ambiguous terms, and allowlist common UI/browser packages.

### 27. "Unpair" Clears Parent State Only
- **Location:** `ParentMainActivity.kt:L95-98`
- **Problem:** `onUnpair` only calls `prefs.setPairedChild("")` and resets local state. No backend node is cleared and the child is not notified.
- **Impact:** The child remains paired and keeps uploading incidents/screenshots to a parent who believes the device is unpaired.
- **Suggested direction:** Mark the family link inactive/remove it and have the child observe the change.

### 28. Cloudflare Device ID Does Not Match the Firebase Device ID
- **Location:** `android/app/src/main/java/com/example/screenguard/network/CloudflareIncidentDispatcher.kt:L34-38`
- **Problem:** Cloudflare uses `"${Build.MODEL}_${androidId.take(6)}"` while Firebase uses a generated UUID-based `deviceId`.
- **Impact:** The two backends cannot correlate the same child; Cloudflare pause/respond calls target a different identity than the parent app.
- **Suggested direction:** Use one stable device ID for both dispatchers.

### 29. Missing `<queries>` Breaks Home-Launcher Detection on Android 11+
- **Location:** no `<queries>` in any manifest; `ScreenGuardAccessibilityService.kt:L190` calls `queryIntentActivities(ACTION_MAIN/CATEGORY_HOME)`
- **Problem:** Package-visibility filtering on API 30+ returns only the app's own package, so `cachedHomeLaunchers` stays empty.
- **Impact:** The launcher is not treated as protected; lint reports `QueryPermissionsNeeded`.
- **Suggested direction:** Declare a `<queries>` intent for `ACTION_MAIN` + `CATEGORY_HOME`.

### 30. Play Store Toggle Desyncs From the Blocked Package Set
- **Location:** `PreferencesManager.kt:L308-337`
- **Problem:** `setBlockPlayStore` keeps `_blockPlayStore` and `_blockedPackages` in sync, but `addBlockedPackage`/`removeBlockedPackage` mutate only `_blockedPackages`.
- **Impact:** Adding/removing `com.android.vending` manually leaves the toggle showing the wrong state.
- **Suggested direction:** Derive the toggle from the package set, or update both in every mutation.

### 31. VPN Reports "Protection Active" Even When the Tunnel Failed
- **Location:** `android/app/src/main/java/com/example/screenguard/service/FamilyDnsVpnService.kt:L29-47`, `:69`, `:150-152`
- **Problem:** `startForeground` with the "Protection Active" notification runs before `establishDnsTunnel()`. A `null` from `builder.establish()` is not checked and all exceptions are swallowed.
- **Impact:** The user is told filtering is active while DNS may be completely broken (see #1) or the tunnel never came up.
- **Suggested direction:** Post the notification only after a successful `establish()`, surface failures, and stop the service on failure.

### 32. Pairing Is Unauthenticated and Hijackable
- **Location:** `android/app/src/main/java/com/example/screenguard/network/PairingManager.kt:L41`, `:109-148`
- **Problem:** Codes come from Kotlin `Random` (not `SecureRandom`) in a 900k space; the `pairings` node is world-readable/writable (#4); there is no rate limit or attempt counter. Any client can read a `WAITING` code, rewrite its `parentId`, or set `status = "PAIRED"`. The child transaction never verifies who wrote the session.
- **Impact:** A child can be paired to an attacker, who then controls free time, uninstall unlocks and incident visibility.
- **Suggested direction:** Use `SecureRandom` with a longer code, authenticate the session, bind it to the creating parent, rate-limit attempts, and validate server-side.

### 33. Guardian Sends Firebase Request IDs to the Cloudflare Endpoint
- **Location:** `android/app/src/main/java/com/example/screenguard/GuardianActivity.kt:L367,L371` → `:242-252`
- **Problem:** `req.id` comes from the Firebase `pause_requests` snapshot, but it is posted to `$serverUrl/api/requests/respond`, which expects a Cloudflare request ID.
- **Impact:** Cloudflare approvals 404 or approve the wrong request.
- **Suggested direction:** Send the Cloudflare request ID field (or drop the cross-backend call).

### 34. `confThreshold` Is Ignored and Compound Hints Use 0.05
- **Location:** `android/app/src/main/java/com/example/screenguard/service/NudeNetVisionDetector.kt:L117`, `:199`, `:211`, `:228`, `:236`
- **Problem:** The `confThreshold` parameter is declared but never used; thresholds are hard-coded at 0.20/0.22/0.35, and the "compound" branch accepts hints as low as 0.05. There is no NMS or box/objectness validation — only per-class max over 2,100 anchors.
- **Impact:** Frequent false positives on swimwear/beach/fitness content and no way to tune sensitivity from the caller.
- **Suggested direction:** Honor `confThreshold`, validate boxes, apply NMS, and raise the compound hint threshold.

### 35. Child Activity Polling Loops Keep Running When Backgrounded
- **Location:** `android/app/src/main/java/com/example/screenguard/ui/ChildMainActivity.kt:L186-189`, `:371-372`
- **Problem:** `LaunchedEffect(Unit) { while (true) { … delay(1500) } }` and the 1-second clock are not lifecycle-aware.
- **Impact:** Continuous wakeups while the activity is stopped, on top of the 2-second vision loop.
- **Suggested direction:** Use `repeatOnLifecycle(Lifecycle.State.STARTED)` or a lifecycle-aware flow.

### 36. `MainActivity` Reads Non-Observable State
- **Location:** `android/app/src/main/java/com/example/screenguard/MainActivity.kt:L901-903`
- **Problem:** `isUnlocked` is a plain `Boolean` and `isAppPaused` is computed once from `System.currentTimeMillis()`, so neither recomposes as state/time changes.
- **Impact:** Stale unlock/pause banners. Only relevant if #14 is fixed by reviving the activity rather than deleting it.
- **Suggested direction:** Collect an unlock `StateFlow` and tick the clock in a lifecycle-aware effect.

### 37. DNS-Only Filtering Is Trivially Bypassed
- **Location:** `FamilyDnsVpnService.kt:L60-66`
- **Problem:** The VPN adds three resolver addresses (`1.1.1.3`, `1.0.0.3`, `185.228.168.168`) and routes only those `/32`s. Apps using DoH/DoT or a hard-coded resolver bypass it entirely. Distinct from #1/#2, which describe breakage rather than coverage.
- **Impact:** The adult-content filter can be bypassed by any modern browser.
- **Suggested direction:** Route all DNS, block known DoH endpoints, or filter per-app/at the IP layer.

### 38. Screenshots Are Never Deleted
- **Location:** `FamilyRepository.kt:L133-140`; `FirebaseIncidentDispatcher.kt:L103-110`
- **Problem:** Both dispatchers upload screenshots to Storage with no retention or cleanup path (no `.delete()` anywhere in the codebase).
- **Impact:** Unbounded storage growth; combined with #4 every object is publicly readable.
- **Suggested direction:** Add a retention policy and delete objects when incidents are pruned.

### 39. Incident History Is Capped at 60 With Base64 Payloads
- **Location:** `FamilyRepository.kt:L295`
- **Problem:** `limitToLast(60)` silently drops older incidents, and each node embeds a base64 thumbnail.
- **Impact:** Parents lose history, and each read can transfer many megabytes.
- **Suggested direction:** Paginate metadata and keep images only in Storage URLs.

### 40. `POST_NOTIFICATIONS` Is Declared but Never Requested
- **Location:** `android/app/src/child/AndroidManifest.xml` (permission); no runtime request in `ChildMainActivity`
- **Problem:** Android 13+ requires a runtime grant; the code never asks.
- **Impact:** The persistent VPN/accessibility notification is hidden, so the user cannot see or stop the service from the shade.
- **Suggested direction:** Request the permission during onboarding.

### 41. `startForeground` Failure Is Swallowed, Then the VPN Starts Anyway
- **Location:** `FamilyDnsVpnService.kt:L29-47`
- **Problem:** The `startForeground` exception is caught and logged, then `establishDnsTunnel()` runs regardless.
- **Impact:** On Android 12+ the process can be killed shortly after, leaving a half-started VPN with no notification.
- **Suggested direction:** Stop the service when `startForeground` fails.

### 42. No PIN Gate on Disabling Protection
- **Location:** `ScreenGuardDeviceAdminReceiver.kt`; PIN checks exist only in dead `MainActivity.kt:L140,L241`
- **Problem:** Nothing requires the guardian PIN before disabling the accessibility service, deactivating device admin, or clearing app data. `onDisableRequested` only returns a message.
- **Impact:** A child can switch protection off from Android Settings.
- **Suggested direction:** Gate settings changes with the PIN and use device-admin policies where possible.

### 43. `commit()` on the Calling Thread
- **Location:** `PreferencesManager.kt:L194`
- **Problem:** `prefs.edit().putString(KEY_DEVICE_ID, id).commit()` performs a synchronous disk write.
- **Impact:** Main-thread jank/ANR on first run; lint reports `ApplySharedPref`.
- **Suggested direction:** Use `apply()`.

### 44. Vision and Block Screenshot Cadences Conflict
- **Location:** `ScreenGuardAccessibilityService.kt:L469` (1.5 s vision throttle) vs `:596` (5 s block cooldown)
- **Problem:** After a word block, the vision path can immediately capture and block again.
- **Impact:** Duplicate incidents and repeated `performGlobalAction(HOME)` calls.
- **Suggested direction:** Share one cooldown between the word and vision paths.

### 45. The 12 MB ONNX Model Is Fully Buffered Into Memory
- **Location:** `NudeNetVisionDetector.kt:L88,L97`
- **Problem:** `assets.open(...).readBytes()` holds the 12,150,158-byte asset in a `ByteArray`, then `createSession` copies it again.
- **Impact:** A 24–36 MB transient spike on `minSdk 24` devices risks `OutOfMemoryError`; failure is only logged and vision stays silently disabled.
- **Suggested direction:** Stream from the asset descriptor or memory-map it, and surface initialization failure in the UI.

### 46. No Tests for the Risky Subsystems
- **Location:** `android/app/src/test`, `android/app/src/androidTest`
- **Problem:** Only `WordDetectorTest` and `MainScreenTest` exist; there are no tests for pairing, VPN packet construction, rules application or vision thresholds.
- **Impact:** Regressions in the highest-risk areas go undetected (the bugs in this section are examples).
- **Suggested direction:** Add unit tests for pairing transactions, DNS packet building and rules mapping.

### 47. Accessibility Service Is Broadly Exported
- **Location:** `android/app/src/child/AndroidManifest.xml:L38`; `res/xml/screenguard_accessibility_service_config.xml` (`canTakeScreenshot="true"`)
- **Problem:** The service accepts events from all apps and can take screenshots, with no additional restrictions beyond the bind permission.
- **Impact:** A broad privacy exposure surface, amplified by the public Storage rules (#4).
- **Suggested direction:** Document and minimize the captured data; ensure only the system can bind; audit screenshot retention.

### 48. DNS Forwarder: No Backoff, Fixed 4096 Buffer, No Truncation Handling
- **Location:** `FamilyDnsVpnService.kt:L102`, `:143-145`
- **Problem:** A fixed 4,096-byte receive buffer and no EDNS/TC handling; the catch block loops immediately while `isRunning` is true. This is distinct from #2 (head-of-line blocking and misrouting).
- **Impact:** Large/DNSSEC responses fail or truncate; transient errors can spin the CPU.
- **Suggested direction:** Size the buffer from EDNS, handle the TC bit, and add a short backoff on errors.

### 49. Launcher Cache Refreshes on Every Accessibility Event
- **Location:** `ScreenGuardAccessibilityService.kt:L187-190`
- **Problem:** `cachedHomeLaunchers.isEmpty()` forces a refresh whenever the cache is empty — which is always the case when #29 makes the query return nothing.
- **Impact:** A package-manager Binder call on every accessibility event.
- **Suggested direction:** Fix #29 and cache the negative result with a TTL.

### 50. `TEST_BLOCKED_WORD` Ships in the Default List
- **Location:** `PreferencesManager.kt:L82`
- **Problem:** A placeholder test token is enabled for every new install.
- **Impact:** Unnecessary false blocks.
- **Suggested direction:** Remove it from production defaults.

### 51. Misspelled Duplicate Blocklist Entries
- **Location:** `PreferencesManager.kt:L66-68`
- **Problem:** `"nacked"`, `"nakked"`, `"neked"` sit alongside `"naked"`.
- **Impact:** Noise and maintenance overhead.
- **Suggested direction:** Deduplicate and spell-check the list.

### 52. `String.format` Without an Explicit `Locale`
- **Location:** `MainActivity.kt:L542`
- **Problem:** Uses the default locale; lint reports `DefaultLocale`.
- **Impact:** Non-ASCII digits in some locales.
- **Suggested direction:** Pass `Locale.US` (or a fixed locale).

### 53. ONNX Runtime `.so` Is Not 16 KB Aligned
- **Location:** `com.microsoft.onnxruntime:onnxruntime-android:1.18.0` (`arm64-v8a/libonnxruntime.so`)
- **Problem:** Lint reports `Aligned16KB`; the library is not aligned for 16 KB memory pages.
- **Impact:** The native library may fail to load on Android 15+ devices configured with 16 KB pages.
- **Suggested direction:** Upgrade ONNX Runtime to a 16 KB-aligned release.

### 54. Release Build Is Unminified
- **Location:** `android/app/build.gradle.kts:L34`
- **Problem:** `isMinifyEnabled = false` with the default ProGuard file only.
- **Impact:** Larger APK and easier reverse engineering, which aggravates the exposed Firebase configuration (#4).
- **Suggested direction:** Enable R8 with the necessary keep rules.

---

*Report generated for ScreenGuard project. Changes have been documented without applying code modifications.*
