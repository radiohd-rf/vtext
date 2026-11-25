package ru.vtext.app.domain.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import ru.vtext.app.domain.PoetryDetector
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.cleanup.CleanupOptions
import ru.vtext.app.domain.cleanup.ImportTextCleaner
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ChapterNote
import ru.vtext.app.domain.model.ChapterImage
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/**
 * Разбор PDF через PDFBox: текст извлекается постранично.
 * Если в PDF есть закладки, главы и подглавы берутся из оглавления;
 * иначе пробуем найти заголовки по размеру/жирности шрифта.
 */
class PdfParser : BookParser {

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
        assetsDir: File?,
    ): ParsedBook {
        return try {
            PDDocument.load(file).use { document ->
                val info = document.documentInformation
                val title = cleanMetadata(info?.title, TITLE_PLACEHOLDERS) ?: fallbackTitle
                val author = cleanMetadata(info?.author, AUTHOR_PLACEHOLDERS)

                // Основной путь: KitePDF даёт готовые абзацы и кегли,
                // по ним возвращаем буквицы на место. PDFBox — запасной.
                val kite = runCatching { kitePages(file, preset) }.getOrNull()
                val pageTexts = kite?.map { it.text }
                    ?: run {
                        val pageCount = document.numberOfPages
                        val tracker = PdfStyleTracker()
                        (1..pageCount).map { page ->
                            val stripper = PdfTextExtractor(tracker).apply {
                                startPage = page
                                endPage = page
                            }
                            runCatching { stripper.getText(document) }.getOrDefault("")
                        }
                    }
                // Убираем номера страниц и повторяющиеся колонтитулы до разбиения на фрагменты.
                val cleanedPages = ImportTextCleaner.cleanPages(
                    pageTexts,
                    CleanupOptions(joinHyphenation = preset != TextPreset.POETRY),
                ).pages
                // Стихи в PDF не размечены: определяем по длине строк (Онегин — 24 символа,
                // проза — 80–100). Пользовательский выбор BOOK/POETRY важнее.
                val effectivePreset = if (
                    preset == TextPreset.AUTO &&
                    PoetryDetector.isPoetry(cleanedPages.joinToString("\n\n"))
                ) {
                    TextPreset.POETRY
                } else {
                    preset
                }
                val outline = readOutline(document)
                val fromOutline = if (outline.size >= MIN_OUTLINE_ENTRIES) {
                    chaptersFromOutline(cleanedPages, outline, effectivePreset)
                } else {
                    emptyList()
                }
                val chapters = when {
                    fromOutline.size >= 2 -> fromOutline
                    effectivePreset == TextPreset.POETRY -> {
                        val fromHeadings = chaptersFromHeadings(document, cleanedPages, effectivePreset)
                        if (fromHeadings.size >= 2) {
                            fromHeadings
                        } else {
                            listOf(
                                Chapter(
                                    "Стихи",
                                    TextSegmenter.segmentFor(
                                        joinPages(cleanedPages, effectivePreset),
                                        effectivePreset,
                                    ),
                                    page = 0,
                                ),
                            )
                        }
                    }
                    else -> chaptersFromHeadings(document, cleanedPages, effectivePreset).ifEmpty {
                        chaptersFromPages(cleanedPages, effectivePreset)
                    }
                }
                val withFootnotes = if (kite == null) {
                    chapters
                } else {
                    attachFootnotes(chapters, kite)
                }
                val withImages = if (assetsDir == null) {
                    withFootnotes
                } else {
                    attachPageImages(withFootnotes, extractImages(document, assetsDir))
                }
                ParsedBook(title, author, withImages)
            }
        } catch (_: Exception) {
            // Повреждённый, зашифрованный или недоступный PDF — вернём пустую книгу,
            // репозиторий подставит заглушку.
            ParsedBook(fallbackTitle, null, emptyList())
        }
    }

    internal data class KitePage(val text: String, val footnotes: List<ChapterNote>)

    /**
     * Текст страниц через KitePDF: `structuredText.blocks` — это абзацы,
     * строки внутри блока склеиваем. Буквица — одиночная заглавная буква
     * с кеглем заметно больше основного: переносим её в начало абзаца.
     * Сноски внизу страницы (мелкий текст) вынимаем из тела и привязываем
     * к маркерам на этой же странице («p12-3»).
     */
    internal fun kitePages(file: File, preset: TextPreset): List<KitePage> {
        val document = io.github.yuroyami.kitepdf.PdfDocument.open(file.readBytes())
        val joiner = if (preset == TextPreset.POETRY) "\n" else " "
        return document.pages.mapIndexed { pageIndex, page ->
            val blocks = page.structuredText.blocks
            val weights = HashMap<Int, Int>()
            for (block in blocks) {
                for (line in block.lines) {
                    for (span in line.spans) {
                        val size = span.fontSize.toInt()
                        weights[size] = (weights[size] ?: 0) + span.text.length
                    }
                }
            }
            val body = (weights.maxByOrNull { it.value }?.key ?: 10).toFloat()
            val bodyMinY = blocks
                .flatMap { it.lines }
                .flatMap { it.spans }
                .filter { it.fontSize >= body * 0.85f }
                .minOfOrNull { it.origin?.second ?: 0.0 } ?: 0.0

            val footnotes = mutableListOf<ChapterNote>()
            val footnoteLabels = mutableSetOf<String>()
            // Сноски — строки мелким кеглем ниже последней строки тела. Смотрим
            // построчно: KitePDF нередко отдаёт всю страницу одним блоком, и
            // тогда «мелкий блок» не выделяется.
            val footnoteLines = mutableListOf<String>()
            val keptLines = blocks.map { block ->
                block.lines.filter { line ->
                    val spans = line.spans
                    if (spans.isEmpty()) return@filter true
                    val maxSize = spans.maxOfOrNull { it.fontSize.toFloat() } ?: body
                    val y = spans.firstOrNull()?.origin?.second ?: 0.0
                    val isFootnote = maxSize < body * 0.85f && y < bodyMinY
                    if (isFootnote) {
                        footnoteLines += spans.joinToString("") { it.text }.trim()
                    }
                    !isFootnote
                }
            }
            parseFootnoteLines(footnoteLines, pageIndex).forEach { note ->
                footnotes += note
                footnoteLabels += note.label.substringAfter('-')
            }

            val pageText = StringBuilder()
            blocks.forEachIndexed { index, block ->
                val lines = keptLines[index]
                if (lines.isEmpty()) return@forEachIndexed
                val dropCaps = StringBuilder()
                val lineText = mutableListOf<StringBuilder>()
                val lineBaseline = mutableListOf<Double>()
                val lineMarks = mutableListOf<List<Pair<Double, Int>>>()
                val markers = mutableListOf<Triple<String, Double, Double>>()
                for (line in lines) {
                    val rawLine = line.spans.joinToString("") { it.text }.trim()
                    val maxSize = line.spans.maxOfOrNull { it.fontSize.toFloat() } ?: body
                    val baseline = line.spans.firstOrNull()?.origin?.second ?: 0.0
                    if (maxSize <= body * 0.85f && NOTE_LABEL.matches(rawLine)) {
                        val x = line.spans.firstOrNull()?.bounds?.left ?: 0.0
                        val label = if (rawLine in footnoteLabels) {
                            "p${pageIndex + 1}-$rawLine"
                        } else {
                            rawLine
                        }
                        markers += Triple(label, x, baseline)
                        continue
                    }
                    val text = StringBuilder()
                    val marks = mutableListOf<Pair<Double, Int>>()
                    for (span in line.spans) {
                        val value = span.text
                        val trimmed = value.trim()
                        if (trimmed.length == 1 &&
                            span.fontSize >= body * 1.5f &&
                            trimmed.first().isUpperCase()
                        ) {
                            dropCaps.append(trimmed)
                            continue
                        }
                        text.append(value)
                        marks += (span.bounds?.right ?: 0.0) to text.length
                    }
                    if (text.isNotBlank()) {
                        lineText += text
                        lineBaseline += baseline
                        lineMarks += marks
                    }
                }
                if (lineText.isEmpty()) return@forEachIndexed
                val insertions = mutableMapOf<Int, MutableList<Pair<Int, String>>>()
                for (marker in markers) {
                    val label = marker.first
                    val markerX = marker.second
                    val markerY = marker.third
                    var target = -1
                    var bestY = Double.NEGATIVE_INFINITY
                    lineBaseline.forEachIndexed { lineIndex, y ->
                        if (y <= markerY + 0.1 && y > bestY) {
                            bestY = y
                            target = lineIndex
                        }
                    }
                    if (target < 0) {
                        target = lineBaseline.indices.minByOrNull {
                            kotlin.math.abs(lineBaseline[it] - markerY)
                        } ?: -1
                    }
                    if (target < 0) continue
                    val insertion = lineMarks[target]
                        .lastOrNull { it.first <= markerX + 1.0 }
                        ?.second ?: 0
                    insertions.getOrPut(target) { mutableListOf() } += insertion to label
                }
                insertions.forEach { (lineIndex, items) ->
                    val raw = lineText[lineIndex]
                    items.sortedByDescending { it.first }.forEach { (position, label) ->
                        raw.insert(position.coerceIn(0, raw.length), "⟦" + label + "⟧")
                    }
                }
                val builder = StringBuilder(dropCaps)
                lineText.forEachIndexed { lineIndex, line ->
                    if (lineIndex > 0) builder.append(joiner)
                    builder.append(line.toString().trim())
                }
                val joined = if (preset == TextPreset.POETRY) {
                    builder.toString()
                } else {
                    val withNote = HYPHEN_WITH_NOTE.replace(builder.toString()) { match ->
                        match.groupValues[1] + match.groupValues[3] + "⟦" +
                            match.groupValues[2] + "⟧"
                    }
                    HYPHEN_BREAK.replace(withNote) { match ->
                        match.groupValues[1] + match.groupValues[2]
                    }
                }
                pageText.append(joined).append("\n\n")
            }
            KitePage(pageText.toString().trim(), footnotes)
        }
    }

    /** Строки сносок страницы → примечания «p<страница>-<номер>». */
    internal fun parseFootnoteLines(lines: List<String>, pageIndex: Int): List<ChapterNote> {
        val result = mutableListOf<ChapterNote>()
        var label: String? = null
        val text = StringBuilder()

        fun flush() {
            val current = label
            if (current != null) {
                val body = text.toString().trim()
                if (body.isNotBlank()) {
                    result += ChapterNote("p${pageIndex + 1}-$current", body)
                }
            }
            label = null
            text.setLength(0)
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val match = FOOTNOTE_LINE.find(trimmed)
            when {
                NOTE_LABEL.matches(trimmed) -> {
                    flush()
                    label = trimmed
                }
                match != null -> {
                    flush()
                    label = match.groupValues[1]
                    text.append(match.groupValues[2])
                }
                label != null -> {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(trimmed)
                }
            }
        }
        flush()
        return result
    }

    /** Сноски страниц — в главу, где стоит маркер `⟦p12-3⟧`. */
    private fun attachFootnotes(chapters: List<Chapter>, pages: List<KitePage>): List<Chapter> {
        if (chapters.isEmpty()) return chapters
        val all = pages.flatMap { it.footnotes }.distinctBy { it.label }
        if (all.isEmpty()) return chapters
        val used = mutableSetOf<String>()
        val result = chapters.map { chapter ->
            val text = chapter.segments.joinToString(" ")
            val notes = all.filter { note -> text.contains("⟦" + note.label + "⟧") }
            if (notes.isEmpty()) {
                chapter
            } else {
                used += notes.map { it.label }
                chapter.copy(footnotes = chapter.footnotes + notes)
            }
        }
        val leftover = all.filter { it.label !in used }
        if (leftover.isEmpty()) return result
        // Маркер не распознан (или потерян при склейке строк) — не теряем текст:
        // такие сноски кладём в последнюю главу.
        val last = result.lastIndex
        return result.mapIndexed { index, chapter ->
            if (index != last) chapter else chapter.copy(footnotes = chapter.footnotes + leftover)
        }
    }

    private data class OutlineEntry(val title: String, val page: Int, val depth: Int)

    private fun readOutline(document: PDDocument): List<OutlineEntry> {
        val root = document.documentCatalog?.documentOutline ?: return emptyList()
        val pages = document.pages
        val result = mutableListOf<OutlineEntry>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                val page: PDPage? = runCatching { item.findDestinationPage(document) }.getOrNull()
                val pageIndex = page?.let { pages.indexOf(it) } ?: -1
                val title = item.title
                    ?.replace(WHITESPACE, " ")
                    ?.trim()
                    .orEmpty()
                if (pageIndex in 0 until pages.count && title.isNotBlank()) {
                    result += OutlineEntry(title, pageIndex, depth.coerceAtMost(MAX_DEPTH))
                }
                if (item.hasChildren()) {
                    walk(item, depth + 1)
                }
            }
        }
        walk(root, 0)
        return result
    }

    private fun chaptersFromOutline(
        pages: List<String>,
        outline: List<OutlineEntry>,
        preset: TextPreset,
    ): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var index = 0
        while (index < outline.size) {
            val startPage = outline[index].page
            val samePage = mutableListOf(outline[index])
            var next = index + 1
            // Несколько закладок на одной странице (например, «Об авторе» и
            // «От издательства») объединяем, иначе текст страницы дублируется.
            while (next < outline.size && outline[next].page == startPage) {
                samePage += outline[next]
                next += 1
            }
            val title = samePage.map { it.title }.distinct().joinToString(" · ")
            val depth = samePage.minOf { it.depth }
            val endPage = outline.drop(next)
                .firstOrNull { it.page > startPage }
                ?.page
                ?: pages.size
            val safeEnd = endPage.coerceAtMost(pages.size)
            if (startPage < safeEnd) {
                val text = joinPages(pages.subList(startPage, safeEnd), preset)
                val segments = TextSegmenter.segmentFor(text, preset)
                if (segments.isNotEmpty()) {
                    chapters += Chapter(title, segments, depth, startPage)
                }
            }
            index = next
        }
        return chapters
    }

    private fun chaptersFromPages(pages: List<String>, preset: TextPreset): List<Chapter> =
        pages.mapIndexedNotNull { index, text ->
            val segments = TextSegmenter.segmentFor(text, preset)
            if (segments.isEmpty()) null else Chapter("Страница ${index + 1}", segments, page = index)
        }

    private fun chaptersFromHeadings(
        document: PDDocument,
        pages: List<String>,
        preset: TextPreset,
    ): List<Chapter> {
        val extractor = PdfLayoutExtractor()
        runCatching { extractor.getText(document) }
        val headings = PdfHeadingDetector.detect(extractor.lines, pages.size)
        if (headings.size < 2) return emptyList()

        val chapters = mutableListOf<Chapter>()
        var index = 0
        while (index < headings.size) {
            val startPage = headings[index].page
            val samePage = mutableListOf(headings[index])
            var next = index + 1
            while (next < headings.size && headings[next].page == startPage) {
                samePage += headings[next]
                next += 1
            }
            val title = samePage.map { it.text }.distinct().joinToString(" · ")
            val depth = samePage.minOf { it.depth }
            val endPage = headings.drop(next)
                .firstOrNull { it.page > startPage }
                ?.page
                ?: pages.size
            val safeEnd = endPage.coerceAtMost(pages.size)
            if (startPage < safeEnd) {
                val segments = TextSegmenter.segmentFor(
                    joinPages(pages.subList(startPage, safeEnd), preset),
                    preset,
                )
                if (segments.isNotEmpty()) {
                    chapters += Chapter(title, segments, depth, startPage)
                }
            }
            index = next
        }
        return chapters
    }

    /**
     * Склеивает страницы главы. В PDF слово часто переносится через край страницы
     * («сек-» / «ретарем»), поэтому такой перенос соединяем. В стихах не трогаем:
     * там тире в конце строки может быть авторским.
     */
    private fun joinPages(pages: List<String>, preset: TextPreset): String {
        if (pages.size <= 1) return pages.joinToString("\n\n")
        val text = StringBuilder()
        pages.forEachIndexed { index, page ->
            if (index > 0) {
                // Абзац, разрезанный краем страницы, склеиваем: иначе каждая
                // страница начинала бы новый фрагмент посреди предложения.
                val continues = continuesOnNextPage(pages[index - 1], page, preset)
                text.append(if (continues) "\n" else "\n\n")
            }
            text.append(page)
        }
        if (preset == TextPreset.POETRY) return text.toString()
        return CROSS_PAGE_HYPHEN.replace(text.toString()) { match ->
            match.groupValues[1] + match.groupValues[2]
        }
    }

    private fun continuesOnNextPage(previous: String, next: String, preset: TextPreset): Boolean {
        if (preset == TextPreset.POETRY) return false
        val lastLine = previous.lines().lastOrNull { it.isNotBlank() }?.trimEnd() ?: return false
        val firstLine = next.lines().firstOrNull { it.isNotBlank() }?.trimStart() ?: return false
        val lastChar = lastLine.lastOrNull() ?: return false
        val firstChar = firstLine.firstOrNull() ?: return false
        val openEnded = lastChar.isLetterOrDigit() || lastChar in ",;:" || lastChar == '—' || lastChar == '–'
        return openEnded && firstChar.isLowerCase()
    }

    /** PDF-генераторы часто оставляют заглушки вроде «untitled»/«anonymous». */
    private fun cleanMetadata(value: String?, placeholders: Set<String>): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.takeIf { it.lowercase() !in placeholders }
    }

    /**
     * Картинки, которые страница реально рисует. Смотреть только в ресурсы
     * нельзя: многие PDF держат общий словарь XObject на всех страницах,
     * и тогда одна и та же иллюстрация сохранялась бы сотни раз (книга
     * А6 отдавала 1 ГБ картинок на 49 исходных). Поэтому идём по потоку
     * содержимого (оператор `Do`) и повторно используем уже сохранённый файл.
     */
    private fun extractImages(document: PDDocument, dir: File): Map<Int, List<String>> {
        val result = mutableMapOf<Int, MutableList<String>>()
        dir.mkdirs()
        val saved = HashMap<COSBase, String>()
        var total = 0
        for (pageIndex in 0 until document.numberOfPages) {
            if (total >= MAX_IMAGES_PER_BOOK) break
            val page = document.getPage(pageIndex)
            val resources = page.resources ?: continue
            val drawn = mutableListOf<PDImageXObject>()
            collectDrawnImages(page, resources, mutableSetOf(), drawn, 0)
            if (drawn.isEmpty()) continue
            var count = 0
            for (xObject in drawn) {
                if (count >= MAX_IMAGES_PER_PAGE || total >= MAX_IMAGES_PER_BOOK) break
                val key = xObject.cosObject
                val existing = saved[key]
                if (existing != null) {
                    result.getOrPut(pageIndex) { mutableListOf() } += existing
                    count += 1
                    continue
                }
                val image = runCatching { xObject.image }.getOrNull() ?: continue
                if (image.width < MIN_IMAGE_PX || image.height < MIN_IMAGE_PX) continue
                val file = saveImage(image, pageIndex, count, dir) ?: continue
                saved[key] = file
                result.getOrPut(pageIndex) { mutableListOf() } += file
                count += 1
                total += 1
            }
        }
        return result
    }

    /** Изображения из потока содержимого страницы (и вложенных форм). */
    private fun collectDrawnImages(
        content: com.tom_roush.pdfbox.contentstream.PDContentStream,
        resources: PDResources,
        seen: MutableSet<COSBase>,
        out: MutableList<PDImageXObject>,
        depth: Int,
    ) {
        if (depth > MAX_FORM_DEPTH) return
        val tokens = runCatching {
            PDFStreamParser(content).also { it.parse() }.tokens
        }.getOrNull() ?: return
        var lastName: COSName? = null
        for (token in tokens) {
            when (token) {
                is COSName -> lastName = token
                is Operator -> {
                    if (token.name == "Do") {
                        lastName?.let { name ->
                            val xObject = runCatching { resources.getXObject(name) }.getOrNull()
                            when (xObject) {
                                is PDImageXObject ->
                                    if (seen.add(xObject.cosObject)) out += xObject
                                is PDFormXObject -> {
                                    val formResources = xObject.resources
                                    if (formResources != null) {
                                        collectDrawnImages(
                                            xObject,
                                            formResources,
                                            seen,
                                            out,
                                            depth + 1,
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    lastName = null
                }
                else -> lastName = null
            }
        }
    }

    private fun saveImage(
        image: Bitmap,
        page: Int,
        index: Int,
        dir: File,
    ): String? = runCatching {
        val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(image.width, image.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                image,
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            image
        }
        // JPEG без прозрачности: подложка белая, иначе схемы уходят в чёрный.
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== image) scaled.recycle()
        val name = "pdf-${page + 1}-$index.jpg"
        File(dir, name).outputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        opaque.recycle()
        name
    }.getOrNull()

    /** Каждая картинка — в конец той главы, на странице которой она стоит. */
    private fun attachPageImages(
        chapters: List<Chapter>,
        perPage: Map<Int, List<String>>,
    ): List<Chapter> {
        if (chapters.isEmpty() || perPage.isEmpty()) return chapters
        val byChapter = mutableMapOf<Int, MutableList<ChapterImage>>()
        perPage.toSortedMap().forEach { (page, files) ->
            var target = 0
            chapters.forEachIndexed { index, chapter ->
                if (chapter.page.coerceAtLeast(0) <= page) target = index
            }
            val last = chapters[target].segments.lastIndex.coerceAtLeast(0)
            files.forEach { file ->
                byChapter.getOrPut(target) { mutableListOf() } +=
                    ChapterImage(last, file, "Иллюстрация")
            }
        }
        return chapters.mapIndexed { index, chapter ->
            val images = byChapter[index] ?: return@mapIndexed chapter
            chapter.copy(images = chapter.images + images)
        }
    }

    private companion object {
        const val MIN_IMAGE_PX = 80
        const val MAX_IMAGE_SIDE = 1600
        const val MAX_IMAGES_PER_PAGE = 8
        const val MAX_IMAGES_PER_BOOK = 600
        const val MAX_FORM_DEPTH = 4
        val TITLE_PLACEHOLDERS = setOf("untitled", "без названия", "unknown")
        val AUTHOR_PLACEHOLDERS = setOf("anonymous", "unknown", "user", "admin")
        val WHITESPACE = Regex("\\s+")
        val HYPHEN_BREAK = Regex("([\\p{L}])-\\s+([\\p{Ll}])")
        val HYPHEN_WITH_NOTE =
            Regex("([\\p{L}])-\\s*⟦([\\d*]{1,3})⟧\\s+([\\p{Ll}])")
        val NOTE_LABEL = Regex("^[\\d*]{1,3}$")
        val FOOTNOTE_LINE = Regex("^([*\\d]{1,3})[.)]?\\s+(.*)$")
        val CROSS_PAGE_HYPHEN = Regex("([\\p{L}])-[ \\t]*\\n\\s*\\n[ \\t]*([\\p{L}])")
        const val MIN_OUTLINE_ENTRIES = 3
        const val MAX_DEPTH = 2
    }
}
