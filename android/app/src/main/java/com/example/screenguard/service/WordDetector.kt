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

    fun normalizeText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun compilePattern(target: String, matchMode: MatchMode): Pattern {
        val cleanTarget = normalizeText(target)
        if (cleanTarget.isEmpty()) {
            return Pattern.compile("$^") // Matches nothing
        }

        // Split multi-word phrases and rejoin with regex whitespace
        val parts = cleanTarget.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val joinedPhrase = parts.joinToString("\\s+") { Pattern.quote(it) }

        val patternStr = if (matchMode == MatchMode.EXACT_WORD) {
            // Word boundary match supporting symbols and unicode
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
     * Scans the provided text against the blocked words list.
     * Returns the list of matched words.
     */
    fun findMatches(
        fullText: String?,
        blockedWords: Collection<String>,
        matchMode: MatchMode = MatchMode.EXACT_WORD
    ): List<String> {
        if (fullText.isNullOrEmpty() || blockedWords.isEmpty()) {
            return emptyList()
        }

        val cleanText = normalizeText(fullText)
        if (cleanText.isEmpty()) return emptyList()

        val matches = mutableListOf<String>()
        for (word in blockedWords) {
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
    }
}
