package com.example.screenguard.data

import android.content.Context
import android.content.SharedPreferences
import com.example.screenguard.service.MatchMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val PRESET_18PLUS_MANHWA = setOf(
    "pornhwa",
    "18+ Manhwa",
    "Adult Manhwa",
    "Adult Webtoon",
    "Adult Manhua",
    "Mature 18+",
    "18+ Comic",
    "Smut Manhwa",
    "Ero Manhwa",
    "Eromanga",
    "Hentai",
    "Doujinshi",
    "Doujin",
    "Ecchi",
    "Netorare",
    "NTR",
    "Uncensored Manhwa",
    "Raw 18+",
    "Lezhin 18+",
    "Toptoon 18+",
    "Toomics 18+",
    "NSFW Comic",
    "NSFW Webtoon",
    "Erotic Webtoon",
    "Hentai Manga",
    "Manhwa18",
    "Manhwa68",
    "ManyToon",
    "Hiperdex",
    "Manga18fx",
    "Toonily18",
    "Webtoon18",
    "Pornhwa.me",
    "ManhwaHentai",
    "ReadHentai",
    "stepmom",
    "stepsister",
    "noona's",
    "Secret Class",
    "Silent War",
    "Sexercise",
    "Touch to Unlock",
    "Close as Neighbors",
    "uncensored",
    "adult",
    "mature",
    "18+",
    "erotic",
    "nsfw",
    "smut",
    "cookie",
    "bikini",
    "swimsuit",
    "lingerie",
    "naked",
    "nacked",
    "nakked",
    "neked",
    "nude",
    "nudes",
    "topless",
    "boobs",
    "tits",
    "pussy",
    "dick",
    "cock",
    "porn",
    "porno",
    "sex",
    "erotica",
    "onlyfans",
    "TEST_BLOCKED_WORD"
)

