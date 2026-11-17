package com.example.screenguard

import com.example.screenguard.service.MatchMode
import com.example.screenguard.service.WordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WordDetectorTest {

    @Before
    fun setUp() {
        WordDetector.clearCache()
    }

    @Test
    fun testExactWordMatching() {
        val blocked = listOf("cat", "dog")

        // Standalone words should match
        assertEquals(listOf("cat"), WordDetector.findMatches("I have a cat.", blocked))
        assertEquals(listOf("dog"), WordDetector.findMatches("Look at that dog!", blocked))
        assertEquals(setOf("cat", "dog"), WordDetector.findMatches("Both cat and dog are here", blocked).toSet())

        // Substrings inside words should NOT match in EXACT_WORD mode
        assertTrue(WordDetector.findMatches("The education system is fine", blocked).isEmpty())
        assertTrue(WordDetector.findMatches("Concatenate string", blocked).isEmpty())
        assertTrue(WordDetector.findMatches("Cathedral ceiling", blocked).isEmpty())
        assertTrue(WordDetector.findMatches("Underdog story", blocked).isEmpty())
    }

    @Test
    fun testCaseInsensitivity() {
        val blocked = listOf("Secret_Token", "PASSWORD")

        assertEquals(listOf("Secret_Token"), WordDetector.findMatches("secret_token leaked", blocked))
        assertEquals(listOf("Secret_Token"), WordDetector.findMatches("SECRET_TOKEN leaked", blocked))
        assertEquals(listOf("PASSWORD"), WordDetector.findMatches("My password is 123", blocked))
    }

    @Test
    fun testMultiWordPhrases() {
        val blocked = listOf("confidential leak", "top secret document")

        assertEquals(listOf("confidential leak"), WordDetector.findMatches("This is a confidential leak right here", blocked))
        assertEquals(listOf("confidential leak"), WordDetector.findMatches("This is a confidential\nleak right here", blocked))
        assertEquals(listOf("confidential leak"), WordDetector.findMatches("This is a confidential   leak", blocked))
        assertEquals(listOf("top secret document"), WordDetector.findMatches("TOP SECRET DOCUMENT", blocked))
    }

    @Test
    fun testPunctuationAndSymbols() {
        val blocked = listOf("TEST_BLOCKED_WORD", "api_key", "danger")

        assertEquals(listOf("TEST_BLOCKED_WORD"), WordDetector.findMatches("Found TEST_BLOCKED_WORD in file.", blocked))
        assertEquals(listOf("danger"), WordDetector.findMatches("(danger) ahead!", blocked))
        assertEquals(listOf("api_key"), WordDetector.findMatches("api_key = 'xyz';", blocked))
        assertEquals(listOf("TEST_BLOCKED_WORD"), WordDetector.findMatches("\"TEST_BLOCKED_WORD\"", blocked))
    }

    @Test
    fun testUnicodeNormalization() {
        val blocked = listOf("café", "über")

        assertEquals(listOf("café"), WordDetector.findMatches("Welcome to the café today", blocked))
        assertEquals(listOf("über"), WordDetector.findMatches("Drive with über now", blocked))
    }

    @Test
    fun testSubstringMode() {
        val blocked = listOf("cat")

        assertEquals(listOf("cat"), WordDetector.findMatches("education", blocked, MatchMode.SUBSTRING))
        assertEquals(listOf("cat"), WordDetector.findMatches("concatenate", blocked, MatchMode.SUBSTRING))
    }
}
