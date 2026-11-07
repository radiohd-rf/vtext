package ru.vtext.app.domain.parser

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocxParserTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `splits document into chapters by heading styles`() {
        val file = createDocx(
            document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                      <w:r><w:t>Глава 1. Начало</w:t></w:r>
                    </w:p>
                    <w:p><w:r><w:t>Первый абзац книги.</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Второй абзац книги.</w:t></w:r></w:p>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:t>Глава 2. Продолжение</w:t></w:r>
                    </w:p>
                    <w:p><w:r><w:t>Текст второй главы.</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
            core = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                    xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Тестовая книга</dc:title>
                  <dc:creator>Иван Петров</dc:creator>
                </cp:coreProperties>
            """.trimIndent(),
        )

        val parsed = DocxParser().parse(file, "fallback")

        assertEquals("Тестовая книга", parsed.title)
        assertEquals("Иван Петров", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Глава 1. Начало", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].segments.any { it.contains("Первый абзац") })
        assertEquals("Глава 2. Продолжение", parsed.chapters[1].title)
        assertEquals(listOf("Текст второй главы."), parsed.chapters[1].segments)
    }

    @Test
    fun `without headings returns single chapter and fallback title`() {
        val file = createDocx(
            document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Просто текст без заголовков.</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )

        val parsed = DocxParser().parse(file, "Имя файла")

        assertEquals("Имя файла", parsed.title)
        assertNull(parsed.author)
        assertEquals(1, parsed.chapters.size)
        assertEquals("Текст", parsed.chapters[0].title)
        assertEquals(listOf("Просто текст без заголовков."), parsed.chapters[0].segments)
    }

    @Test
    fun `marks italic and bold runs for emphasis`() {
        val file = createDocx(
            document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r><w:t>Обычный </w:t></w:r>
                      <w:r><w:rPr><w:i/></w:rPr><w:t>курсив</w:t></w:r>
                      <w:r><w:t> и </w:t></w:r>
                      <w:r><w:rPr><w:b/></w:rPr><w:t>жирный</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )

        val parsed = DocxParser().parse(file, "Имя файла")

        val segment = parsed.chapters[0].segments.first()
        assertTrue(segment.contains("*курсив*"))
        assertTrue(segment.contains("**жирный**"))
    }

    private fun createDocx(document: String, core: String? = null): File {
        val file = folder.newFile("sample.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(document.toByteArray())
            zip.closeEntry()
            if (core != null) {
                zip.putNextEntry(ZipEntry("docProps/core.xml"))
                zip.write(core.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
