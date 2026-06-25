package com.example.vocalbridge.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun testSimpleSplit() {
        val text = "Hello world! This is a test. How are you?"
        val result = SentenceSplitter.split(text)
        assertEquals(3, result.size)
        assertEquals("Hello world!", result[0])
        assertEquals("This is a test.", result[1])
        assertEquals("How are you?", result[2])
    }

    @Test
    fun testAbbreviations() {
        val text = "Mr. George visited Dr. Watson in London. The case was resolved."
        val result = SentenceSplitter.split(text)
        assertEquals(2, result.size)
        assertEquals("Mr. George visited Dr. Watson in London.", result[0])
        assertEquals("The case was resolved.", result[1])
    }

    @Test
    fun testShortSentenceMerging() {
        // "Short." is less than 10 chars, so it should merge with the next sentence
        val text = "Short. This is a longer sentence."
        val result = SentenceSplitter.split(text)
        assertEquals(1, result.size)
        assertEquals("Short. This is a longer sentence.", result[0])
    }

    @Test
    fun testEllipsisAndRepeatedPunctuation() {
        val text = "Wait for it... Now! Go!!!"
        val result = SentenceSplitter.split(text)
        assertEquals(2, result.size)
        assertEquals("Wait for it...", result[0])
        assertEquals("Now! Go!!!", result[1])
    }
}