val DEFAULT_BLOCKED_PACKAGES = setOf(
    "com.android.vending", // Google Play Store
    "chat.kijang.manga.cookie.browser" // Cookie Manga
)

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _blockPlayStore = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_PLAY_STORE, true))
    val blockPlayStore: StateFlow<Boolean> = _blockPlayStore.asStateFlow()

    private val _blockedPackages = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED_PACKAGES) ?: DEFAULT_BLOCKED_PACKAGES
    )
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _blockedWords = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED_WORDS, PRESET_18PLUS_MANHWA) ?: PRESET_18PLUS_MANHWA
    )
    val blockedWords: StateFlow<Set<String>> = _blockedWords.asStateFlow()

    private val _whitelist = MutableStateFlow(
        prefs.getStringSet(KEY_WHITELIST, setOf("com.example.screenguard", "com.example.screenguard.child", "com.example.screenguard.parent", "com.android.systemui")) ?: setOf("com.example.screenguard", "com.example.screenguard.child", "com.example.screenguard.parent", "com.android.systemui")
    )
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    private val _matchMode = MutableStateFlow(
        MatchMode.fromId(prefs.getString(KEY_MATCH_MODE, MatchMode.EXACT_WORD.id) ?: MatchMode.EXACT_WORD.id)
    )
    val matchMode: StateFlow<MatchMode> = _matchMode.asStateFlow()

    private val _showToast = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TOAST, true))
    val showToast: StateFlow<Boolean> = _showToast.asStateFlow()

    private val _guardianPin = MutableStateFlow(prefs.getString(KEY_GUARDIAN_PIN, "") ?: "")
    val guardianPin: StateFlow<String> = _guardianPin.asStateFlow()

    private val _cloudflareEndpoint = MutableStateFlow(prefs.getString(KEY_CLOUDFLARE_ENDPOINT, "") ?: "")
    val cloudflareEndpoint: StateFlow<String> = _cloudflareEndpoint.asStateFlow()

    private val _uninstallProtection = MutableStateFlow(prefs.getBoolean(KEY_UNINSTALL_PROTECTION, true))
    val uninstallProtection: StateFlow<Boolean> = _uninstallProtection.asStateFlow()

    private val _blockIncognito = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_INCOGNITO, true))
    val blockIncognito: StateFlow<Boolean> = _blockIncognito.asStateFlow()

    private val _blockHistoryDeletion = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_HISTORY_DELETION, true))
    val blockHistoryDeletion: StateFlow<Boolean> = _blockHistoryDeletion.asStateFlow()

    private val _protectPrivateDns = MutableStateFlow(prefs.getBoolean(KEY_PROTECT_PRIVATE_DNS, true))
    val protectPrivateDns: StateFlow<Boolean> = _protectPrivateDns.asStateFlow()

    private val _pauseUntil = MutableStateFlow(prefs.getLong(KEY_PAUSE_UNTIL, 0L))
    val pauseUntil: StateFlow<Long> = _pauseUntil.asStateFlow()

    // Temporary in-memory unlocked session
    @Volatile
    var isGuardianUnlockedUntil: Long = 0L

    private fun hashPin(pin: String): String {
        if (pin.isEmpty()) return ""
        val salt = "screenguard_salt_v1_"
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isGuardianUnlocked(): Boolean {
        if (!hasGuardianPin()) return true
        return System.currentTimeMillis() < isGuardianUnlockedUntil
    }

    fun unlockGuardianSession(durationMs: Long = 3 * 60 * 1000L) {
        isGuardianUnlockedUntil = System.currentTimeMillis() + durationMs
    }

    fun lockGuardianSession() {
        isGuardianUnlockedUntil = 0L
    }

    fun hasGuardianPin(): Boolean = _guardianPin.value.isNotBlank()

    fun setGuardianPin(pin: String) {
        val hashed = hashPin(pin)
        prefs.edit().putString(KEY_GUARDIAN_PIN, hashed).apply()
        _guardianPin.value = hashed
    }

    fun removeGuardianPin() {
        prefs.edit().remove(KEY_GUARDIAN_PIN).apply()
        _guardianPin.value = ""
        isGuardianUnlockedUntil = 0L
    }

    fun verifyGuardianPin(pin: String): Boolean {
        val current = _guardianPin.value
        if (current.isEmpty()) return true
        val hashed = hashPin(pin)
        return current == hashed
    }

    @Synchronized
    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        }
        return id
    }

    fun isAppPaused(): Boolean {
        val until = _pauseUntil.value
        return System.currentTimeMillis() < until
    }

    fun setPauseUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_PAUSE_UNTIL, timestamp).apply()
        _pauseUntil.value = timestamp
    }

    fun clearPause() {
        prefs.edit().putLong(KEY_PAUSE_UNTIL, 0L).apply()
        _pauseUntil.value = 0L
    }

    fun setCloudflareEndpoint(url: String) {
        prefs.edit().putString(KEY_CLOUDFLARE_ENDPOINT, url).apply()
        _cloudflareEndpoint.value = url
    }

    fun setUninstallProtection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UNINSTALL_PROTECTION, enabled).apply()
        _uninstallProtection.value = enabled
    }

    fun setBlockIncognito(block: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_INCOGNITO, block).apply()
        _blockIncognito.value = block
    }

    fun setBlockHistoryDeletion(block: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_HISTORY_DELETION, block).apply()
        _blockHistoryDeletion.value = block
    }

    fun setProtectPrivateDns(protect: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECT_PRIVATE_DNS, protect).apply()
        _protectPrivateDns.value = protect
    }

    private val _parentId = MutableStateFlow(prefs.getString(KEY_PARENT_ID, "") ?: "")
    val parentId: StateFlow<String> = _parentId.asStateFlow()

    private val _parentName = MutableStateFlow(prefs.getString(KEY_PARENT_NAME, "Guardian") ?: "Guardian")
    val parentName: StateFlow<String> = _parentName.asStateFlow()

    private val _pairedChildId = MutableStateFlow(prefs.getString(KEY_PAIRED_CHILD_ID, "") ?: "")
    val pairedChildId: StateFlow<String> = _pairedChildId.asStateFlow()

    private val _freeTimeUntil = MutableStateFlow(prefs.getLong(KEY_FREE_TIME_UNTIL, 0L))
    val freeTimeUntil: StateFlow<Long> = _freeTimeUntil.asStateFlow()

    private val _uninstallUnlockedUntil = MutableStateFlow(prefs.getLong(KEY_UNINSTALL_UNLOCKED_UNTIL, 0L))
    val uninstallUnlockedUntil: StateFlow<Long> = _uninstallUnlockedUntil.asStateFlow()

    fun getParentId(): String = _parentId.value
    fun getParentName(): String = _parentName.value
    fun getPairedChildId(): String = _pairedChildId.value

    fun setParentPairing(pId: String, pName: String) {
        prefs.edit().putString(KEY_PARENT_ID, pId).putString(KEY_PARENT_NAME, pName).apply()
        _parentId.value = pId
        _parentName.value = pName
    }

    fun setPairedChild(cId: String) {
        prefs.edit().putString(KEY_PAIRED_CHILD_ID, cId).apply()
        _pairedChildId.value = cId
    }

    fun isPairedWithParent(): Boolean = _parentId.value.isNotEmpty()

    fun setFreeTimeUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_FREE_TIME_UNTIL, timestamp).apply()
        _freeTimeUntil.value = timestamp
    }

    fun isFreeTimeActive(): Boolean = System.currentTimeMillis() < _freeTimeUntil.value

    fun setUninstallUnlockedUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_UNINSTALL_UNLOCKED_UNTIL, timestamp).apply()
        _uninstallUnlockedUntil.value = timestamp
    }

    fun isUninstallUnlocked(): Boolean = System.currentTimeMillis() < _uninstallUnlockedUntil.value

    init {
        // Ensure initial defaults include preset words if empty or first run
        if (!prefs.contains(KEY_BLOCKED_WORDS)) {
            prefs.edit().putStringSet(KEY_BLOCKED_WORDS, PRESET_18PLUS_MANHWA).apply()
            _blockedWords.value = PRESET_18PLUS_MANHWA
        }
        if (!prefs.contains(KEY_BLOCKED_PACKAGES)) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED_PACKAGES).apply()
            _blockedPackages.value = DEFAULT_BLOCKED_PACKAGES
        }
        // Ensure com.android.settings is purged from whitelist so Settings Guard functions
        val currentWhitelist = _whitelist.value.toMutableSet()
        if (currentWhitelist.remove("com.android.settings")) {
            prefs.edit().putStringSet(KEY_WHITELIST, currentWhitelist).apply()
            _whitelist.value = currentWhitelist
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled
    }

    fun setBlockPlayStore(block: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_PLAY_STORE, block).apply()
        _blockPlayStore.value = block
        val current = _blockedPackages.value.toMutableSet()
        if (block) {
            current.add("com.android.vending")
        } else {
            current.remove("com.android.vending")
        }
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, current).apply()
        _blockedPackages.value = current
    }

    fun addBlockedPackage(pkg: String): Boolean {
        val clean = pkg.trim()
        if (clean.isEmpty()) return false
        val current = _blockedPackages.value.toMutableSet()
        if (current.add(clean)) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, current).apply()
            _blockedPackages.value = current
            return true
        }
        return false
    }

    fun removeBlockedPackage(pkg: String): Boolean {
        val current = _blockedPackages.value.toMutableSet()
        if (current.remove(pkg)) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, current).apply()
            _blockedPackages.value = current
            return true
        }
        return false
    }

    fun load18PlusPreset(): Int {
        val current = _blockedWords.value.toMutableSet()
        var addedCount = 0
        for (word in PRESET_18PLUS_MANHWA) {
            if (current.add(word)) {
                addedCount++
            }
        }
        prefs.edit().putStringSet(KEY_BLOCKED_WORDS, current).apply()
        _blockedWords.value = current
        return addedCount
    }

    fun addBlockedWord(word: String): Boolean {
        val clean = word.trim()
        if (clean.isEmpty()) return false
        val current = _blockedWords.value.toMutableSet()
        if (current.add(clean)) {
            prefs.edit().putStringSet(KEY_BLOCKED_WORDS, current).apply()
            _blockedWords.value = current
            return true
        }
        return false
    }

    fun removeBlockedWord(word: String): Boolean {
        val current = _blockedWords.value.toMutableSet()
        if (current.remove(word)) {
            prefs.edit().putStringSet(KEY_BLOCKED_WORDS, current).apply()
            _blockedWords.value = current
            return true
        }
        return false
    }

    fun editBlockedWord(oldWord: String, newWord: String): Boolean {
        val cleanNew = newWord.trim()
        if (cleanNew.isEmpty()) return false
        val current = _blockedWords.value.toMutableSet()
        if (current.remove(oldWord)) {
            current.add(cleanNew)
            prefs.edit().putStringSet(KEY_BLOCKED_WORDS, current).apply()
            _blockedWords.value = current
            return true
        }
        return false
    }

    fun addWhitelistPackage(pkg: String): Boolean {
        val clean = pkg.trim()
        if (clean.isEmpty()) return false
        val current = _whitelist.value.toMutableSet()
        if (current.add(clean)) {
            prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
            _whitelist.value = current
            return true
        }
        return false
    }

    fun removeWhitelistPackage(pkg: String): Boolean {
        val current = _whitelist.value.toMutableSet()
        if (current.remove(pkg)) {
            prefs.edit().putStringSet(KEY_WHITELIST, current).apply()
            _whitelist.value = current
            return true
        }
        return false
    }

    fun setMatchMode(mode: MatchMode) {
        prefs.edit().putString(KEY_MATCH_MODE, mode.id).apply()
        _matchMode.value = mode
    }

    fun setShowToast(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TOAST, show).apply()
        _showToast.value = show
    }

    companion object {
        private const val PREFS_NAME = "screenguard_prefs"
        private const val KEY_ENABLED = "key_enabled"
        private const val KEY_BLOCK_PLAY_STORE = "key_block_play_store"
        private const val KEY_BLOCKED_PACKAGES = "key_blocked_packages"
        private const val KEY_BLOCKED_WORDS = "key_blocked_words"
        private const val KEY_WHITELIST = "key_whitelist"
        private const val KEY_MATCH_MODE = "key_match_mode"
        private const val KEY_SHOW_TOAST = "key_show_toast"
        private const val KEY_GUARDIAN_PIN = "key_guardian_pin"
        private const val KEY_CLOUDFLARE_ENDPOINT = "key_cloudflare_endpoint"
        private const val KEY_UNINSTALL_PROTECTION = "key_uninstall_protection"
        private const val KEY_BLOCK_INCOGNITO = "key_block_incognito"
        private const val KEY_BLOCK_HISTORY_DELETION = "key_block_history_deletion"
        private const val KEY_PROTECT_PRIVATE_DNS = "key_protect_private_dns"
        private const val KEY_PAUSE_UNTIL = "key_pause_until"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_PARENT_ID = "key_parent_id"
        private const val KEY_PARENT_NAME = "key_parent_name"
        private const val KEY_PAIRED_CHILD_ID = "key_paired_child_id"
        private const val KEY_FREE_TIME_UNTIL = "key_free_time_until"
        private const val KEY_UNINSTALL_UNLOCKED_UNTIL = "key_uninstall_unlocked_until"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
