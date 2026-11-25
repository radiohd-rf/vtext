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
import ru.vtext.app.domain.model.ChapterImage
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/**
 * Разбор EPUB: container.xml → OPF (метаданные + spine) → XHTML-главы.
 *
 * Для книг, собранных [ru.vtext.app.domain.epub.EpubCompiler] (метка `vtext`
 * в OPF), абзацы берутся как готовые фрагменты без повторной нарезки, а позиции
 * картинок сохраняются. Остальные EPUB разбираются как раньше, картинки
 * добавляются в конец главы.
 */
class EpubParser : BookParser {

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
        assetsDir: File?,
    ): ParsedBook {
        ZipFile(file).use { zip ->
            val rootPath = readRootFilePath(zip) ?: return ParsedBook(fallbackTitle, null, emptyList())
            val opfEntry = findEntry(zip, rootPath) ?: return ParsedBook(fallbackTitle, null, emptyList())
            val opfText = readText(zip, opfEntry)
            val opf = parseXml(opfText) ?: return ParsedBook(fallbackTitle, null, emptyList())

            val title = firstText(opf, "title") ?: fallbackTitle
            val author = firstText(opf, "creator")
            val baseDir = rootPath.substringBeforeLast('/', "")
            val compiled = opfText.contains("name=\"vtext\"")

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

            val spinePaths = spineIds.mapNotNull { manifest[it] }.filter { isHtml(it) }
            // Ссылки на примечания — по главам; определения ищем во всех файлах.
            val referencesByChapter = mutableMapOf<Int, List<Pair<String, String>>>()
            if (!compiled) {
                spinePaths.forEachIndexed { index, path ->
                    val entry = findEntry(zip, path) ?: return@forEachIndexed
                    referencesByChapter[index] = extractReferences(readText(zip, entry))
                }
            }
            val wanted = referencesByChapter.values.flatten().map { it.first }.toSet()
            val definitions = mutableMapOf<String, String>()
            if (wanted.isNotEmpty()) {
                spinePaths.forEach { path ->
                    val entry = findEntry(zip, path) ?: return@forEach
                    extractDefinitions(readText(zip, entry), wanted).forEach { (id, text) ->
                        definitions.putIfAbsent(id, text)
                    }
                }
            }

            val chapters = mutableListOf<Chapter>()
            var number = 0
            spinePaths.forEachIndexed { index, path ->
                val entry = findEntry(zip, path) ?: return@forEachIndexed
                val raw = readText(zip, entry)
                val refs = referencesByChapter[index].orEmpty()
                val html = if (compiled) raw else removeNoteAsides(replaceReferences(raw))
                val chapter = if (compiled) {
                    compiledChapter(zip, path, html, assetsDir)
                } else {
                    foreignChapter(zip, path, html, preset, assetsDir)
                } ?: return@forEachIndexed
                val notes = refs.mapNotNull { (id, display) ->
                    val text = definitions[id] ?: return@mapNotNull null
                    ru.vtext.app.domain.model.ChapterNote("$id-$display", text)
                }
                number += 1
                chapters += chapter.copy(
                    title = chapter.title.ifBlank { "Глава $number" },
                    footnotes = notes,
                )
            }

            return ParsedBook(title, author, chapters)
        }
    }

    /** Книга, собранная нами: один `<p class="seg">` — один фрагмент. */
    private fun compiledChapter(
        zip: ZipFile,
        path: String,
        html: String,
        assetsDir: File?,
    ): Chapter? {
        val document = parseXml(html) ?: return null
        val body = document.getElementsByTagName("body").item(0) as? Element ?: return null
        var title = ""
        val segments = mutableListOf<String>()
        val images = mutableListOf<ChapterImage>()
        val children = body.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            when (localName(node)) {
                "h1", "h2", "h3" -> {
                    val value = blockText(node).trim()
                    if (title.isBlank() && value.isNotBlank()) title = value
                }
                "p" -> {
                    val value = blockText(node).trim()
                    if (value.isNotBlank()) segments += value
                }
                "figure" -> {
                    val img = firstDescendant(node, "img") ?: continue
                    addImage(zip, path, img, segments.size - 1, assetsDir, images)
                }
                "img" -> addImage(zip, path, node, segments.size - 1, assetsDir, images)
            }
        }
        if (segments.isEmpty()) return null
        return Chapter(title, segments, images = images)
    }

    /** Сторонний EPUB: текст режется как раньше, картинки — в конец главы. */
    private fun foreignChapter(
        zip: ZipFile,
        path: String,
        html: String,
        preset: TextPreset,
        assetsDir: File?,
    ): Chapter? {
        var text = HtmlText.extract(html)
        if (text.isBlank()) return null
        val chapterTitle = HtmlText.htmlTitle(html)
        // Заголовок главы не дублируем в озвучиваемом тексте.
        if (chapterTitle != null && text.startsWith(chapterTitle)) {
            text = text.removePrefix(chapterTitle).trimStart('\n', ' ')
        }
        if (text.isBlank()) return null
        val segments = TextSegmenter.segmentFor(text, preset)
        if (segments.isEmpty()) return null
        val images = mutableListOf<ChapterImage>()
        val document = parseXml(html)
        if (document != null) {
            forEachElement(document, "img") { img ->
                addImage(zip, path, img, segments.lastIndex, assetsDir, images)
            }
        }
        return Chapter(chapterTitle.orEmpty(), segments, images = images)
    }

    private fun addImage(
        zip: ZipFile,
        chapterPath: String,
        img: Element,
        afterSegment: Int,
        assetsDir: File?,
        out: MutableList<ChapterImage>,
    ) {
        val dir = assetsDir ?: return
        val src = img.getAttribute("src").trim()
        if (src.isBlank() || src.startsWith("data:")) return
        val resolved = resolve(chapterPath.substringBeforeLast('/', ""), src)
        val entry = findEntry(zip, resolved) ?: return
        val original = resolved.substringAfterLast('/')
        if (original.isBlank()) return
        // Собранный нами EPUB ссылается на файлы, которые уже лежат в assets —
        // не плодим копии с хешем в имени.
        val safeName = if (File(dir, original).isFile) {
            original
        } else {
            "img-" + Integer.toHexString(resolved.hashCode()) + "-" +
                original.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
        val target = File(dir, safeName)
        if (!target.isFile) {
            runCatching {
                dir.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { return }
        }
        if (out.none { it.file == safeName }) {
            out += ChapterImage(afterSegment, safeName, img.getAttribute("alt").orEmpty())
        }
    }

    /** Ссылки-примечания: `<a epub:type="noteref" href="#n1">1</a>` и подобные. */
    private fun extractReferences(html: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        ANCHOR.findAll(html).forEach { match ->
            val anchor = match.value
            val href = HREF_FRAGMENT.find(anchor)?.groupValues?.get(1) ?: return@forEach
            if (!isNoteref(anchor, href)) return@forEach
            val id = href.substringAfterLast('#')
            if (id.isBlank()) return@forEach
            val text = TAG.replace(
                anchor.substringAfter('>').substringBeforeLast("</a>"),
                "",
            ).trim()
            val display = text.ifBlank {
                id.takeLastWhile { it.isDigit() }.ifBlank { id }
            }
            result += id to display
        }
        return result.distinct()
    }

    private fun isNoteref(anchor: String, href: String): Boolean {
        val type = EPUB_TYPE.find(anchor)?.groupValues?.get(1).orEmpty()
        if (type.contains("noteref", ignoreCase = true)) return true
        val text = TAG.replace(
            anchor.substringAfter('>').substringBeforeLast("</a>"),
            "",
        ).trim()
        return text.isNotEmpty() && text.length <= 3 &&
            text.all { it.isDigit() || it == '*' }
    }

    /** Заменяет ссылки-примечания на маркеры ⟦id-N⟧. */
    private fun replaceReferences(html: String): String = ANCHOR.replace(html) { match ->
        val anchor = match.value
        val href = HREF_FRAGMENT.find(anchor)?.groupValues?.get(1)
        if (href == null || !isNoteref(anchor, href)) {
            return@replace anchor
        }
        val id = href.substringAfterLast('#')
        if (id.isBlank()) return@replace anchor
        val text = TAG.replace(
            anchor.substringAfter('>').substringBeforeLast("</a>"),
            "",
        ).trim()
        val display = text.ifBlank { id.takeLastWhile { it.isDigit() }.ifBlank { id } }
        "⟦$id-$display⟧"
    }

    /** Убирает встроенные сноски-`<aside>`, чтобы не читать их в тексте. */
    private fun removeNoteAsides(html: String): String = ASIDE_NOTE.replace(html, "")

    /** Тексты сносок по id: `<aside id="n1">…</aside>`, `<li id="n1">…</li>`. */
    private fun extractDefinitions(html: String, ids: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (id in ids) {
            val escaped = Regex.escape(id)
            val regex = Regex(
                "<([a-zA-Z][\\w:-]*)[^>]*\\bid\\s*=\\s*\"$escaped\"[^>]*>(.*?)</\\1>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
            val match = regex.find(html) ?: continue
            val inner = ANCHOR.replace(match.groupValues[2]) { anchor ->
                val href = HREF_FRAGMENT.find(anchor.value)?.groupValues?.get(1)
                if (href != null && href.startsWith("#")) "" else anchor.value
            }
            val text = TAG.replace(inner, " ").replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank() && text.length < 4000) result[id] = text
        }
        return result
    }

    private fun isHtml(path: String): Boolean =
        path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true)

    private fun blockText(node: Node): String {
        val builder = StringBuilder()
        fun walk(current: Node) {
            when {
                current.nodeType == Node.TEXT_NODE || current.nodeType == Node.CDATA_SECTION_NODE -> {
                    builder.append(current.nodeValue ?: "")
                }
                current is Element && localName(current) == "br" -> builder.append('\n')
                current is Element && localName(current) == "img" -> Unit
                else -> {
                    val children = current.childNodes
                    for (i in 0 until children.length) walk(children.item(i))
                }
            }
        }
        walk(node)
        return builder.toString().replace('\u00a0', ' ')
    }

    private fun firstDescendant(node: Node, name: String): Element? {
        if (node is Element && localName(node) == name) return node
        val children = node.childNodes
        for (i in 0 until children.length) {
            firstDescendant(children.item(i), name)?.let { return it }
        }
        return null
    }

    private val ANCHOR = Regex(
        "<a\\b[^>]*>.*?</a>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val HREF_FRAGMENT = Regex(
        "href\\s*=\\s*\"([^\"]*#[^\"]+)\"",
        RegexOption.IGNORE_CASE,
    )
    private val EPUB_TYPE = Regex(
        "epub:type\\s*=\\s*\"([^\"]*)\"",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("<[^>]+>")
    private val ASIDE_NOTE = Regex(
        "<aside\\b[^>]*epub:type\\s*=\\s*\"[^\"]*(?:footnote|endnote|rearnote)[^\"]*\"[^>]*>.*?</aside>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

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
