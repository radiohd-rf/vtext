package ru.vtext.app.domain.parser

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/**
 * Разбор DOCX: это ZIP с word/document.xml. Абзацы собираем из w:p,
 * заголовки распознаём по стилю (Heading N / Заголовок N) и режем на фрагменты.
 */
class DocxParser : BookParser {

    private val headingStyle = Regex(
        """^(heading|заголовок|title|название|section)(\d|\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
    ): ParsedBook {
        ZipFile(file).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
                ?: return ParsedBook(fallbackTitle, null, emptyList())
            val document = parseXml(zip.readBytes(documentEntry))
                ?: return ParsedBook(fallbackTitle, null, emptyList())

            val title = readCoreProperty(zip, "title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val author = readCoreProperty(zip, "creator")?.takeIf { it.isNotBlank() }

            val paragraphs = document.getElementsByTagName("*")
            val blocks = mutableListOf<Pair<String, StringBuilder>>()
            var currentTitle = "Текст"
            var body = StringBuilder()

            fun flush() {
                if (body.isNotBlank()) {
                    blocks += currentTitle to body
                }
                body = StringBuilder()
            }

            for (index in 0 until paragraphs.length) {
                val node = paragraphs.item(index)
                if (node !is Element || localName(node) != "p") continue
                val text = paragraphText(node).trim()
                if (text.isEmpty()) continue
                if (isHeading(node)) {
                    flush()
                    currentTitle = text
                } else {
                    if (body.isNotEmpty()) body.append("\n\n")
                    body.append(text)
                }
            }
            flush()

            val chapters = blocks.map { (chapterTitle, chapterBody) ->
                Chapter(chapterTitle, TextSegmenter.segmentFor(chapterBody.toString(), preset))
            }.filter { it.segments.isNotEmpty() }

            return ParsedBook(title, author, chapters)
        }
    }

    private fun paragraphText(paragraph: Element): String {
        val builder = StringBuilder()
        fun walk(node: Node) {
            if (node is Element) {
                when (localName(node)) {
                    "t" -> {
                        builder.append(node.textContent ?: "")
                        return
                    }
                    "tab" -> {
                        builder.append(' ')
                        return
                    }
                    "br", "cr" -> {
                        builder.append('\n')
                        return
                    }
                    "nobreakhyphen" -> {
                        builder.append('-')
                        return
                    }
                }
            }
            val children = node.childNodes
            for (index in 0 until children.length) walk(children.item(index))
        }
        walk(paragraph)
        return builder.toString()
    }

    private fun isHeading(paragraph: Element): Boolean {
        val properties = firstChildElement(paragraph, "ppr") ?: return false
        val style = firstChildElement(properties, "pstyle")?.let { styleValue(it) }
        if (style != null && headingStyle.containsMatchIn(style)) return true
        return firstChildElement(properties, "outlinelvl") != null
    }

    private fun styleValue(element: Element): String? {
        val qualified = element.getAttribute("w:val")
        val plain = element.getAttribute("val")
        return (qualified.ifBlank { plain }).ifBlank { null }
    }

    private fun firstChildElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && localName(child) == name) return child
        }
        return null
    }

    private fun readCoreProperty(zip: ZipFile, property: String): String? {
        val entry = zip.getEntry("docProps/core.xml") ?: return null
        val document = parseXml(zip.readBytes(entry)) ?: return null
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && localName(node) == property) {
                val value = (node.textContent ?: "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun ZipFile.readBytes(entry: java.util.zip.ZipEntry): ByteArray =
        getInputStream(entry).use { it.readBytes() }

    private fun parseXml(bytes: ByteArray): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        // DOCX приходит от пользователя: запрещаем DOCTYPE и внешние сущности (XXE).
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        null
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':').lowercase()
}
