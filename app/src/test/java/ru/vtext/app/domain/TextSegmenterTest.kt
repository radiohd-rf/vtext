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
        val text = (1..50).joinToString(" ") { "Короткое предложение $it." }
        val result = TextSegmenter.segment(text, maxChars = 80)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.length <= 80 })
        val sentenceCount = result.joinToString(" ").split('.').count { it.isNotBlank() }
        assertEquals(50, sentenceCount)
    }

    @Test
    fun doesNotSplitWordWithoutSpaces() {
        val word = "а".repeat(25)
        val result = TextSegmenter.segment(word, maxChars = 10)

        assertEquals(listOf(word), result)
    }

    @Test
    fun keepsLongSentenceWholeBelowHardLimit() {
        val sentence = (1..10).joinToString(" ") { "слово" } + "."
        val result = TextSegmenter.segment(sentence, maxChars = 20, hardMaxChars = 100)

        assertEquals(listOf(sentence), result)
    }

    @Test
    fun splitsVeryLongSentenceByWords() {
        val sentence = (1..40).joinToString(" ") { "слово" } + "."
        val result = TextSegmenter.segment(sentence, maxChars = 20, hardMaxChars = 40)

        assertTrue(result.size > 1)
        assertTrue(result.all { it.length <= 20 })
        assertEquals(sentence, result.joinToString(" "))
    }
}
