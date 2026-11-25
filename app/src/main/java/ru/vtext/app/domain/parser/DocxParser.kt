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
        assetsDir: File?,
    ): ParsedBook {
        ZipFile(file).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
                ?: return ParsedBook(fallbackTitle, null, emptyList())
            val document = parseXml(zip.readBytes(documentEntry))
                ?: return ParsedBook(fallbackTitle, null, emptyList())

            val title = readCoreProperty(zip, "title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val author = readCoreProperty(zip, "creator")?.takeIf { it.isNotBlank() }
            val styles = readStyles(zip)

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
                val styleId = paragraphStyle(node)
                // Оглавление книги в тексте не читаем: это сотни строк со ссылками.
                if (styleId != null && styleId in styles.skip) continue
                val text = paragraphText(node).trim()
                if (text.isEmpty()) continue
                if (styleId != null &&
                    (styleId in styles.headings || headingStyle.containsMatchIn(styleId))
                ) {
                    flush()
                    currentTitle = text
                } else {
                    if (body.isNotEmpty()) body.append("\n\n")
                    body.append(text)
                }
            }
            flush()

            val definitions = readFootnotes(zip)
            val chapters = blocks.map { (chapterTitle, chapterBody) ->
                Chapter(chapterTitle, TextSegmenter.segmentFor(chapterBody.toString(), preset))
            }.filter { it.segments.isNotEmpty() }.map { chapter ->
                val notes = chapter.segments
                    .flatMap { NOTE_MARKER.findAll(it).toList() }
                    .mapNotNull { match ->
                        val id = match.groupValues[1].removePrefix("fn")
                        val display = match.groupValues[2]
                        val text = definitions[id] ?: return@mapNotNull null
                        ru.vtext.app.domain.model.ChapterNote("fn$id-$display", text)
                    }
                    .distinctBy { it.label }
                chapter.copy(footnotes = notes)
            }

            return ParsedBook(title, author, chapters)
        }
    }

    private fun paragraphText(paragraph: Element): String {
        val builder = StringBuilder()
        fun walk(node: Node) {
            if (node is Element && localName(node) == "r") {
                val text = StringBuilder()
                collectRunText(node, text)
                val raw = text.toString()
                if (raw.isBlank()) {
                    builder.append(raw)
                } else {
                    val properties = firstChildElement(node, "rpr")
                    val bold = properties != null && isEnabled(properties, "b")
                    val italic = properties != null && isEnabled(properties, "i")
                    val leading = if (raw.first().isWhitespace()) " " else ""
                    val trailing = if (raw.last().isWhitespace()) " " else ""
                    val core = raw.trim()
                    val marked = when {
                        bold -> "**$core**"
                        italic -> "*$core*"
                        else -> core
                    }
                    builder.append(leading).append(marked).append(trailing)
                }
                return
            }
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
                    "docpr" -> {
                        // Alt-текст изображения из wp:docPr.
                        val alt = node.getAttribute("descr").trim()
                            .ifBlank { node.getAttribute("title").trim() }
                        if (alt.isNotBlank()) {
                            builder.append(' ').append(alt).append(' ')
                        }
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

    /** Текст внутри одного run (`w:r`), без вложенных абзацев. */
    private fun collectRunText(run: Element, builder: StringBuilder) {
        val children = run.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child !is Element) continue
            when (localName(child)) {
                "t" -> builder.append(child.textContent ?: "")
                "tab" -> builder.append(' ')
                "br", "cr" -> builder.append('\n')
                "nobreakhyphen" -> builder.append('-')
                "footnotereference" -> {
                    val id = child.getAttribute("w:id").ifBlank { child.getAttribute("id") }
                    if (id.isNotBlank()) builder.append("⟦fn$id-$id⟧")
                }
                "rpr" -> Unit
                else -> collectRunText(child, builder)
            }
        }
    }

    /** Свойство run включено: `<w:b/>` без `w:val` или с `true`/`1`/`on`. */
    private fun isEnabled(properties: Element, name: String): Boolean {
        val element = firstChildElement(properties, name) ?: return false
        val value = element.getAttribute("w:val").ifBlank { element.getAttribute("val") }
        return value.isBlank() || value !in setOf("0", "false", "off")
    }

    /**
     * Заголовки и служебные стили из `word/styles.xml`. Идентификатор стиля
     * (`w:styleId="1"`) ничего не говорит, имя («heading 1») и `outlineLvl`
     * лежат в таблице стилей — без неё DOCX остаётся одной главой.
     */
    private fun readStyles(zip: ZipFile): DocxStyles {
        val entry = zip.getEntry("word/styles.xml") ?: return DocxStyles(emptySet(), emptySet())
        val document = parseXml(zip.readBytes(entry)) ?: return DocxStyles(emptySet(), emptySet())
        val headings = mutableSetOf<String>()
        val skip = mutableSetOf<String>()
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node !is Element || localName(node) != "style") continue
            val id = node.getAttribute("w:styleId").ifBlank { node.getAttribute("styleId") }
            if (id.isBlank()) continue
            val name = firstChildElement(node, "name")
                ?.let { it.getAttribute("w:val").ifBlank { it.getAttribute("val") } }
                .orEmpty()
            if (name.startsWith("toc", ignoreCase = true)) {
                skip += id
                continue
            }
            val outline = firstChildElement(node, "outlinelvl")
                ?.let { it.getAttribute("w:val").ifBlank { it.getAttribute("val") } }
                ?.toIntOrNull()
            val named = headingStyle.containsMatchIn(name.lowercase())
            if (named || (outline != null && outline <= HEADING_MAX_OUTLINE)) {
                headings += id
            }
        }
        return DocxStyles(headings, skip)
    }

    private data class DocxStyles(val headings: Set<String>, val skip: Set<String>)

    private fun paragraphStyle(paragraph: Element): String? {
        val properties = firstChildElement(paragraph, "ppr") ?: return null
        return firstChildElement(properties, "pstyle")?.let { styleValue(it) }
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

    private val NOTE_MARKER = Regex("⟦(.+)-([^-]+)⟧")
    private val HEADING_MAX_OUTLINE = 8

    /** Тексты сносок из word/footnotes.xml по id. */
    private fun readFootnotes(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("word/footnotes.xml") ?: return emptyMap()
        val document = parseXml(zip.readBytes(entry)) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node !is Element || localName(node) != "footnote") continue
            val id = node.getAttribute("w:id").ifBlank { node.getAttribute("id") }
            if (id.isBlank() || id in result) continue
            val text = footnoteText(node)
            if (text.isNotBlank()) result[id] = text
        }
        return result
    }

    private fun footnoteText(root: Element): String {
        val builder = StringBuilder()
        val nodes = root.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && localName(node) == "p") {
                val text = paragraphText(node).trim()
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append(' ')
                    builder.append(text)
                }
            }
        }
        return builder.toString()
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
            // isXIncludeAware на Android бросает UnsupportedOperationException
            // и утянул бы за собой весь разбор (книга открывалась пустой).
            runCatching { isXIncludeAware = false }
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
    } catch (error: Exception) {
        android.util.Log.e("VtextParse", "docx xml parse failed", error)
        null
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfter(':').lowercase()
}
