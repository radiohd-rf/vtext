package ru.vtext.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.parser.startsParagraph

class TextLayoutTest {

    @Test
    fun detectsPoetryByShortLines() {
        // «Евгений Онегин» в PDF: строки по 20–35 символов.
        val verse = (1..60).joinToString("\n") { "Мой дядя самых честных правил, $it" }
        assertTrue(PoetryDetector.isPoetry(verse))
    }

    @Test
    fun treatsJustifiedProseAsProse() {
        // Проза: выключенные строки по 80–100 символов.
        val prose = (1..60).joinToString("\n") {
            "Война и мир, том первый, часть первая, глава $it — длинная строка прозы для проверки"
        }
        assertFalse(PoetryDetector.isPoetry(prose))
    }

    @Test
    fun tooShortTextIsNotPoetry() {
        assertFalse(PoetryDetector.isPoetry("Короткий текст\nиз двух строк"))
    }

    @Test
    fun detectsParagraphStarts() {
        assertTrue(startsParagraph("Привет, мир"))
        assertTrue(startsParagraph("— Здравствуйте"))
        assertTrue(startsParagraph("«Цитата»"))
        assertTrue(startsParagraph("1941 год"))
        assertFalse(startsParagraph("продолжение строки"))
    }
}
