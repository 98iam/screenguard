package com.example.screenguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
import com.example.screenguard.network.FirebaseIncidentDispatcher
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenGuardAccessibilityService : AccessibilityService() {

    private lateinit var preferences: PreferencesManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L
    private var lastScreenshotTime = 0L
    private var pauseListener: ValueEventListener? = null

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true

        // Real-time synchronization of Guardian pause status
        try {
            pauseListener = FirebaseIncidentDispatcher.attachPauseStatusListener(applicationContext) { isPaused, _ ->
                if (isPaused) {
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "⏸ ScreenGuard: Temporary Pause Approved by Guardian",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ScreenGuardService", "Failed to attach Firebase pause listener", e)
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
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfos.any { it.activityInfo?.packageName?.equals(pkg, ignoreCase = true) == true }
        } catch (e: Exception) {
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::preferences.isInitialized) {
            preferences = PreferencesManager.getInstance(this)
        }

        // Check if guardian granted a temporary pause
        if (preferences.isAppPaused()) return

        val eventPkg = event.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()
        val rootNode = rootInActiveWindow ?: event.source
        val rootPkg = rootNode?.packageName?.toString() ?: eventPkg

        // 1. Anti-Tamper & Anti-Uninstall Settings Guard
        val isSettingsOrInstaller = settingsAndInstallerPackages.contains(eventPkg) ||
                settingsAndInstallerPackages.contains(rootPkg)

        if (isSettingsOrInstaller) {
            if (preferences.uninstallProtection.value && !preferences.isGuardianUnlocked()) {
                val sb = StringBuilder()
                try {
                    extractTextRecursive(rootNode, sb, maxNodes = 120)
                } catch (e: Exception) {}
                val text = sb.toString().lowercase()

                val hasScreenGuard = text.contains("screenguard")
                val hasDangerAction = text.contains("force stop") ||
                        text.contains("uninstall") ||
                        text.contains("deactivate") ||
                        text.contains("disable") ||
                        text.contains("clear data") ||
                        text.contains("clear storage") ||
                        text.contains("turn off")

                val isAccessibilityDetailForScreenGuard = hasScreenGuard &&
                        (text.contains("accessibility") || text.contains("use service") || text.contains("shortcut"))

                if ((hasScreenGuard && hasDangerAction) || isAccessibilityDetailForScreenGuard) {
                    if (now - lastTriggerTime > 1000) {
                        lastTriggerTime = now
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        mainHandler.post {
                            Toast.makeText(
                                applicationContext,
                                "🛡 Settings Guard: Locked by Guardian PIN",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return
                }
            }
            return
        }

        // 2. Check if protection is enabled
        if (!preferences.isEnabled.value) return

        // 3. Instant Blacklisted App Check (e.g. Google Play Store or custom apps)
        val blockedPackages = preferences.blockedPackages.value
        val isEventPkgBlocked = blockedPackages.any { it.equals(eventPkg, ignoreCase = true) }
        val isRootPkgBlocked = blockedPackages.any { it.equals(rootPkg, ignoreCase = true) }

        if (isEventPkgBlocked || isRootPkgBlocked) {
            if (now - lastTriggerTime > 500) {
                lastTriggerTime = now
                val targetPkg = if (isRootPkgBlocked) rootPkg else eventPkg

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

        // 4. Ignore our own app & system protected apps
        if (isPackageProtected(eventPkg) || isPackageProtected(rootPkg)) return

        // 5. Throttle text matching triggers
        if (now - lastTriggerTime < 800) return
        if (rootNode == null) return

        // 6. Extract text from screen node hierarchy
        val extractedText = StringBuilder()
        try {
            extractTextRecursive(rootNode, extractedText, maxNodes = 400)
        } catch (e: Exception) {
            // Safe fallback
        }

        val fullText = extractedText.toString()
        if (fullText.isEmpty()) return

        // 7. Detect blocked words
        val blockedWords = preferences.blockedWords.value
        val matchMode = preferences.matchMode.value
        val matches = WordDetector.findMatches(fullText, blockedWords, matchMode)

        if (matches.isNotEmpty()) {
            lastTriggerTime = now
            val matchedWord = matches.first()
            val targetPkg = if (rootPkg.isNotEmpty() && !isPackageProtected(rootPkg)) rootPkg else eventPkg

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
                                val maxW = 720
                                bitmap = if (rawBitmap.width > maxW) {
                                    val ratio = maxW.toFloat() / rawBitmap.width
                                    Bitmap.createScaledBitmap(rawBitmap, maxW, (rawBitmap.height * ratio).toInt(), true)
                                } else {
                                    rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
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
        // 1. Firebase Dispatcher (Realtime Database + Storage)
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

        val nodePkg = node.packageName?.toString()
        if (nodePkg != null && isPackageProtected(nodePkg)) {
            return
        }

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
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }
}
