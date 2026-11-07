package ru.vtext.app.data

import android.graphics.Bitmap
import android.util.Base64
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import ru.vtext.app.domain.model.BookFormat
import ru.vtext.app.domain.parser.TextDecoder

/** Извлекает обложку книги в файл books/<id>/cover (если она есть в формате). */
object CoverExtractor {

    fun extract(format: BookFormat, source: File, dir: File): File? = runCatching {
        when (format) {
            BookFormat.PDF -> extractPdf(source, dir)
            BookFormat.EPUB -> extractEpub(source, dir)
            BookFormat.FB2 -> extractFb2(source, dir)
            BookFormat.TXT, BookFormat.DOCX -> null
        }
    }.getOrNull()

    private fun extractPdf(source: File, dir: File): File? {
        PDDocument.load(source).use { document ->
            if (document.numberOfPages == 0) return null
            val bitmap = PDFRenderer(document).renderImage(0, 1f)
            val output = File(dir, COVER)
            FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            }
            bitmap.recycle()
            return output.takeIf { it.length() > 0 }
        }
    }

    private fun extractEpub(source: File, dir: File): File? {
        ZipFile(source).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val containerText = readText(zip, container)
            val rootPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(containerText)
                ?.groupValues?.get(1) ?: return null
            val opfEntry = findEntry(zip, rootPath) ?: return null
            val opf = readText(zip, opfEntry)
            val baseDir = rootPath.substringBeforeLast('/', "")
            val coverHref = findCoverHref(opf) ?: return null
            val path = resolve(baseDir, coverHref)
            val entry = findEntry(zip, path) ?: return null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return save(dir, bytes)
        }
    }

    private fun findCoverHref(opf: String): String? {
        val items = Regex("(?is)<item\\b[^>]*>").findAll(opf).map { it.value }.toList()
        val coverImage = items.firstOrNull { "cover-image" in attribute(it, "properties") }
        if (coverImage != null) return attribute(coverImage, "href")
        val coverId = Regex("<meta\\b[^>]*name\\s*=\\s*\"cover\"[^>]*content\\s*=\\s*\"([^\"]+)\"")
            .find(opf)?.groupValues?.get(1)
            ?: return null
        return items.firstOrNull { attribute(it, "id") == coverId }
            ?.let { attribute(it, "href") }
    }

    private fun attribute(tag: String, name: String): String =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"")
            .find(tag)?.groupValues?.get(1)
            .orEmpty()

    private fun extractFb2(source: File, dir: File): File? {
        val text = TextDecoder.decode(source.readBytes())
        val coverId = Regex("""(?is)<coverpage>.*?<image[^>]*href\s*=\s*["']#([^"']+)["']""")
            .find(text)?.groupValues?.get(1) ?: return null
        val payload = Regex(
            """(?is)<binary\b[^>]*id\s*=\s*["']${Regex.escape(coverId)}["'][^>]*>(.*?)</binary>""",
        ).find(text)?.groupValues?.get(1) ?: return null
        val bytes = Base64.decode(payload.replace(Regex("\\s+"), ""), Base64.DEFAULT)
        return save(dir, bytes)
    }

    private fun save(dir: File, bytes: ByteArray): File {
        val output = File(dir, COVER)
        output.writeBytes(bytes)
        return output
    }

    private fun readText(zip: ZipFile, entry: java.util.zip.ZipEntry): String =
        zip.getInputStream(entry).use { TextDecoder.decode(it.readBytes()) }

    private fun findEntry(zip: ZipFile, path: String): java.util.zip.ZipEntry? {
        val normalized = path.trimStart('/')
        zip.getEntry(normalized)?.let { return it }
        val decoded = runCatching { URLDecoder.decode(normalized, "UTF-8") }.getOrNull()
        if (decoded != null && decoded != normalized) zip.getEntry(decoded)?.let { return it }
        return zip.entries().asSequence().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        if (!clean.contains('/')) return if (baseDir.isBlank()) clean else "$baseDir/$clean"
        val parts = (if (baseDir.isBlank()) mutableListOf() else baseDir.split('/').toMutableList())
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private const val COVER = "cover"
}
