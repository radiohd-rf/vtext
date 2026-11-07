package ru.vtext.app.domain

/**
 * Режет текст книги на фрагменты для TTS: сначала по абзацам и предложениям,
 * и только очень длинные предложения — по словам. Слова и обычные предложения
 * не разрываются, чтобы речь не звучала обрывками.
 */
object TextSegmenter {

    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")
    private val whitespace = Regex("\\s+")

    /**
     * @param maxChars желаемый размер фрагмента; предложение целиком сохраняется,
     * если оно не длиннее [hardMaxChars].
     * @param hardMaxChars порог, после которого очень длинное предложение
     * делится по словам (никогда внутри слова).
     */
    fun segment(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        hardMaxChars: Int = maxChars * 2,
    ): List<String> {        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalized
            .split(Regex("\\n\\s*\\n+"))
            .map { it.replace(whitespace, " ").trim() }
            .filter { it.isNotEmpty() }

        val result = ArrayList<String>()
        for (paragraph in paragraphs) {
            if (paragraph.length <= maxChars) {
                result += paragraph
                continue
            }
            val buffer = StringBuilder()
            for (part in paragraph.split(sentenceSplit)) {
                val sentence = part.trim()
                if (sentence.isEmpty()) continue
                when {
                    sentence.length > hardMaxChars -> {
                        flush(buffer, result)
                        result += splitLongSentence(sentence, maxChars)
                    }
                    // Длинное, но цельное предложение не режем посередине.
                    sentence.length > maxChars -> {
                        flush(buffer, result)
                        result += sentence
                    }
                    buffer.length + sentence.length + 1 > maxChars -> {
                        flush(buffer, result)
                        buffer.append(sentence)
                    }
                    else -> {
                        if (buffer.isNotEmpty()) buffer.append(' ')
                        buffer.append(sentence)
                    }
                }
            }
            flush(buffer, result)
        }
        return result
    }

    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        val words = sentence.split(whitespace).filter { it.isNotEmpty() }
        val parts = ArrayList<String>()
        val buffer = StringBuilder()
        for (word in words) {
            when {
                word.length > maxChars -> {
                    flush(buffer, parts)
                    parts += word
                }
                buffer.length + word.length + 1 > maxChars -> {
                    flush(buffer, parts)
                    buffer.append(word)
                }
                else -> {
                    if (buffer.isNotEmpty()) buffer.append(' ')
                    buffer.append(word)
                }
            }
        }
        flush(buffer, parts)
        return parts
    }

    private fun flush(buffer: StringBuilder, result: MutableList<String>) {
        if (buffer.isNotEmpty()) {
            result += buffer.toString()
            buffer.setLength(0)
        }
    }

    const val DEFAULT_MAX_CHARS = 1000
}
