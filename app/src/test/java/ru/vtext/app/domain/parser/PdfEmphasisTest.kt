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
