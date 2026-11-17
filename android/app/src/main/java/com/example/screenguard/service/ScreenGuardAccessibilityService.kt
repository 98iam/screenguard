package com.example.screenguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.screenguard.data.DetectionLogRepository
import com.example.screenguard.data.PreferencesManager
import com.example.screenguard.network.CloudflareIncidentDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenGuardAccessibilityService : AccessibilityService() {

    private lateinit var preferences: PreferencesManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L

    // Known critical system packages that should never be closed
    private val defaultProtectedPackages = setOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "android"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true
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

        val eventPkg = event.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()
        val rootNode = rootInActiveWindow ?: event.source
        val rootPkg = rootNode?.packageName?.toString() ?: eventPkg

        // 1. Anti-Tamper & Anti-Uninstall Settings Guard
        if (eventPkg == "com.android.settings" || rootPkg == "com.android.settings") {
            if (preferences.uninstallProtection.value && !preferences.isGuardianUnlocked()) {
                val sb = StringBuilder()
                try {
                    extractTextRecursive(rootNode, sb, maxNodes = 100)
                } catch (e: Exception) {}
                val settingsText = sb.toString().lowercase()

                val isTamperAttempt = settingsText.contains("screenguard") ||
                        settingsText.contains("accessibility") ||
                        settingsText.contains("device admin") ||
                        settingsText.contains("force stop") ||
                        settingsText.contains("uninstall") ||
                        settingsText.contains("deactivate") ||
                        settingsText.contains("special app access")

                if (isTamperAttempt) {
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

        // 3. Instant Blacklisted App Check (e.g. Google Play Store or Cookie Manga)
        val blockedPackages = preferences.blockedPackages.value
        val isEventPkgBlocked = blockedPackages.any { it.equals(eventPkg, ignoreCase = true) }
        val isRootPkgBlocked = blockedPackages.any { it.equals(rootPkg, ignoreCase = true) }

        if (isEventPkgBlocked || isRootPkgBlocked) {
            if (now - lastTriggerTime > 400) {
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

                // Capture screenshot before returning home
                triggerIncidentScreenshotAndUpload(targetPkg, appLabel, "[LOCKED APP: $appLabel]")

                performGlobalAction(GLOBAL_ACTION_HOME)

                DetectionLogRepository.addLog(
                    packageName = targetPkg,
                    appName = appLabel,
                    matchedWord = "[LOCKED APP: $appLabel]"
                )

                if (preferences.showToast.value) {
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "🛡 ScreenGuard: $appLabel is Locked",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
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

            // Silent Screenshot & Cloudflare Dispatch
            triggerIncidentScreenshotAndUpload(targetPkg, appLabel, matchedWord)

            // Action: Kick user to Home Screen
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Log Event locally
            DetectionLogRepository.addLog(
                packageName = targetPkg,
                appName = appLabel,
                matchedWord = matchedWord
            )

            // Display feedback toast if enabled
            if (preferences.showToast.value) {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🛡 ScreenGuard: Closed $appLabel ('$matchedWord')",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun triggerIncidentScreenshotAndUpload(targetPkg: String, appLabel: String, matchedWord: String) {
        val endpoint = preferences.cloudflareEndpoint.value
        if (endpoint.isBlank()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()

                            CloudflareIncidentDispatcher.dispatchIncident(
                                context = applicationContext,
                                endpointUrl = endpoint,
                                bitmap = bitmap,
                                packageName = targetPkg,
                                appName = appLabel,
                                matchedWord = matchedWord
                            )
                        } catch (e: Exception) {
                            CloudflareIncidentDispatcher.dispatchIncident(
                                context = applicationContext,
                                endpointUrl = endpoint,
                                bitmap = null,
                                packageName = targetPkg,
                                appName = appLabel,
                                matchedWord = matchedWord
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        CloudflareIncidentDispatcher.dispatchIncident(
                            context = applicationContext,
                            endpointUrl = endpoint,
                            bitmap = null,
                            packageName = targetPkg,
                            appName = appLabel,
                            matchedWord = "$matchedWord (Screenshot failed: $errorCode)"
                        )
                    }
                })
            } catch (e: Exception) {
                // Safe ignore
            }
        } else {
            CloudflareIncidentDispatcher.dispatchIncident(
                context = applicationContext,
                endpointUrl = endpoint,
                bitmap = null,
                packageName = targetPkg,
                appName = appLabel,
                matchedWord = matchedWord
            )
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
