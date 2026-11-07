package ru.vtext.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfEmphasisTest {

    private fun chars(text: String, style: Int): List<Pair<String, Int>> =
        text.map { it.toString() to style }

    @Test
    fun marksItalicAndBoldRuns() {
        val tracker = PdfStyleTracker()
        val chars = chars("Обычный ", 0) + chars("курсив", 1) + chars(" и ", 0) + chars("жирный", 2)
        val text = emphasizedText(chars, tracker)
        assertEquals("Обычный *курсив* и **жирный**", text)
    }

    @Test
    fun doesNotMarkShortBoldHeadings() {
        val tracker = PdfStyleTracker()
        val text = emphasizedText(chars("Глава 1", 2), tracker)
        assertEquals("Глава 1", text)
    }

    @Test
    fun detectsSuperscriptMarkers() {
        // Основной текст 12 pt, базовая линия 700; цифра 8 pt и поднята на 720 (выше по оси PDFBox).
        val sizes = floatArrayOf(12f, 12f, 12f, 8f, 12f)
        val ys = floatArrayOf(700f, 700f, 700f, 696f, 700f)
        val mask = superscriptMask(sizes, ys)
        assertEquals(listOf(false, false, false, true, false), mask.toList())
    }

    @Test
    fun keepsSmallTextOnItsOwnLine() {
        // Мелкая строка (подпись/сноска) без подъёма — не верхний индекс.
        val sizes = floatArrayOf(8f, 8f, 8f)
        val ys = floatArrayOf(700f, 700f, 700f)
        val mask = superscriptMask(sizes, ys)
        assertEquals(listOf(false, false, false), mask.toList())
    }

    @Test
    fun disablesEmphasisForMostlyBoldText() {
        val tracker = PdfStyleTracker()
        emphasizedText(chars("а".repeat(5000), 2), tracker)
        assertTrue(tracker.disabled)
        val next = emphasizedText(chars("Текст", 2), tracker)
        assertEquals("Текст", next)
    }

    @Test
    fun keepsPlainTextUnchanged() {
        val tracker = PdfStyleTracker()
        val text = emphasizedText(chars("Просто текст.", 0), tracker)
        assertEquals("Просто текст.", text)
        assertEquals(0, tracker.marked)
    }
}
