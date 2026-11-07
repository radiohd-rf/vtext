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
    fun transliteratesEnglishWords() {
        assertEquals("пайтон и джава", SileroText.transliterateLatin("Python и Java"))
        assertEquals("баффер", SileroText.transliterateLatin("buffer"))
        assertEquals("кэш", SileroText.transliterateLatin("cache"))
        assertEquals("апи", SileroText.transliterateLatin("API"))
        assertEquals("си-пи-ю", SileroText.transliterateLatin("CPU"))
        assertEquals("хелло", SileroText.transliterateLatin("Hello"))
    }

    @Test
    fun normalizesLatinAndDigits() {
        assertEquals("глава один про пайтон", SileroText.normalizeForSpeech("глава 1 про Python"))
        assertEquals("апи", SileroText.normalizeForSpeech("API"))
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
    fun updatesPronunciationWithUserDictionary() {
        val terms = SileroText.parseDictionary(
            """
            # комментарий
            python=пайтон
            замок=з+амок

            docker=докер
            """.trimIndent(),
        )
        assertEquals(3, terms.size)
        val result = SileroText.normalizeForSpeech("Python и замок, docker", terms)
        assertTrue(result.contains("пайтон"))
        assertTrue(result.contains("з+амок"))
        assertTrue(result.contains("докер"))
    }

    @Test
    fun dictionaryStressOverridesNeuralPrediction() {
        // «главу»: г(0) л(1) а(2) в(3) у(4); словарь ставит ударение на «у».
        val accented = SileroText.accent(
            "главу",
            lookupStress = { 4 },
        ) { words ->
            words.map { SileroText.WordPrediction(stressVowel = 0, stressProbability = 0.9f, yoVowel = 0, yoProbability = 0f) }
        }
        assertEquals("глав+у", accented)
    }

    @Test
    fun fallsBackToNeuralPredictionWithoutDictionary() {
        val accented = SileroText.accent(
            "главу",
            lookupStress = { -1 },
        ) { words ->
            words.map { SileroText.WordPrediction(stressVowel = 0, stressProbability = 0.9f, yoVowel = 0, yoProbability = 0f) }
        }
        assertEquals("гл+аву", accented)
    }

    @Test
    fun keepsWordBoundariesInDictionary() {
        val terms = mapOf("кот" to "код")
        assertEquals("код и котик", SileroText.applyUserDictionary("кот и котик", terms))
    }

    @Test
    fun supportsMultiWordPhrasesInDictionary() {
        val terms = mapOf("на замок" to "на зам+ок", "старинный замок" to "старинный з+амок")
        assertEquals(
            "Повесил на зам+ок старинный з+амок",
            SileroText.applyUserDictionary("Повесил на замок старинный замок", terms),
        )
    }

    @Test
    fun parsesDictionaryWithBom() {
        val terms = SileroText.parseDictionary("\uFEFFкнигу=фолиант\npython=пайтон")
        assertEquals("фолиант", terms["книгу"])
        assertEquals("пайтон", terms["python"])
    }

    @Test
    fun parsesBoldItalicAndCaps() {
        val bold = SileroText.parseMarkup("Он сказал **важно** и ушёл.")
        assertEquals("Он сказал важно и ушёл.", bold.text)
        assertTrue(bold.emphasisSentences.contains(0))

        val italic = SileroText.parseMarkup("Он сказал *тихо* и ушёл.")
        assertEquals("Он сказал тихо и ушёл.", italic.text)
        assertTrue(italic.emphasisSentences.isEmpty())

        val caps = SileroText.parseMarkup("Это КРИК!")
        assertEquals("Это КРИК!", caps.text)
        assertTrue(caps.emphasisSentences.contains(0))
    }

    @Test
    fun emphasisShiftsSentenceType() {
        val sequence = SileroText.buildSequence(
            accentedText = "**Важно** и точка.",
            sos = "|",
            eos = "~",
            symbols = SileroMetaTestData.symbols,
            symbolToId = SileroMetaTestData.symbolToId,
            typeIds = mapOf("st" to 0),
        )
        assertEquals(SileroText.EMPHASIS_TYPE_SHIFT, sequence.types[1])
        assertTrue(sequence.focus.any { it > 0 })
    }

    @Test
    fun buildsSsmlBreaksAndLinePauses() {
        val ssml = SileroText.chunkPlan("Первое. <break time=\"2s\"/> Второе.", 200)
        assertEquals(2, ssml.size)
        assertEquals(2000, ssml.first().pauseMs)

        val strong = SileroText.chunkPlan("<speak>Раз. <break strength=\"strong\"/> Два.</speak>", 200)
        assertEquals(300, strong.first().pauseMs)

        val poetry = SileroText.chunkPlan("Строка первая\nСтрока вторая\nСтрока третья", 200)
        assertEquals(3, poetry.size)
        assertEquals(220, poetry[0].pauseMs)
        assertEquals(220, poetry[1].pauseMs)
    }

    @Test
    fun stripsFootnoteMarkers() {
        assertEquals("Новый год", SileroText.stripFootnotes("Новый год[12]"))
        assertEquals("без сносок", SileroText.stripFootnotes("без сносок"))
    }

    @Test
    fun transliteratesFrenchPhrases() {
        val french = SileroText.transliterateLatin("Je ne vous connais plus, mon ami")
        assertTrue(french.contains("же"))
        assertTrue(french.contains("ву"))
        assertTrue(french.contains("плю"))
    }

    @Test
    fun readsYearsInContext() {
        assertEquals(
            "в тысяча восемьсот пятом году",
            SileroText.normalizeForSpeech("в 1805 году"),
        )
        assertEquals(
            "тысяча восемьсот пятого года",
            SileroText.normalizeForSpeech("1805 года"),
        )
        assertEquals(
            "в тысяча девятисотом году",
            SileroText.normalizeForSpeech("в 1900 году"),
        )
    }

    @Test
    fun readsRomanNumerals() {
        assertEquals("глава семнадцать", SileroText.normalizeForSpeech("глава XVII"))
        assertEquals("", SileroText.normalizeForSpeech("\nII\n").trim())
    }

    @Test
    fun expandsAbbreviations() {
        assertEquals("и так далее", SileroText.expandAbbreviations("и т. д."))
        assertEquals("смотри страница", SileroText.expandAbbreviations("см. стр."))
    }

    @Test
    fun readsDecimalNumbers() {
        assertEquals("три запятая пять", SileroText.spellNumbers("3,5"))
        assertEquals("два запятая семьдесят пять", SileroText.spellNumbers("2.75"))
    }

    @Test
    fun computesWordTimingsFromDurations() {
        val ids = longArrayOf(2, 11, 12, 46, 13, 1)
        val durations = floatArrayOf(0f, 2f, 2f, 1f, 2f, 0f)
        val words = SileroText.wordTimings(ids, durations, SileroMetaTestData.symbolToId.entries
            .associate { (char, id) -> id to char })
        assertEquals(2, words.size)
        assertEquals("аб", words[0].text)
        assertEquals(0, words[0].startMs)
        assertEquals(50, words[0].endMs)
        assertEquals("в", words[1].text)
        assertEquals(62, words[1].startMs)
    }

    @Test
    fun mapsSynthesizedWordsToSourceWords() {
        val tracker = SileroText.WordTracker("в 1805 году было много событий")
        val indexes = tracker.advance(listOf("в", "тысяча", "восемьсот", "пятом", "году", "было"))
        assertEquals(listOf(0, 1, 1, 1, 2, 3), indexes)
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
