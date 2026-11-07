package ru.vtext.app.domain.parser

import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/**
 * Разбор EPUB: container.xml → OPF (метаданные + spine) → XHTML-главы.
 * Глава — единица spine; текст извлекается из HTML и режется на фрагменты.
 */
class EpubParser : BookParser {

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
    ): ParsedBook {
        ZipFile(file).use { zip ->
            val rootPath = readRootFilePath(zip) ?: return ParsedBook(fallbackTitle, null, emptyList())
            val opfEntry = findEntry(zip, rootPath) ?: return ParsedBook(fallbackTitle, null, emptyList())
            val opfText = readText(zip, opfEntry)
            val opf = parseXml(opfText) ?: return ParsedBook(fallbackTitle, null, emptyList())

            val title = firstText(opf, "title") ?: fallbackTitle
            val author = firstText(opf, "creator")
            val baseDir = rootPath.substringBeforeLast('/', "")

            val manifest = mutableMapOf<String, String>()
            forEachElement(opf, "item") { item ->
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = resolve(baseDir, href)
                }
            }

            val spineIds = mutableListOf<String>()
            forEachElement(opf, "itemref") { ref ->
                val idref = ref.getAttribute("idref")
                if (idref.isNotBlank()) spineIds += idref
            }

            val chapters = mutableListOf<Chapter>()
            var number = 0
            for (id in spineIds) {
                val path = manifest[id] ?: continue
                if (!path.endsWith(".xhtml", true) && !path.endsWith(".html", true) && !path.endsWith(".htm", true)) {
                    continue
                }
                val entry = findEntry(zip, path) ?: continue
                val html = readText(zip, entry)
                var text = HtmlText.extract(html)
                if (text.isBlank()) continue
                val chapterTitle = HtmlText.htmlTitle(html)
                // Заголовок главы не дублируем в озвучиваемом тексте.
                if (chapterTitle != null && text.startsWith(chapterTitle)) {
                    text = text.removePrefix(chapterTitle).trimStart('\n', ' ')
                }
                if (text.isBlank()) continue
                number += 1
                val segments = TextSegmenter.segmentFor(text, preset)
                if (segments.isNotEmpty()) {
                    chapters += Chapter(chapterTitle ?: "Глава $number", segments)
                }
            }

            return ParsedBook(title, author, chapters)
        }
    }

    private fun readRootFilePath(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        val text = readText(zip, entry)
        val match = Regex("""full-path\s*=\s*"([^"]+)"""").find(text)
        return match?.groupValues?.get(1)
    }

    private fun readText(zip: ZipFile, entry: java.util.zip.ZipEntry): String =
        zip.getInputStream(entry).use { TextDecoder.decode(it.readBytes()) }

    private fun findEntry(zip: ZipFile, path: String): java.util.zip.ZipEntry? {
        val normalized = path.trimStart('/')
        zip.getEntry(normalized)?.let { return it }
        val decoded = runCatching { URLDecoder.decode(normalized, "UTF-8") }.getOrNull()
        if (decoded != null && decoded != normalized) zip.getEntry(decoded)?.let { return it }
        // Иногда регистр в OPF не совпадает с именами внутри архива.
        return zip.entries().asSequence().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    private fun resolve(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        if (!cleanHref.contains('/')) {
            return if (baseDir.isBlank()) cleanHref else "$baseDir/$cleanHref"
        }
        val parts = (if (baseDir.isBlank()) mutableListOf() else baseDir.split('/').toMutableList())
        for (part in cleanHref.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun parseXml(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(XmlSanitizer.sanitize(xml))))
    } catch (_: Exception) {
        null
    }

    private fun firstText(document: Document, tag: String): String? {
        val nodes = document.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && localName(node) == tag) {
                val value = textOf(node).trim()
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun forEachElement(document: Document, name: String, action: (Element) -> Unit) {
        val nodes = document.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && localName(node) == name) action(node)
        }
    }

    private fun textOf(node: Node): String {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            return node.nodeValue ?: ""
        }
        val builder = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) builder.append(textOf(children.item(i)))
        return builder.toString()
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':').lowercase()
}
