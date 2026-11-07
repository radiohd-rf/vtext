package ru.vtext.app.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SileroHomosolverTest {

    @Test
    fun tokenizesLikePythonReference() {
        val vocab = buildVocab()
        try {
            val tokenizer = SileroBertTokenizer(vocab)
            assertEquals(listOf(2L, 32840L, 3L), tokenizer.encode("привет").toList())
            assertEquals(
                listOf("Стари", "##нный", "[HOMO]", "замок", "[/HOMO]", "стоял", "на", "горе", "."),
                tokenizer.tokenize("Старинный [HOMO] замок [/HOMO] стоял на горе."),
            )
            assertEquals(listOf(2L, 83828L, 28060L, 83829L, 3L), tokenizer.encode("[HOMO] горе [/HOMO]").toList())
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun appliesVariantKeepingCaseAndYo() {
        assertEquals("З+амок", SileroHomosolver.applyVariantToWord("Замок", "з+амок"))
        assertEquals("зам+ок", SileroHomosolver.applyVariantToWord("замок", "зам+ок"))
        assertEquals("вс+ё", SileroHomosolver.applyVariantToWord("все", "вс+ё"))
        assertEquals("Нач+ала", SileroHomosolver.applyVariantToWord("Начала", "нач+ала"))
    }

    /** Синтетический vocab.txt: id специальных токенов те же, что в модели Silero. */
    private fun buildVocab(): File {
        val tokens = MutableList(83_830) { "token$it" }
        tokens[0] = "[PAD]"
        tokens[1] = "[UNK]"
        tokens[2] = "[CLS]"
        tokens[3] = "[SEP]"
        tokens[28060] = "горе"
        tokens[32840] = "привет"
        tokens[83828] = "[HOMO]"
        tokens[83829] = "[/HOMO]"
        val extra = listOf("Стари", "##нный", "замок", "[/HOMO]", "стоял", "на", ".")
        var position = 100
        for (token in extra) {
            if (!tokens.contains(token)) {
                tokens[position] = token
                position++
            }
        }
        val file = File.createTempFile("vocab", ".txt")
        file.writeText(tokens.joinToString("\n"))
        return file
    }
}
