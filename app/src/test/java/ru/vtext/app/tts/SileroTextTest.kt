package ru.vtext.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SileroTextTest {

    @Test
    fun spellsNumbersInRussian() {
        assertEquals("ноль", SileroText.spellNumber(0))
        assertEquals("один", SileroText.spellNumber(1))
        assertEquals("двадцать один", SileroText.spellNumber(21))
        assertEquals("сто", SileroText.spellNumber(100))
        assertEquals("тысяча", SileroText.spellNumber(1000))
        assertEquals("две тысячи двадцать четыре", SileroText.spellNumber(2024))
        assertEquals("два миллиона", SileroText.spellNumber(2_000_000))
    }

    @Test
    fun replacesDigitsInText() {
        assertEquals("глава один", SileroText.spellNumbers("глава 1"))
        assertEquals("в тысяча девятьсот сорок один", SileroText.spellNumbers("в 1941"))
    }

    @Test
    fun classifiesSentences() {
        assertEquals("st", SileroText.classifyText("Привет."))
        assertEquals("exclam", SileroText.classifyText("Привет!"))
        assertEquals("general_q", SileroText.classifyText("Ты придёшь?"))
        assertEquals("wh_q", SileroText.classifyText("Кто здесь?"))
        assertEquals("alternative_q", SileroText.classifyText("Ты или он?"))
        assertEquals("tag_q", SileroText.classifyText("Хорошо, правда?"))
        assertEquals("st|exclam", SileroText.classifyText("Привет. Как дела!"))
    }

    @Test
    fun placesStressOnPredictedVowel() {
        val accented = SileroText.accent("привет") { words ->
            words.map { SileroText.WordPrediction(stressVowel = 1, stressProbability = 0.9f, yoVowel = 0, yoProbability = 0f) }
        }
        assertEquals("прив+ет", accented)
    }

    @Test
    fun restoresYoWhenPredicted() {
        // «ежик»: гласные е(0), и(2); «ё» предсказана на первой «е».
        val accented = SileroText.accent("ежик") { words ->
            words.map { SileroText.WordPrediction(stressVowel = 0, stressProbability = 0.9f, yoVowel = 1, yoProbability = 0.9f) }
        }
        assertEquals("+ёжик", accented)
    }

    @Test
    fun keepsPunctuationAndSpaces() {
        val accented = SileroText.accent("Привет, мир!") { words ->
            words.map { SileroText.WordPrediction(stressVowel = 1, stressProbability = 0.9f, yoVowel = 0, yoProbability = 0f) }
        }
        assertEquals("Прив+ет, м+ир!", accented)
    }

    @Test
    fun buildsSequenceWithSentenceTypes() {
        val symbolToId = SileroMetaTestData.symbolToId
        val sequence = SileroText.buildSequence(
            accentedText = "Привет.",
            sos = "|",
            eos = "~",
            symbols = SileroMetaTestData.symbols,
            symbolToId = symbolToId,
            typeIds = mapOf("st" to 0, "exclam" to 5),
        )
        // | п р и в е т . ~
        assertEquals(9, sequence.ids.size)
        assertEquals(2L, sequence.ids.first())
        assertEquals(1L, sequence.ids.last())
        assertTrue(sequence.types.all { it == 0L })
    }

    @Test
    fun splitsLongTextIntoChunks() {
        val text = "Первое предложение. Второе предложение. Третье предложение."
        val chunks = SileroText.chunks(text, 20)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.isNotBlank() })
    }
}

internal object SileroMetaTestData {
    const val symbols = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "

    val symbolToId: Map<Char, Int> = symbols.mapIndexed { index, char -> char to index }.toMap()
}
