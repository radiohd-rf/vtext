package ru.vtext.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class FootnoteLinesTest {

    @Test
    fun splitsNumberLinesAndContinuesText() {
        val notes = PdfParser().parseFootnoteLines(
            listOf(
                "7",
                "Рейфилд Д. Жизнь Антона Чехова. М.: Б.С.Г.-Пресс, 2010.",
                "С. 257–265.",
                "8 Там же. С. 12.",
            ),
            pageIndex = 11,
        )
        assertEquals(2, notes.size)
        assertEquals("p12-7", notes[0].label)
        assertEquals(
            "Рейфилд Д. Жизнь Антона Чехова. М.: Б.С.Г.-Пресс, 2010. С. 257–265.",
            notes[0].text,
        )
        assertEquals("p12-8", notes[1].label)
        assertEquals("Там же. С. 12.", notes[1].text)
    }

    @Test
    fun ignoresLinesWithoutNumber() {
        val notes = PdfParser().parseFootnoteLines(
            listOf("Подпись под рисунком", "12 Текст сноски."),
            pageIndex = 3,
        )
        assertEquals(1, notes.size)
        assertEquals("p4-12", notes[0].label)
        assertEquals("Текст сноски.", notes[0].text)
    }
}
