package com.example.screenguard.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.screenguard.data.DetectionLogRepository
import com.example.screenguard.data.PreferencesManager
import com.example.screenguard.network.CloudflareIncidentDispatcher
import com.example.screenguard.network.FamilyRepository
import com.example.screenguard.network.FirebaseIncidentDispatcher
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenGuardAccessibilityService : AccessibilityService() {

    private lateinit var preferences: PreferencesManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastTriggerTime = 0L
    private var lastTextScanTime = 0L
    private var lastScreenshotTime = 0L
    private var lastVisionScanTime = 0L
    @Volatile
    private var isVisionScanning = false
    @Volatile
    private var currentActivePkg: String = ""
    private var pauseListener: ValueEventListener? = null
    private var childRulesListener: ValueEventListener? = null

    // Screen state listener to put Firebase RTDB offline during screen off (saves 100% standby radio drain)
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    try {
                        FirebaseDatabase.getInstance().goOffline()
                    } catch (e: Exception) {
                        Log.w("ScreenGuardService", "Failed to pause Firebase RTDB", e)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    try {
                        FirebaseDatabase.getInstance().goOnline()
                    } catch (e: Exception) {
                        Log.w("ScreenGuardService", "Failed to resume Firebase RTDB", e)
                    }
                }
            }
        }
    }

    // Cache home launchers to prevent expensive Binder IPC calls on every node
    @Volatile
    private var cachedHomeLaunchers: Set<String> = emptySet()
    @Volatile
    private var lastHomeLaunchersRefreshTime: Long = 0L

    // Known critical system packages that should never be closed
    private val defaultProtectedPackages = setOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "android"
    )

    private val settingsAndInstallerPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "com.vivaldi.browser",
        "com.ecosia.android",
        "com.UCMobile.intl"
    )

    private fun isBrowserApp(pkg: String): Boolean {
        return browserPackages.any { it.equals(pkg, ignoreCase = true) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true

        // Real-time synchronization of Child Rules & Free Time from Parent
        try {
            childRulesListener = FamilyRepository.attachChildRulesListener(applicationContext) { rules ->
                if (rules.freeTimeUntil > System.currentTimeMillis()) {
                    val remainingMins = ((rules.freeTimeUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "⏸ ScreenGuard: Free Time Active ($remainingMins mins remaining)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ScreenGuardService", "Failed to attach child rules listener", e)
        }

        // Register screen on/off receiver to save battery by sleeping RTDB connection while screen is off
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            Log.w("ScreenGuardService", "Failed to register screen state receiver", e)
        }

        // Initialize YOLOv8 NudeNet Vision AI in background & start periodic visual check
        serviceScope.launch {
            NudeNetVisionDetector.initialize(applicationContext)
            while (isActive) {
                delay(2000L)
                val pkg = currentActivePkg
                if (pkg.isNotEmpty() && !isPackageProtected(pkg) && !preferences.isFreeTimeActive() && preferences.isEnabled.value) {
                    checkScreenForExplicitVision(pkg)
                }
            }
        }
    }

    private fun isPackageProtected(pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        if (pkg.equals(packageName, ignoreCase = true)) return true
        if (defaultProtectedPackages.any { it.equals(pkg, ignoreCase = true) }) return true

        // User custom whitelist
        val userWhitelist = preferences.whitelist.value
        if (userWhitelist.any { it.equals(pkg, ignoreCase = true) }) return true

        // Check if package is a home launcher
        if (isHomeLauncher(pkg)) return true

        return false
    }

    private fun isHomeLauncher(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (now - lastHomeLaunchersRefreshTime > 60_000L || cachedHomeLaunchers.isEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                cachedHomeLaunchers = resolveInfos.mapNotNull { it.activityInfo?.packageName?.lowercase() }.toSet()
                lastHomeLaunchersRefreshTime = now
            } catch (e: Exception) {
                // Keep existing cache on failure
            }
        }
        return cachedHomeLaunchers.contains(pkg.lowercase())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::preferences.isInitialized) {
            preferences = PreferencesManager.getInstance(this)
        }

        val eventPkg = event.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()

        // Always track active non-protected foreground package immediately
        currentActivePkg = if (isPackageProtected(eventPkg)) "" else eventPkg

        var rootNode: AccessibilityNodeInfo? = null
        var rootNodeFetched = false
        fun getRoot(): AccessibilityNodeInfo? {
            if (!rootNodeFetched) {
                rootNode = rootInActiveWindow ?: event.source
                rootNodeFetched = true
            }
            return rootNode
        }

        // 1. Anti-Tamper, Anti-Uninstall & Private DNS Settings Guard (Permanently enforced, checked before pause)
        val isSettingsEvent = settingsAndInstallerPackages.any { it.equals(eventPkg, ignoreCase = true) }
        val isSettingsOrInstaller = isSettingsEvent || run {
            val rPkg = getRoot()?.packageName?.toString() ?: ""
            settingsAndInstallerPackages.any { it.equals(rPkg, ignoreCase = true) }
        }

        if (isSettingsOrInstaller) {
            // If parent remotely approved 15-minute uninstallation window, permit settings access
            if (preferences.isUninstallUnlocked()) {
                return
            }

            val node = getRoot()
            val sb = StringBuilder()
            try {
                extractTextRecursive(node, sb, maxNodes = 120)
            } catch (e: Exception) {}
            val text = sb.toString().lowercase()

            val hasScreenGuard = text.contains("screenguard") || text.contains("shield")
            val hasDangerAction = text.contains("force stop") ||
                    text.contains("uninstall") ||
                    text.contains("deactivate") ||
                    text.contains("disable") ||
                    text.contains("clear data") ||
                    text.contains("clear storage") ||
                    text.contains("turn off")

            val isAccessibilityDetailForScreenGuard = hasScreenGuard &&
                    (text.contains("accessibility") || text.contains("use service") || text.contains("shortcut"))

            val isDnsTamper = text.contains("private dns") ||
                    (text.contains("dns") && (text.contains("hostname") || text.contains("provider") || text.contains("cloudflare") || text.contains("adguard")))

            val checkUninstall = preferences.uninstallProtection.value
            val checkDns = preferences.protectPrivateDns.value
            val isTamperTriggered = (checkUninstall && ((hasScreenGuard && hasDangerAction) || isAccessibilityDetailForScreenGuard)) ||
                    (checkDns && isDnsTamper)

            if (isTamperTriggered) {
                if (now - lastTriggerTime > 1000) {
                    lastTriggerTime = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "🛡 Settings Guard: Locked by ScreenGuard Parent",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return
            }
            return
        }

        // 2. Check if protection is enabled
        if (!preferences.isEnabled.value) return

        // 3. Instant Blacklisted App Check (e.g. Google Play Store or custom apps)
        val blockedPackages = preferences.blockedPackages.value
        val isEventPkgBlocked = blockedPackages.any { it.equals(eventPkg, ignoreCase = true) }

        if (isEventPkgBlocked) {
            if (now - lastTriggerTime > 500) {
                lastTriggerTime = now
                val targetPkg = eventPkg

                val appLabel = if (targetPkg == "com.android.vending") {
                    "Google Play Store"
                } else {
                    try {
                        val pm = packageManager
                        val appInfo = pm.getApplicationInfo(targetPkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        targetPkg
                    }
                }

                // Close app with screenshot capture
                executeAppBlockWithScreenshot(
                    targetPkg = targetPkg,
                    appLabel = appLabel,
                    triggerReason = "[LOCKED APP: $appLabel]"
                )
            }
            return
        }

        // 4. Ignore our own app & system protected apps early
        if (isPackageProtected(eventPkg)) return

        // 5. Throttle text matching triggers during scrolling/typing
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastTextScanTime < 800) return
        }
        lastTextScanTime = now

        val node = getRoot() ?: return
        val rootPkg = node.packageName?.toString() ?: eventPkg

        // Re-check root package in case event was from child view with different root
        if (blockedPackages.any { it.equals(rootPkg, ignoreCase = true) }) {
            if (now - lastTriggerTime > 500) {
                lastTriggerTime = now
                val appLabel = if (rootPkg == "com.android.vending") {
                    "Google Play Store"
                } else {
                    try {
                        val pm = packageManager
                        val appInfo = pm.getApplicationInfo(rootPkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        rootPkg
                    }
                }
                executeAppBlockWithScreenshot(
                    targetPkg = rootPkg,
                    appLabel = appLabel,
                    triggerReason = "[LOCKED APP: $appLabel]"
                )
            }
            return
        }

        if (isPackageProtected(rootPkg)) return

        // 6. Extract text from screen node hierarchy (max 200 nodes for battery efficiency)
        val extractedText = StringBuilder()
        try {
            extractTextRecursive(node, extractedText, maxNodes = 200)
        } catch (e: Exception) {
            // Safe fallback
        }

        val fullText = extractedText.toString()
        if (fullText.isEmpty()) return

        // 6b. Browser Incognito & History Deletion Guard (Permanently enforced, never disabled)
        if (isBrowserApp(eventPkg) || isBrowserApp(rootPkg)) {
            val browserText = fullText.lowercase()

            val isIncognitoAttempt = browserText.contains("new incognito tab") ||
                browserText.contains("you've gone incognito") ||
                browserText.contains("turn on incognito") ||
                browserText.contains("new private tab") ||
                browserText.contains("new inprivate tab") ||
                browserText.contains("inprivate browsing") ||
                browserText.contains("private browsing") ||
                browserText.contains("close incognito tabs") ||
                browserText.contains("switch to incognito")

            val isClearHistoryAttempt = browserText.contains("clear browsing data") ||
                browserText.contains("delete browsing data") ||
                browserText.contains("clear history") ||
                browserText.contains("delete history") ||
                browserText.contains("clear all history")

            val checkIncognito = preferences.blockIncognito.value
            val checkHistory = preferences.blockHistoryDeletion.value
            val isBlocked = (isIncognitoAttempt && checkIncognito) || (isClearHistoryAttempt && checkHistory)

            if (isBlocked) {
                lastTriggerTime = now
                val reason = if (isIncognitoAttempt) "Incognito Mode is Permanently Blocked" else "Clearing History is Blocked"

                performGlobalAction(GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 120)

                val targetPkg = if (isBrowserApp(rootPkg)) rootPkg else eventPkg
                DetectionLogRepository.addLog(
                    packageName = targetPkg,
                    appName = "Web Browser",
                    matchedWord = "[GUARD: $reason]"
                )

                // Dispatch incident to Parent app channel
                FamilyRepository.pushIncident(
                    context = applicationContext,
                    bitmap = null,
                    packageName = targetPkg,
                    appName = "Web Browser",
                    matchedWord = "[BLOCKED: $reason]"
                )

                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🛡 ScreenGuard: $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        }

        // If Free Time or pause is active, word blocking is relaxed for general educational/useful apps
        if (preferences.isAppPaused() || preferences.isFreeTimeActive()) {
            return
        }

        // 7. Fast Single-Pass Word Matching (Stops on first match)
        val blockedWords = preferences.blockedWords.value
        val matchMode = preferences.matchMode.value
        val matchedWord = WordDetector.findFirstMatch(fullText, blockedWords, matchMode)

        val targetPkg = if (rootPkg.isNotEmpty() && !isPackageProtected(rootPkg)) rootPkg else eventPkg
        currentActivePkg = targetPkg

        if (matchedWord != null) {
            lastTriggerTime = now

            val appLabel = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(targetPkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                targetPkg
            }

            // Close app with screenshot capture
            executeAppBlockWithScreenshot(
                targetPkg = targetPkg,
                appLabel = appLabel,
                triggerReason = matchedWord
            )
        } else {
            // 8. Vision AI Screen Analysis: Check image content (e.g. bikini, nudity, explicit images)
            checkScreenForExplicitVision(targetPkg)
        }
    }

    /**
     * Deep Learning Vision AI Analysis (YOLOv8 NudeNet ONNX).
     * Inspects screen content for bikinis, swimwear, and explicit imagery.
     */
    private fun checkScreenForExplicitVision(targetPkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (preferences.isFreeTimeActive()) return
        if (isPackageProtected(targetPkg)) return
        if (isVisionScanning) return

        val now = System.currentTimeMillis()
        if (now - lastVisionScanTime < 1500L) return
        lastVisionScanTime = now
        isVisionScanning = true

        val timeoutRunnable = Runnable { isVisionScanning = false }
        mainHandler.postDelayed(timeoutRunnable, 5000L)

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val hwBuffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    var bitmap: Bitmap? = null
                    try {
                        val rawBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        if (rawBitmap != null) {
                            // Hardware buffers cannot be accessed via getPixels(); convert to software ARGB_8888
                            val softCopy = rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            if (softCopy != null) {
                                bitmap = Bitmap.createScaledBitmap(softCopy, 320, 320, true)
                                if (bitmap != softCopy) {
                                    softCopy.recycle()
                                }
                            }
                            rawBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("ScreenGuardService", "Vision screenshot wrap error", e)
                    } finally {
                        hwBuffer.close()
                    }

                    if (bitmap != null) {
                        serviceScope.launch {
                            try {
                                val detections = NudeNetVisionDetector.detect(bitmap, confThreshold = 0.28f)
                                if (detections.isNotEmpty()) {
                                    val topDetection = detections.first()
                                    val pm = packageManager
                                    val appLabel = try {
                                        val appInfo = pm.getApplicationInfo(targetPkg, 0)
                                        pm.getApplicationLabel(appInfo).toString()
                                    } catch (e: Exception) {
                                        targetPkg
                                    }

                                    Log.w("ScreenGuardService", "🚨 NudeNet AI Triggered: $appLabel - ${topDetection.label} (${topDetection.score})")

                                    mainHandler.post {
                                        performGlobalAction(GLOBAL_ACTION_HOME)
                                        Toast.makeText(
                                            applicationContext,
                                            "🛡 ScreenGuard AI: Blocked $appLabel (${topDetection.humanReadableName})",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }

                                    DetectionLogRepository.addLog(
                                        packageName = targetPkg,
                                        appName = appLabel,
                                        matchedWord = "[AI VISION: ${topDetection.humanReadableName}]"
                                    )

                                    dispatchToBackends(
                                        targetPkg = targetPkg,
                                        appLabel = appLabel,
                                        matchedWord = "[AI VISION: ${topDetection.humanReadableName}]",
                                        bitmap = bitmap
                                    )
                                }
                            } finally {
                                isVisionScanning = false
                            }
                        }
                    } else {
                        isVisionScanning = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    isVisionScanning = false
                }
            })
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            isVisionScanning = false
        }
    }

    /**
     * Fixes race condition (H3), rate limit (H4), and downscaling (M1).
     * Takes screenshot first, kicks to Home upon completion, with a 250ms fallback.
     */
    private fun executeAppBlockWithScreenshot(
        targetPkg: String,
        appLabel: String,
        triggerReason: String
    ) {
        val now = System.currentTimeMillis()
        val canTakeScreenshot = (now - lastScreenshotTime > 5000L) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        // Log locally immediately
        DetectionLogRepository.addLog(
            packageName = targetPkg,
            appName = appLabel,
            matchedWord = triggerReason
        )

        // Show feedback toast if enabled
        if (preferences.showToast.value) {
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "🛡 ScreenGuard: Closed $appLabel ('$triggerReason')",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (canTakeScreenshot) {
            lastScreenshotTime = now
            var homeTriggered = false

            fun safeGoHome() {
                if (!homeTriggered) {
                    homeTriggered = true
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }

            // 250ms safety fallback in case takeScreenshot hangs
            val fallbackRunnable = Runnable { safeGoHome() }
            mainHandler.postDelayed(fallbackRunnable, 250)

            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        mainHandler.removeCallbacks(fallbackRunnable)
                        var bitmap: Bitmap? = null
                        val hwBuffer = screenshotResult.hardwareBuffer
                        try {
                            val colorSpace = screenshotResult.colorSpace
                            val rawBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            if (rawBitmap != null) {
                                val maxDimension = 640
                                bitmap = if (rawBitmap.width > maxDimension || rawBitmap.height > maxDimension) {
                                    val ratio = minOf(
                                        maxDimension.toFloat() / rawBitmap.width,
                                        maxDimension.toFloat() / rawBitmap.height
                                    )
                                    val targetW = (rawBitmap.width * ratio).toInt().coerceAtLeast(1)
                                    val targetH = (rawBitmap.height * ratio).toInt().coerceAtLeast(1)
                                    val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
                                    scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
                                } else {
                                    rawBitmap.copy(Bitmap.Config.RGB_565, false) ?: rawBitmap
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ScreenGuardService", "Bitmap processing error", e)
                        } finally {
                            hwBuffer.close()
                        }

                        // Dispatch incident to Firebase & Cloudflare
                        dispatchToBackends(targetPkg, appLabel, triggerReason, bitmap)

                        // Safely transition to home screen
                        safeGoHome()
                    }

                    override fun onFailure(errorCode: Int) {
                        mainHandler.removeCallbacks(fallbackRunnable)
                        dispatchToBackends(targetPkg, appLabel, "$triggerReason (Screenshot error: $errorCode)", null)
                        safeGoHome()
                    }
                })
            } catch (e: Exception) {
                mainHandler.removeCallbacks(fallbackRunnable)
                safeGoHome()
                dispatchToBackends(targetPkg, appLabel, triggerReason, null)
            }
        } else {
            // Immediately kick to home if on screenshot cooldown or older Android
            performGlobalAction(GLOBAL_ACTION_HOME)
            dispatchToBackends(targetPkg, appLabel, triggerReason, null)
        }
    }

    private fun dispatchToBackends(
        targetPkg: String,
        appLabel: String,
        matchedWord: String,
        bitmap: Bitmap?
    ) {
        // 1. Family Repository (Dedicated channel to Parent device)
        try {
            FamilyRepository.pushIncident(
                context = applicationContext,
                bitmap = bitmap,
                packageName = targetPkg,
                appName = appLabel,
                matchedWord = matchedWord
            )
        } catch (e: Exception) {
            Log.e("ScreenGuardService", "Family dispatch error", e)
        }

        // 2. Firebase General Dispatcher fallback
        try {
            FirebaseIncidentDispatcher.dispatchIncident(
                context = applicationContext,
                bitmap = bitmap,
                packageName = targetPkg,
                appName = appLabel,
                matchedWord = matchedWord
            )
        } catch (e: Exception) {
            Log.e("ScreenGuardService", "Firebase dispatch error", e)
        }

        // 2. Cloudflare Dispatcher (if URL is set in preferences)
        val cfEndpoint = preferences.cloudflareEndpoint.value
        if (cfEndpoint.isNotBlank()) {
            try {
                CloudflareIncidentDispatcher.dispatchIncident(
                    context = applicationContext,
                    endpointUrl = cfEndpoint,
                    bitmap = bitmap,
                    packageName = targetPkg,
                    appName = appLabel,
                    matchedWord = matchedWord
                )
            } catch (e: Exception) {
                Log.e("ScreenGuardService", "Cloudflare dispatch error", e)
            }
        }
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo?, buffer: StringBuilder, maxNodes: Int, count: IntArray = intArrayOf(0)) {
        if (node == null || count[0] >= maxNodes) return

        // Skip invisible nodes (e.g. off-screen or hidden views) to save IPC and CPU
        if (!node.isVisibleToUser) return

        count[0]++

        node.text?.let {
            if (it.isNotEmpty()) {
                buffer.append(it).append(" ")
            }
        }
        node.contentDescription?.let {
            if (it.isNotEmpty()) {
                buffer.append(it).append(" ")
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (count[0] >= maxNodes) break
            val child = node.getChild(i)
            if (child != null) {
                extractTextRecursive(child, buffer, maxNodes, count)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            val pId = preferences.getParentId()
            val cId = preferences.getDeviceId()
            if (pId.isNotEmpty() && childRulesListener != null) {
                FirebaseDatabase.getInstance().getReference("devices").child("families").child(pId).child(cId).child("rules").removeEventListener(childRulesListener!!)
            }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            FirebaseIncidentDispatcher.detachPauseStatusListener(applicationContext, pauseListener)
        } catch (e: Exception) {
            // Ignore
        }
        pauseListener = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }
}
