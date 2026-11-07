package ru.vtext.app.domain

/**
 * Режет текст на фрагменты для TTS: сначала по абзацам, затем по предложениям.
 * Ограничение длины важно из-за лимита TextToSpeech.speak (~4000 символов) и
 * для удобного возобновления чтения с точностью до фрагмента.
 */
object TextSegmenter {

    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")
    private val whitespace = Regex("\\s+")

    fun segment(text: String, maxChars: Int = 100): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
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
                if (sentence.length > maxChars) {
                    flush(buffer, result)
                    result += sentence.chunked(maxChars)
                } else if (buffer.length + sentence.length + 1 > maxChars) {
                    flush(buffer, result)
                    buffer.append(sentence)
                } else {
                    if (buffer.isNotEmpty()) buffer.append(' ')
                    buffer.append(sentence)
                }
            }
            flush(buffer, result)
        }
        return result
    }

    private fun flush(buffer: StringBuilder, result: MutableList<String>) {
        if (buffer.isNotEmpty()) {
            result += buffer.toString()
            buffer.setLength(0)
        }
    }
}
