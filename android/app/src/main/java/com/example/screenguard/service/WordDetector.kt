package com.example.screenguard.service

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

enum class MatchMode(val id: String) {
    EXACT_WORD("exact_word"),
    SUBSTRING("substring");

    companion object {
        fun fromId(id: String): MatchMode = entries.firstOrNull { it.id == id } ?: EXACT_WORD
    }
}

object WordDetector {
    private val patternCache = ConcurrentHashMap<Pair<String, MatchMode>, Pattern>()

    @Volatile
    private var cachedCombinedWords: Set<String>? = null
    @Volatile
    private var cachedCombinedMode: MatchMode? = null
    @Volatile
    private var cachedCombinedPattern: Pattern? = null

    fun normalizeText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun compilePattern(target: String, matchMode: MatchMode): Pattern {
        val cleanTarget = normalizeText(target)
        if (cleanTarget.isEmpty()) {
            return Pattern.compile("$^")
        }

        val parts = cleanTarget.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val joinedPhrase = parts.joinToString("\\s+") { Pattern.quote(it) }

        val patternStr = if (matchMode == MatchMode.EXACT_WORD) {
            "(?<!\\w)$joinedPhrase(?!\\w)"
        } else {
            joinedPhrase
        }

        return Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }

    private fun getPattern(word: String, matchMode: MatchMode): Pattern {
        val key = Pair(word, matchMode)
        return patternCache.computeIfAbsent(key) { compilePattern(word, matchMode) }
    }

    /**
     * Builds and caches a single combined regex pattern for all blocked words.
     * Evaluates in a single pass rather than looping N times.
     */
    private fun getCombinedPattern(blockedWords: Set<String>, matchMode: MatchMode): Pattern? {
        if (blockedWords.isEmpty()) return null

        val currentPattern = cachedCombinedPattern
        if (currentPattern != null && cachedCombinedWords == blockedWords && cachedCombinedMode == matchMode) {
            return currentPattern
        }

        synchronized(this) {
            if (cachedCombinedWords == blockedWords && cachedCombinedMode == matchMode && cachedCombinedPattern != null) {
                return cachedCombinedPattern
            }

            // Sort phrases by length descending so longer compound phrases match first
            val validPhrases = blockedWords
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }

            if (validPhrases.isEmpty()) return null

            val joinedGroup = validPhrases.joinToString("|") { word ->
                val clean = normalizeText(word)
                val parts = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
                parts.joinToString("\\s+") { Pattern.quote(it) }
            }

            val patternStr = if (matchMode == MatchMode.EXACT_WORD) {
                "(?<!\\w)(?:$joinedGroup)(?!\\w)"
            } else {
                "(?:$joinedGroup)"
            }

            val compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            cachedCombinedWords = blockedWords
            cachedCombinedMode = matchMode
            cachedCombinedPattern = compiled
            return compiled
        }
    }

    /**
     * Fast single-pass scanner that returns immediately upon finding the first match.
     * Ideal for live accessibility service events.
     */
    fun findFirstMatch(
        fullText: String?,
        blockedWords: Collection<String>,
        matchMode: MatchMode = MatchMode.EXACT_WORD
    ): String? {
        if (fullText.isNullOrEmpty() || blockedWords.isEmpty()) return null
        val cleanText = normalizeText(fullText)
        if (cleanText.isEmpty()) return null

        val wordSet = if (blockedWords is Set<String>) blockedWords else blockedWords.toSet()
        val combinedPattern = getCombinedPattern(wordSet, matchMode)
        if (combinedPattern != null) {
            val matcher = combinedPattern.matcher(cleanText)
            if (matcher.find()) {
                val matchedStr = matcher.group()
                // Map matched string back to canonical original blocked word if possible
                return wordSet.firstOrNull { it.equals(matchedStr, ignoreCase = true) } ?: matchedStr
            }
            return null
        }

        // Fallback
        for (word in wordSet) {
            val cleanWord = word.trim()
            if (cleanWord.isEmpty()) continue
            if (getPattern(cleanWord, matchMode).matcher(cleanText).find()) {
                return cleanWord
            }
        }
        return null
    }

    /**
     * Scans the provided text against the blocked words list.
     * Uses the combined single-pass pattern to collect matches efficiently.
     */
    fun findMatches(
        fullText: String?,
        blockedWords: Collection<String>,
        matchMode: MatchMode = MatchMode.EXACT_WORD
    ): List<String> {
        if (fullText.isNullOrEmpty() || blockedWords.isEmpty()) return emptyList()
        val cleanText = normalizeText(fullText)
        if (cleanText.isEmpty()) return emptyList()

        val wordSet = if (blockedWords is Set<String>) blockedWords else blockedWords.toSet()
        val combinedPattern = getCombinedPattern(wordSet, matchMode)

        if (combinedPattern != null) {
            val matches = LinkedHashSet<String>()
            val matcher = combinedPattern.matcher(cleanText)
            while (matcher.find()) {
                val matchedStr = matcher.group()
                val canonical = wordSet.firstOrNull { it.equals(matchedStr, ignoreCase = true) } ?: matchedStr
                matches.add(canonical)
            }
            return matches.toList()
        }

        val matches = mutableListOf<String>()
        for (word in wordSet) {
            val cleanWord = word.trim()
            if (cleanWord.isEmpty()) continue
            val pattern = getPattern(cleanWord, matchMode)
            if (pattern.matcher(cleanText).find()) {
                matches.add(cleanWord)
            }
        }
        return matches
    }

    fun clearCache() {
        patternCache.clear()
        cachedCombinedWords = null
        cachedCombinedMode = null
        cachedCombinedPattern = null
    }
}
