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
    fun dropsFootnoteBodiesAndSuperscripts() {
        val html = """
            <p>Основной текст<sup>1</sup>.</p>
            <aside epub:type="footnote"><p>1. Тело сноски, которое не читаем.</p></aside>
        """.trimIndent()

        val text = HtmlText.extract(html)

        assertTrue(text.contains("Основной текст"))
        assertFalse(text.contains("Тело сноски"))
        assertFalse(text.contains("1."))
    }

    @Test
    fun ignoresImagesWithoutAlt() {
        val html = "<p>Текст.</p><img src=\"x.png\">"

        val text = HtmlText.extract(html)

        assertFalse(text.contains("<img"))
        assertTrue(text.contains("Текст."))
    }
}
