package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class RtfParserTest {

    @Test
    fun extractsCyrillicTextAndPages() {
        val file = File("G:\\silero-tmp\\notes-test.rtf")
        if (!file.isFile) return
        val parsed = RtfParser().parse(file, "Test RTF", TextPreset.BOOK, null)
        val text = parsed.chapters.flatMap { it.segments }.joinToString(" ")
        println("TEXT: $text")
        assertTrue(text, text.contains("Это текст с примечанием"))
        assertTrue(text, text.contains("Второй абзац книги"))
        assertTrue(text, text.contains("Вторая страница текста"))
        assertTrue(parsed.chapters.size == 2)
    }
}
