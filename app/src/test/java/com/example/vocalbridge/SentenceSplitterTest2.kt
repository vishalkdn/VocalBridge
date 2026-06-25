package com.example.vocalbridge

import com.example.vocalbridge.tts.SentenceSplitter
import org.junit.Test
import org.junit.Assert.assertEquals

class SentenceSplitterTest2 {
    @Test
    fun testParagraphs() {
        val text = "Paragraph 1 sentence 1. Paragraph 1 sentence 2.\n\nParagraph 2 sentence 1. Paragraph 2 sentence 2."
        val sentences = SentenceSplitter.split(text)
        for (s in sentences) {
            println("SENTENCE: '$s'")
        }
    }
}
