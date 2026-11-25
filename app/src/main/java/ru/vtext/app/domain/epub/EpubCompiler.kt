package ru.vtext.app.domain.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.vtext.app.domain.model.Chapter

/**
 * Собирает EPUB 3 из уже разобранных глав: одна XHTML-глава на главу,
 * один `<p class="seg">` на фрагмент, картинки — `<figure>` после нужного фрагмента.
 *
 * В OPF пишется метка `vtext`, чтобы [ru.vtext.app.domain.parser.EpubParser]
 * разобрал файл обратно без повторной нарезки текста.
 */
object EpubCompiler {

    /** Версия формата: при изменении старые собранные книги пересобираются. */
    const val VERSION = 1

    private const val MARKER = "vtext"

    fun write(
        target: File,
        title: String,
        author: String?,
        chapters: List<Chapter>,
        assetsDir: File,
        language: String = "ru",
    ) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // mimetype обязан идти первым и без сжатия.
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.setLevel(6)

            zip.putText(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            )
            zip.putText("OEBPS/style.css", CSS)

            val images = chapters.flatMap { it.images }.distinctBy { it.file }
            val manifest = StringBuilder()
            val spine = StringBuilder()

            chapters.forEachIndexed { index, chapter ->
                val name = "text/ch${(index + 1).toString().padStart(3, '0')}.xhtml"
                zip.putText("OEBPS/$name", chapterXhtml(chapter))
                manifest.append(
                    """    <item id="ch${index + 1}" href="$name" media-type="application/xhtml+xml"/>""" + "\n",
                )
                spine.append("""    <itemref idref="ch${index + 1}"/>""" + "\n")
            }

            images.forEachIndexed { index, image ->
                val source = File(assetsDir, image.file)
                if (!source.isFile) return@forEachIndexed
                zip.putNextEntry(ZipEntry("OEBPS/images/${image.file}"))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                manifest.append(
                    """    <item id="img${index + 1}" href="images/${image.file}" """ +
                        """media-type="${mimeOf(image.file)}"/>""" + "\n",
                )
            }

            manifest.append("""    <item id="css" href="style.css" media-type="text/css"/>""" + "\n")
            manifest.append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" + "\n")

            zip.putText("OEBPS/nav.xhtml", navXhtml(chapters))
            zip.putText(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">vtext-${title.hashCode()}</dc:identifier>
    <dc:title>${escape(title)}</dc:title>
    <dc:language>$language</dc:language>
    ${if (author.isNullOrBlank()) "" else "<dc:creator>${escape(author)}</dc:creator>"}
    <meta name="$MARKER" content="$VERSION"/>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>""",
            )
        }
        // Версия кладётся рядом: по ней видно, что пересборка не нужна.
        File(target.parentFile, "epub.version").writeText(VERSION.toString())
    }

    private fun chapterXhtml(chapter: Chapter): String {
        val body = StringBuilder()
        if (chapter.title.isNotBlank()) {
            body.append("  <h1>${escape(chapter.title)}</h1>\n")
        }
        for (segment in chapter.images.filter { it.afterSegment < 0 }) {
            body.append(figure(segment.file, segment.alt))
        }
        chapter.segments.forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                val html = escape(text).replace("\n", "<br/>")
                body.append("  <p class=\"seg\">$html</p>\n")
            }
            chapter.images.filter { it.afterSegment == index }.forEach { image ->
                body.append(figure(image.file, image.alt))
            }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ru">
<head>
  <title>${escape(chapter.title)}</title>
  <link rel="stylesheet" type="text/css" href="../style.css"/>
</head>
<body>
$body</body>
</html>"""
    }

    private fun figure(file: String, alt: String): String =
        "  <figure class=\"pic\"><img src=\"../images/${escapeAttr(file)}\" " +
            "alt=\"${escapeAttr(alt)}\"/></figure>\n"

    private fun navXhtml(chapters: List<Chapter>): String {
        val items = chapters.mapIndexed { index, chapter ->
            "      <li><a href=\"text/ch${(index + 1).toString().padStart(3, '0')}.xhtml\">" +
                "${escape(chapter.title.ifBlank { "Глава ${index + 1}" })}</a></li>\n"
        }.joinToString("")
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Оглавление</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Оглавление</h1>
    <ol>
$items    </ol>
  </nav>
</body>
</html>"""
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(text: String): String = escape(text).replace("\"", "&quot;")

    private const val CSS = """body { margin: 0 1.2em; line-height: 1.55; font-size: 1em; }
h1 { font-size: 1.35em; margin: 1.2em 0 0.7em; }
p.seg { margin: 0.45em 0; text-align: justify; }
figure.pic { margin: 0.8em 0; text-align: center; }
figure.pic img { max-width: 100%; height: auto; }
"""
}
