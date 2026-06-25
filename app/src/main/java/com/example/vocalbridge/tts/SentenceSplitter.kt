package com.example.vocalbridge.tts

/**
 * Splits input text into sentence-sized chunks optimized for TTS synthesis.
 *
 * Key design decisions:
 * - Split on sentence boundaries (.!?;:) while preserving punctuation
 * - Handle abbreviations (Mr., Dr., etc.) to avoid false splits
 * - Merge very short fragments (<10 chars) with adjacent sentences
 * - Cap maximum chunk length (~300 chars) to prevent long inference delays
 */
object SentenceSplitter {
    // Common abbreviations that shouldn't trigger a split
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "ave",
        "inc", "ltd", "corp", "co", "dept", "univ", "assn",
        "gen", "gov", "sgt", "cpl", "pvt", "capt", "lt", "cmdr",
        "adm", "maj", "col", "brig", "fig", "eq", "approx",
        "vol", "no", "vs", "etc", "al", "jan", "feb", "mar",
        "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    private const val MAX_CHUNK_LENGTH = 120
    private const val MIN_CHUNK_LENGTH = 10

    /**
     * Split text into sentences suitable for TTS synthesis.
     * Returns a list of non-empty sentence strings.
     */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val rawSentences = splitRaw(text.trim())
        return mergeShorties(rawSentences)
    }

    private fun splitRaw(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            current.append(ch)

            when {
                // Sentence-ending punctuation
                ch == '!' || ch == '?' -> {
                    // Consume trailing punctuation and whitespace
                    i = consumeTrailing(text, i + 1, current)
                    flushSentence(current, sentences)
                }

                ch == '.' -> {
                    // Check if this is an abbreviation
                    if (!isAbbreviation(text, i)) {
                        // Check for ellipsis (...)
                        i = consumeTrailing(text, i + 1, current)
                        flushSentence(current, sentences)
                    } else {
                        i++
                    }
                }

                // Split on phrases to ensure low cold-start latency
                ch == ',' || ch == ';' || ch == ':' || ch == '-' -> {
                    // Split on phrase boundaries if the chunk is reasonably sized
                    if (current.length >= MIN_CHUNK_LENGTH) {
                        i = consumeTrailing(text, i + 1, current)
                        flushSentence(current, sentences)
                    } else {
                        i++
                    }
                }

                ch == '\n' -> {
                    // Newlines often indicate paragraph breaks — split
                    if (current.toString().trim().isNotEmpty()) {
                        flushSentence(current, sentences)
                    } else {
                        current.clear()
                    }
                    i++
                }

                else -> {
                    // Check if we've exceeded max length — force split at word boundary
                    if (current.length >= MAX_CHUNK_LENGTH) {
                        forceSplitAtWordBoundary(current, sentences)
                    }
                    i++
                }
            }
        }

        // Flush remaining text
        if (current.toString().trim().isNotEmpty()) {
            flushSentence(current, sentences)
        }

        return sentences
    }

    private fun consumeTrailing(text: String, startIdx: Int, builder: StringBuilder): Int {
        var i = startIdx
        // Consume repeated punctuation and spaces (e.g., "!!!", "...", ", ")
        while (i < text.length && (text[i] == '.' || text[i] == '!' || text[i] == '?' || text[i] == ',' || text[i] == ' ')) {
            builder.append(text[i])
            i++
        }
        return i
    }

    private fun flushSentence(builder: StringBuilder, sentences: MutableList<String>) {
        val sentence = builder.toString().trim()
        if (sentence.isNotEmpty()) {
            sentences.add(sentence)
        }
        builder.clear()
    }

    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        // Find the word before the dot
        var wordStart = dotIndex - 1
        while (wordStart >= 0 && text[wordStart].isLetter()) {
            wordStart--
        }
        wordStart++

        if (wordStart >= dotIndex) return false

        val word = text.substring(wordStart, dotIndex).lowercase()

        // Check if it's a known abbreviation
        if (word in ABBREVIATIONS) return true

        // Single letter followed by dot (e.g., "A.", "J.K.")
        if (word.length == 1) return true

        // Check if followed by a lowercase letter (continuation)
        val afterDot = dotIndex + 1
        if (afterDot < text.length && text[afterDot] == ' ') {
            val nextCharIdx = afterDot + 1
            if (nextCharIdx < text.length && text[nextCharIdx].isLowerCase()) {
                return true
            }
        }

        // Check for decimal numbers (e.g., "3.14")
        if (wordStart > 0 && text[wordStart - 1].isDigit()) return true
        if (dotIndex + 1 < text.length && text[dotIndex + 1].isDigit()) return true

        return false
    }

    private fun forceSplitAtWordBoundary(builder: StringBuilder, sentences: MutableList<String>) {
        val text = builder.toString()
        // Find the last space, comma, or dash to split at
        val splitPoints = listOf(
            text.lastIndexOf(' '),
            text.lastIndexOf(','),
            text.lastIndexOf('-'),
            text.lastIndexOf('–'),
        )
        val splitAt = splitPoints.filter { it > MIN_CHUNK_LENGTH }.maxOrNull()

        if (splitAt != null) {
            val sentence = text.substring(0, splitAt + 1).trim()
            val remainder = text.substring(splitAt + 1)
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            builder.clear()
            builder.append(remainder)
        }
        // If no good split point, we just let it grow until a natural break
    }

    /**
     * Merge very short fragments with their neighbors to prevent
     * garbled output from tiny synthesis calls.
     */
    private fun mergeShorties(sentences: List<String>): List<String> {
        if (sentences.size <= 1) return sentences

        val merged = mutableListOf<String>()
        val pending = StringBuilder()

        for (sentence in sentences) {
            if (pending.isNotEmpty()) {
                pending.append(" ")
            }
            pending.append(sentence)

            if (pending.length >= MIN_CHUNK_LENGTH) {
                merged.add(pending.toString())
                pending.clear()
            }
        }

        // Flush any remaining short text
        if (pending.isNotEmpty()) {
            if (merged.isNotEmpty()) {
                // Merge with the last sentence
                merged[merged.lastIndex] = merged.last() + " " + pending.toString()
            } else {
                merged.add(pending.toString())
            }
        }

        return merged
    }
}
