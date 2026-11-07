package ru.vtext.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Fb2ParserTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parsesMetadataAndSections() {
        val file = folder.newFile("book.fb2")
        file.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE FictionBook SYSTEM "http://www.gribuser.ru/xml/fictionbook/2.0/FictionBook.dtd">
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description>
                <title-info>
                  <book-title>Тестовая книга</book-title>
                  <author><first-name>Иван</first-name><last-name>Петров</last-name></author>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>Глава первая</p></title>
                  <p>Первый&nbsp;абзац.</p>
                </section>
                <section>
                  <title><p>Глава вторая</p></title>
                  <p>Второй абзац.</p>
                </section>
              </body>
            </FictionBook>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val parsed = Fb2Parser().parse(file, "fallback")

        assertEquals("Тестовая книга", parsed.title)
        assertEquals("Иван Петров", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Глава первая", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].segments.first().contains("Первый абзац"))
        assertEquals("Первый абзац.", parsed.chapters[0].segments.first())
    }

    @Test
    fun ignoresNotesBody() {
        val file = folder.newFile("notes.fb2")
        file.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <description><title-info><book-title>Сноски</book-title></title-info></description>
              <body name="notes"><section><p>Сноска.</p></section></body>
              <body><section><title><p>Глава</p></title><p>Основной текст.</p></section></body>
            </FictionBook>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val parsed = Fb2Parser().parse(file, "fallback")

        assertEquals(1, parsed.chapters.size)
        assertTrue(parsed.chapters[0].segments.first().contains("Основной текст"))
    }
}
