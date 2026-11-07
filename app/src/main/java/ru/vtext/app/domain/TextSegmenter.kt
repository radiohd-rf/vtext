package ru.vtext.app.domain

import ru.vtext.app.domain.cleanup.CaptionFilter
import ru.vtext.app.domain.model.TextPreset

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
    ): List<String> {
        val normalized = CaptionFilter.removeCaptions(text)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
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

    fun segmentFor(
        text: String,
        preset: TextPreset,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<String> = if (preset == TextPreset.POETRY) {
        segmentPoetry(text, maxChars)
    } else {
        segment(text, maxChars)
    }

    /** Стихи: сохраняем переносы строк и строфы, не склеиваем предложения. */
    fun segmentPoetry(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val normalized = CaptionFilter.removeCaptions(text)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val stanzas = normalized.split(Regex("\\n\\s*\\n+"))
        val result = ArrayList<String>()
        for (stanza in stanzas) {
            val lines = stanza.lines()
                .map { it.trimEnd() }
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
            if (lines.isEmpty()) continue
            val stanzaText = lines.joinToString("\n")
            if (stanzaText.length <= maxChars) {
                result += stanzaText
                continue
            }
            val buffer = StringBuilder()
            for (line in lines) {
                if (line.length > maxChars) {
                    flush(buffer, result)
                    result += splitLongSentence(line, maxChars)
                } else if (buffer.length + line.length + 1 > maxChars) {
                    flush(buffer, result)
                    buffer.append(line)
                } else {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
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
