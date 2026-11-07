package ru.vtext.app.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun keepsImageAltText() {
        val html = "<p>Текст.</p><img src=\"x.png\" alt=\"Схема алгоритма\">"

        val text = HtmlText.extract(html)

        assertTrue(text.contains("Схема алгоритма"))
        assertTrue(text.contains("Текст."))
    }

    @Test
    fun ignoresImagesWithoutAlt() {
        val html = "<p>Текст.</p><img src=\"x.png\">"

        val text = HtmlText.extract(html)

        assertFalse(text.contains("<img"))
        assertTrue(text.contains("Текст."))
    }
}
