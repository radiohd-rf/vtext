package ru.vtext.app.tts

import java.io.File

/**
 * BERT-токенизатор из пакета Silero (wordpiece): нужен модели разрешения
 * омографов. Порт `custom_tokenizers/bert_tokenizer.py`.
 */
internal class SileroBertTokenizer(vocabFile: File) {

    private val vocab: Map<String, Int>
    private val clsId: Int
    private val sepId: Int
    private val unkId: Int
    private val padId: Int
    private val homoStartId: Int
    private val homoEndId: Int

    init {
        val map = HashMap<String, Int>(131_072)
        vocabFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, token -> map[token] = index }
        }
        vocab = map
        clsId = map[CLS] ?: 2
        sepId = map[SEP] ?: 3
        unkId = map[UNK] ?: 1
        padId = map[PAD] ?: 0
        homoStartId = map["[HOMO]"] ?: -1
        homoEndId = map["[/HOMO]"] ?: -1
    }

    fun encode(text: String): LongArray {
        val tokens = tokenize(text)
        val ids = LongArray(tokens.size + 2)
        ids[0] = clsId.toLong()
        tokens.forEachIndexed { index, token -> ids[index + 1] = (vocab[token] ?: unkId).toLong() }
        ids[ids.size - 1] = sepId.toLong()
        return ids
    }

    fun homoPositions(ids: LongArray): Pair<Int, Int> {
        var start = -1
        var end = -1
        for (index in ids.indices) {
            if (ids[index].toInt() == homoStartId && start < 0) start = index
            if (ids[index].toInt() == homoEndId && end < 0) end = index
        }
        return start to end
    }

    fun padId(): Long = padId.toLong()

    fun tokenize(text: String): List<String> {
        val words = mutableListOf<String>()
        val cleaned = buildString {
            for (char in text) {
                val code = char.code
                if (code == 0 || code == 0xFFFD || isControl(char)) continue
                append(if (isWhitespace(char)) ' ' else char)
            }
        }
        for (token in cleaned.trim().split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            if (token in NEVER_SPLIT) {
                words += token
                continue
            }
            var start = 0
            for (index in token.indices) {
                if (isPunctuation(token[index])) {
                    if (index > start) words += token.substring(start, index)
                    words += token[index].toString()
                    start = index + 1
                }
            }
            if (start < token.length) words += token.substring(start)
        }
        val result = mutableListOf<String>()
        for (word in words) {
            if (word.length > MAX_INPUT_CHARS) {
                result += UNK
                continue
            }
            var start = 0
            var bad = false
            while (start < word.length) {
                var end = word.length
                var piece: String? = null
                while (start < end) {
                    val candidate = word.substring(start, end)
                    val lookup = if (start > 0) "##$candidate" else candidate
                    if (vocab.containsKey(lookup)) {
                        piece = lookup
                        break
                    }
                    end--
                }
                if (piece == null) {
                    bad = true
                    break
                }
                result += piece
                start = end
            }
            if (bad) result += UNK
        }
        return result
    }

    private fun isControl(char: Char): Boolean {
        if (char == '\t' || char == '\n' || char == '\r') return false
        return when (Character.getType(char).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
            Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            -> true
            else -> false
        }
    }

    private fun isWhitespace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\r' ||
            Character.getType(char).toByte() == Character.SPACE_SEPARATOR

    private fun isPunctuation(char: Char): Boolean {
        val code = char.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(char).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }

    private companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"
        const val MAX_INPUT_CHARS = 100
        val NEVER_SPLIT = setOf("[HOMO]", "[/HOMO]", CLS, SEP, UNK, PAD, "[MASK]")
    }
}
