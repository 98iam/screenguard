package com.example.screenguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.screenguard.data.DetectionLogRepository
import com.example.screenguard.data.PreferencesManager
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
        "com.android.settings",
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

        // 1. Check if protection is enabled
        if (!preferences.isEnabled.value) return

        val eventPkg = event.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()

        // 2. Instant Blacklisted App Check (e.g. Google Play Store or Cookie Manga)
        val blockedPackages = preferences.blockedPackages.value
        val isEventPkgBlocked = blockedPackages.any { it.equals(eventPkg, ignoreCase = true) }

        val rootNode = rootInActiveWindow ?: event.source
        val rootPkg = rootNode?.packageName?.toString() ?: eventPkg
        val isRootPkgBlocked = blockedPackages.any { it.equals(rootPkg, ignoreCase = true) }

        if (isEventPkgBlocked || isRootPkgBlocked) {
            if (now - lastTriggerTime > 400) {
                lastTriggerTime = now
                val targetPkg = if (isRootPkgBlocked) rootPkg else eventPkg
                performGlobalAction(GLOBAL_ACTION_HOME)

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

        // 3. Ignore our own app & system protected apps
        if (isPackageProtected(eventPkg) || isPackageProtected(rootPkg)) return

        // 4. Throttle text matching triggers
        if (now - lastTriggerTime < 800) return
        if (rootNode == null) return

        // 5. Extract text from screen node hierarchy
        val extractedText = StringBuilder()
        try {
            extractTextRecursive(rootNode, extractedText, maxNodes = 400)
        } catch (e: Exception) {
            // Safe fallback
        }

        val fullText = extractedText.toString()
        if (fullText.isEmpty()) return

        // 6. Detect blocked words
        val blockedWords = preferences.blockedWords.value
        val matchMode = preferences.matchMode.value
        val matches = WordDetector.findMatches(fullText, blockedWords, matchMode)

        if (matches.isNotEmpty()) {
            lastTriggerTime = now
            val matchedWord = matches.first()
            val targetPkg = if (rootPkg.isNotEmpty() && !isPackageProtected(rootPkg)) rootPkg else eventPkg

            // 7. Action: Kick user to Home Screen
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 8. Get friendly app name
            val appLabel = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(targetPkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                targetPkg
            }

            // 9. Log Event
            DetectionLogRepository.addLog(
                packageName = targetPkg,
                appName = appLabel,
                matchedWord = matchedWord
            )

            // 10. Display feedback toast if enabled
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

    private fun extractTextRecursive(node: AccessibilityNodeInfo?, buffer: StringBuilder, maxNodes: Int, count: IntArray = intArrayOf(0)) {
        if (node == null || count[0] >= maxNodes) return

        // Skip our own app's nodes if rendered in subviews
        val nodePkg = node.packageName?.toString()
        if (nodePkg != null && isPackageProtected(nodePkg)) {
            return
        }

        count[0]++

        // Collect visible text & content descriptions
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

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }
}
