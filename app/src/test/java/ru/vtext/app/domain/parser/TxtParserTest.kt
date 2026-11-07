package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxtParserTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun detectsChaptersByHeadings() {
        val file = folder.newFile("book.txt")
        file.writeText(
            """
            Глава 1
            Первый текст.

            Глава 2
            Второй текст.
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val parsed = TxtParser().parse(file, "Книга")

        assertEquals("Книга", parsed.title)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Глава 1", parsed.chapters[0].title)
        assertEquals("Глава 2", parsed.chapters[1].title)
        assertTrue(parsed.chapters[0].segments.first().contains("Первый текст"))
    }

    @Test
    fun fallsBackToSingleChapter() {
        val file = folder.newFile("plain.txt")
        file.writeText("Просто текст без заголовков.", Charsets.UTF_8)

        val parsed = TxtParser().parse(file, "Плоский")

        assertEquals(1, parsed.chapters.size)
        assertEquals("Текст", parsed.chapters[0].title)
    }

    @Test
    fun decodesWindows1251() {
        val file = File(folder.root, "cp1251.txt")
        file.writeBytes("Привет, мир.".toByteArray(charset("windows-1251")))

        val parsed = TxtParser().parse(file, "Кодировка")

        assertTrue(parsed.chapters.first().segments.first().contains("Привет"))
    }
}
