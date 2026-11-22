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

*Report generated for ScreenGuard project. Changes have been documented without applying code modifications.*
