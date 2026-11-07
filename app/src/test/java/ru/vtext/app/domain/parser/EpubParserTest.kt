package ru.vtext.app.domain.parser

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubParserTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parsesSpineChaptersAndMetadata() {
        val file = folder.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Электронная книга</dc:title>
                    <dc:creator>Мария Иванова</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zip.write(
                "<html><head><title>Глава один</title></head><body><h1>Глава один</h1><p>Первый текст.</p><p>Второй&nbsp;абзац.</p></body></html>"
                    .toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/chapter2.xhtml"))
            zip.write(
                "<html><body><h1>Глава два</h1><p>Третий текст.</p></body></html>".toByteArray(),
            )
            zip.closeEntry()
        }

        val parsed = EpubParser().parse(file, "fallback")

        assertEquals("Электронная книга", parsed.title)
        assertEquals("Мария Иванова", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Глава один", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].segments.first().contains("Первый текст"))
        assertEquals("Глава два", parsed.chapters[1].title)
    }

    @Test
    fun returnsEmptyChaptersForBrokenArchive() {
        val file = folder.newFile("broken.epub")
        file.writeText("not a zip")

        val parsed = runCatching { EpubParser().parse(file, "fallback") }

        assertTrue(parsed.isFailure || parsed.getOrNull()?.chapters.isNullOrEmpty())
    }
}
