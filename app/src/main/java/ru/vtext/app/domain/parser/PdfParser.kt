package ru.vtext.app.domain.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import ru.vtext.app.domain.PoetryDetector
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.cleanup.CleanupOptions
import ru.vtext.app.domain.cleanup.ImportTextCleaner
import ru.vtext.app.domain.model.Chapter
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
                val pageTexts = runCatching { kitePageTexts(file, preset) }
                    .getOrElse {
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
                val withImages = if (assetsDir == null) {
                    chapters
                } else {
                    attachPageImages(chapters, extractImages(document, assetsDir))
                }
                ParsedBook(title, author, withImages)
            }
        } catch (_: Exception) {
            // Повреждённый, зашифрованный или недоступный PDF — вернём пустую книгу,
            // репозиторий подставит заглушку.
            ParsedBook(fallbackTitle, null, emptyList())
        }
    }

    /**
     * Текст страницы через KitePDF: `structuredText.blocks` — это абзацы,
     * строки внутри блока склеиваем. Буквица — одиночная заглавная буква
     * с кеглем заметно больше основного: переносим её в начало абзаца.
     * В стихах строки не склеиваем.
     */
    private fun kitePageTexts(file: File, preset: TextPreset): List<String> {
        val document = io.github.yuroyami.kitepdf.PdfDocument.open(file.readBytes())
        val joiner = if (preset == TextPreset.POETRY) "\n" else " "
        return document.pages.map { page ->
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
            val pageText = StringBuilder()
            for (block in blocks) {
                val dropCaps = StringBuilder()
                val lines = mutableListOf<String>()
                for (line in block.lines) {
                    val text = StringBuilder()
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
                    }
                    val lineText = text.toString().trim()
                    if (lineText.isNotEmpty()) lines.add(lineText)
                }
                if (lines.isEmpty()) continue
                val builder = StringBuilder(dropCaps)
                lines.forEachIndexed { index, line ->
                    if (index > 0) builder.append(joiner)
                    builder.append(line)
                }
                val joined = if (preset == TextPreset.POETRY) {
                    builder.toString()
                } else {
                    HYPHEN_BREAK.replace(builder.toString()) { match ->
                        match.groupValues[1] + match.groupValues[2]
                    }
                }
                pageText.append(joined).append("\n\n")
            }
            pageText.toString().trim()
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

    /** Встроенные картинки страниц: страница (с нуля) → имена файлов в assetsDir. */
    private fun extractImages(document: PDDocument, dir: File): Map<Int, List<String>> {
        val result = mutableMapOf<Int, MutableList<String>>()
        dir.mkdirs()
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            val resources = page.resources ?: continue
            var count = 0
            for (name in resources.xObjectNames) {
                if (count >= MAX_IMAGES_PER_PAGE) break
                val xObject = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                if (xObject !is PDImageXObject) continue
                val image = runCatching { xObject.image }.getOrNull() ?: continue
                if (image.width < MIN_IMAGE_PX || image.height < MIN_IMAGE_PX) continue
                val file = saveImage(image, pageIndex, count, dir) ?: continue
                result.getOrPut(pageIndex) { mutableListOf() } += file
                count += 1
            }
        }
        return result
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
        val TITLE_PLACEHOLDERS = setOf("untitled", "без названия", "unknown")
        val AUTHOR_PLACEHOLDERS = setOf("anonymous", "unknown", "user", "admin")
        val WHITESPACE = Regex("\\s+")
        val HYPHEN_BREAK = Regex("([\\p{L}])-\\s+([\\p{Ll}])")
        val CROSS_PAGE_HYPHEN = Regex("([\\p{L}])-[ \\t]*\\n\\s*\\n[ \\t]*([\\p{L}])")
        const val MIN_OUTLINE_ENTRIES = 3
        const val MAX_DEPTH = 2
    }
}
