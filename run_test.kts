import java.io.File

fun splitRaw(text: String): List<String> {
    val sentences = mutableListOf<String>()
    val current = StringBuilder()

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        current.append(ch)

        when {
            ch == '!' || ch == '?' -> {
                i = consumeTrailing(text, i + 1, current)
                flushSentence(current, sentences)
            }
            ch == '.' -> {
                i = consumeTrailing(text, i + 1, current)
                flushSentence(current, sentences)
            }
            ch == '\n' -> {
                if (current.toString().trim().isNotEmpty()) {
                    flushSentence(current, sentences)
                } else {
                    current.clear()
                }
                i++
            }
            else -> i++
        }
    }
    if (current.toString().trim().isNotEmpty()) flushSentence(current, sentences)
    return sentences
}

fun consumeTrailing(text: String, startIdx: Int, builder: StringBuilder): Int {
    var i = startIdx
    while (i < text.length && (text[i] == '.' || text[i] == '!' || text[i] == '?')) {
        builder.append(text[i])
        i++
    }
    return i
}

fun flushSentence(builder: StringBuilder, sentences: MutableList<String>) {
    val sentence = builder.toString().trim()
    if (sentence.isNotEmpty()) {
        sentences.add(sentence)
    }
    builder.clear()
}

val res = splitRaw("Paragraph 1 sentence 1. Paragraph 1 sentence 2.\n\nParagraph 2 sentence 1. Paragraph 2 sentence 2.")
for (s in res) {
    println("SENTENCE: '$s'")
}
