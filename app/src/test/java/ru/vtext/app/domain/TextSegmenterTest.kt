package ru.vtext.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun splitsParagraphsIntoSeparateSegments() {
        val result = TextSegmenter.segment("Первый абзац.\n\nВторой абзац.")
        assertEquals(listOf("Первый абзац.", "Второй абзац."), result)
    }

    @Test
    fun keepsShortParagraphAsSingleSegment() {
        val result = TextSegmenter.segment("Короткий текст.")
        assertEquals(listOf("Короткий текст."), result)
    }

    @Test
    fun respectsMaxLengthForLongText() {
        val text = (1..50).joinToString(" ") { "Предложение номер $it." }
        val result = TextSegmenter.segment(text, maxChars = 80)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.length <= 80 })
        val sentenceCount = result.joinToString(" ").split('.').count { it.isNotBlank() }
        assertEquals(50, sentenceCount)
    }

    @Test
    fun cutsOverlongSentenceWithoutSpaces() {
        val result = TextSegmenter.segment("а".repeat(25), maxChars = 10)
        assertEquals(listOf("а".repeat(10), "а".repeat(10), "а".repeat(5)), result)
    }
}
