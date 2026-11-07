package ru.vtext.app.domain.parser

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook

/** Разбор FB2 (XML). Документ предварительно санитизируется от DOCTYPE и HTML-сущностей. */
class Fb2Parser : BookParser {

    override fun parse(file: File, fallbackTitle: String): ParsedBook {
        val raw = TextDecoder.decode(file.readBytes())
        val xml = XmlSanitizer.sanitize(raw)

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        val title = firstText(document, "book-title") ?: fallbackTitle
        val author = readAuthor(document)

        val bodies = document.getElementsByTagName("body")
        val body = (0 until bodies.length)
            .map { bodies.item(it) as Element }
            .firstOrNull { (it.getAttribute("name") ?: "") != "notes" }

        val chapters = mutableListOf<Chapter>()
        if (body != null) {
            val sections = childElements(body, "section")
            if (sections.isEmpty()) {
                val text = paragraphsText(body)
                if (text.isNotBlank()) chapters += Chapter(title, TextSegmenter.segment(text))
            } else {
                sections.forEachIndexed { index, section ->
                    val sectionTitle = childElements(section, "title")
                        .firstOrNull()
                        ?.let { textOf(it).trim() }
                        ?.ifBlank { null }
                        ?: "Глава ${index + 1}"
                    val text = paragraphsText(section)
                    if (text.isNotBlank()) {
                        chapters += Chapter(sectionTitle, TextSegmenter.segment(text))
                    }
                }
            }
        }

        return ParsedBook(title, author, chapters)
    }

    private fun readAuthor(document: Document): String? {
        val authors = document.getElementsByTagName("author")
        val names = mutableListOf<String>()
        for (i in 0 until authors.length) {
            val author = authors.item(i) as? Element ?: continue
            val first = firstChildText(author, "first-name")
            val middle = firstChildText(author, "middle-name")
            val last = firstChildText(author, "last-name")
            val nickname = firstChildText(author, "nickname")
            val name = listOfNotNull(first, middle, last)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { nickname.orEmpty() }
            if (name.isNotBlank() && name !in names) names += name
        }
        return names.take(3).joinToString(", ").ifBlank { null }
    }

    private fun firstText(document: Document, tag: String): String? {
        val nodes = document.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val value = textOf(nodes.item(i)).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun firstChildText(parent: Element, tag: String): String? {
        val children = parent.getElementsByTagName(tag)
        for (i in 0 until children.length) {
            val value = textOf(children.item(i)).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun childElements(parent: Element, name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && localName(child) == name) result += child
        }
        return result
    }

    private fun paragraphsText(root: Element): String {
        val builder = StringBuilder()
        collectParagraphs(root, builder)
        return builder.toString().trim()
    }

    private fun collectParagraphs(node: Node, builder: StringBuilder) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && localName(child) == "p") {
                val text = textOf(child).trim()
                if (text.isNotBlank()) builder.append(text).append("\n\n")
            } else if (child is Element) {
                if (localName(child) == "title") continue
                collectParagraphs(child, builder)
            }
        }
    }

    private fun textOf(node: Node): String {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            return node.nodeValue ?: ""
        }
        val builder = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            builder.append(textOf(children.item(i)))
        }
        return builder.toString()
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':').lowercase()
}
