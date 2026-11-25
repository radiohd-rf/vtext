package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class Fb2NestedSectionsTest {

    @Test
    fun splitsNestedSectionsIntoChapters() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description>
                <title-info>
                  <book-title>Nested</book-title>
                  <author><first-name>Ivan</first-name><last-name>Petrov</last-name></author>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>Из Сибири</p></title>
                  <p>Вступление части.</p>
                  <section><title><p>1</p></title><p>Первая глава.</p></section>
                  <section><title><p>2</p></title><p>Вторая глава.</p></section>
                </section>
                <section>
                  <title><p>Остров Сахалин</p></title>
                  <section><title><p>I</p></title><p>Письмо первое.</p></section>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()
        val file = File.createTempFile("nested", ".fb2")
        try {
            file.writeText(xml)
            val parsed = Fb2Parser().parse(file, "Nested", TextPreset.BOOK, null)
            val titles = parsed.chapters.map { it.title }
            println("TITLES: $titles")
            assertEquals(
                listOf(
                    "Из Сибири",
                    "Из Сибири · 1",
                    "Из Сибири · 2",
                    "Остров Сахалин · I",
                ),
                titles.take(5),
            )
            assertTrue(parsed.chapters[1].segments.first().contains("Первая глава"))
            assertTrue(parsed.chapters[2].segments.first().contains("Вторая глава"))
        } finally {
            file.delete()
        }
    }
}
