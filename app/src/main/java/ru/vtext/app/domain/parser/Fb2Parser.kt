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
import ru.vtext.app.domain.model.TextPreset

/** Разбор FB2 (XML). Документ предварительно санитизируется от DOCTYPE и HTML-сущностей. */
class Fb2Parser : BookParser {

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
        assetsDir: File?,
    ): ParsedBook {
        val raw = TextDecoder.decode(file.readBytes())
        val xml = XmlSanitizer.sanitize(raw)

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        val title = firstText(document, "book-title") ?: fallbackTitle
        val author = readAuthor(document)

        val definitions = readNoteDefinitions(document)
        val bodies = document.getElementsByTagName("body")
        val body = (0 until bodies.length)
            .map { bodies.item(it) as Element }
            .firstOrNull {
                val name = it.getAttribute("name").lowercase()
                name != "notes" && name != "comments"
            }

        val chapters = mutableListOf<Chapter>()
        if (body != null) {
            val sections = childElements(body, "section")
            if (sections.isEmpty()) {
                val text = paragraphsText(body)
                if (text.isNotBlank()) chapters += Chapter(title, TextSegmenter.segmentFor(text, preset))
            } else {
                sections.forEachIndexed { index, section ->
                    appendSection(section, null, index, chapters, preset)
                }
            }
        }

        val withNotes = chapters.map { chapter ->
            val notes = chapter.segments
                .flatMap { NOTE_MARKER.findAll(it).toList() }
                .mapNotNull { match ->
                    val id = match.groupValues[1]
                    val display = match.groupValues[2]
                    val text = definitions[id] ?: return@mapNotNull null
                    ru.vtext.app.domain.model.ChapterNote("$id-$display", text)
                }
                .distinctBy { it.label }
            chapter.copy(footnotes = notes)
        }
        return ParsedBook(title, author, withNotes)
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

    private val NOTE_MARKER = Regex("⟦(.+)-([^-]+)⟧")
    private val NUMBER_ONLY = Regex("^[0-9IVXLCDM]{1,6}$", RegexOption.IGNORE_CASE)

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

    /**
     * Раздел FB2 в главы. Вложенные `<section>` — это подглавы: текст самого
     * раздела становится отдельной главой, а подразделы обходятся рекурсивно.
     * Числовые заголовки («1», «II») дополняются названием родителя —
     * иначе в оглавлении остаются безымянные номера.
     */
    private fun appendSection(
        section: Element,
        parentTitle: String?,
        index: Int,
        chapters: MutableList<Chapter>,
        preset: TextPreset,
    ) {
        val ownTitle = childElements(section, "title")
            .firstOrNull()
            ?.let { textOf(it).trim() }
            ?.ifBlank { null }
        val baseTitle = ownTitle ?: "Глава ${index + 1}"
        val displayTitle = if (parentTitle != null && NUMBER_ONLY.matches(baseTitle)) {
            "$parentTitle · $baseTitle"
        } else {
            baseTitle
        }
        val ownText = ownParagraphsText(section)
        if (ownText.isNotBlank()) {
            chapters += Chapter(displayTitle, TextSegmenter.segmentFor(ownText, preset))
        }
        val children = childElements(section, "section")
        children.forEachIndexed { childIndex, child ->
            appendSection(child, baseTitle, childIndex, chapters, preset)
        }
    }

    /** Абзацы самого раздела, без вложенных `<section>`. */
    private fun ownParagraphsText(section: Element): String {
        val builder = StringBuilder()
        val children = section.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val name = localName(child)
            if (name == "title" || name == "section") continue
            if (name == "p") {
                val text = textOf(child).trim()
                if (text.isNotBlank()) builder.append(text).append("\n\n")
            } else {
                collectParagraphs(child, builder)
            }
        }
        return builder.toString().trim()
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
        val name = (node.localName ?: node.nodeName).lowercase()
        // Номер сноски в тексте не читаем: <sup>1</sup>, <a type="note">…</a>.
        if (name == "sup") return ""
        if (name == "a" && (node as? Element)?.getAttribute("type")?.lowercase() == "note") {
            val href = (node as? Element)?.getAttribute("href").orEmpty()
            val id = href.substringAfterLast('#')
            if (id.isBlank()) return ""
            val display = rawText(node).trim()
                .ifBlank { id.takeLastWhile { it.isDigit() }.ifBlank { id } }
            return "⟦$id-$display⟧"
        }
        val builder = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            builder.append(textOf(children.item(i)))
        }
        // Выделения в FB2: <emphasis> — курсив, <strong> — жирный.
        val text = builder.toString()
        if (text.isBlank()) return text
        return when (name) {
            "emphasis" -> "*$text*"
            "strong" -> "**$text**"
            else -> text
        }
    }

    /** Текст сносок из <body name="notes">/«comments» по id. */
    private fun readNoteDefinitions(document: Document): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val bodies = document.getElementsByTagName("body")
        for (i in 0 until bodies.length) {
            val body = bodies.item(i) as? Element ?: continue
            val name = body.getAttribute("name").lowercase()
            if (name != "notes" && name != "comments") continue
            collectNotes(body, result)
        }
        return result
    }

    private fun collectNotes(node: Element, result: MutableMap<String, String>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val id = child.getAttribute("id")
            if (id.isNotBlank() && id !in result) {
                val text = if (localName(child) == "p") {
                    textOf(child).trim()
                } else {
                    paragraphsText(child)
                }
                if (text.isNotBlank()) result[id] = text
            }
            collectNotes(child, result)
        }
    }

    /** Все текстовые узлы без спецправил (для номера ссылки). */
    private fun rawText(node: Node): String {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            return node.nodeValue ?: ""
        }
        val builder = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) builder.append(rawText(children.item(i)))
        return builder.toString()
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':').lowercase()
}
