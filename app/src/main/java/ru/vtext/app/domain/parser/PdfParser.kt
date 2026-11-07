package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.cleanup.CleanupOptions
import ru.vtext.app.domain.cleanup.ImportTextCleaner
import ru.vtext.app.domain.model.Chapter
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
    ): ParsedBook {
        return try {
            PDDocument.load(file).use { document ->
                val info = document.documentInformation
                val title = cleanMetadata(info?.title, TITLE_PLACEHOLDERS) ?: fallbackTitle
                val author = cleanMetadata(info?.author, AUTHOR_PLACEHOLDERS)

                val pageCount = document.numberOfPages
                val tracker = PdfStyleTracker()
                val pageTexts = (1..pageCount).map { page ->
                    val stripper = PdfTextExtractor(tracker).apply {
                        startPage = page
                        endPage = page
                    }
                    runCatching { stripper.getText(document) }.getOrDefault("")
                }
                // Убираем номера страниц и повторяющиеся колонтитулы до разбиения на фрагменты.
                val cleanedPages = ImportTextCleaner.cleanPages(
                    pageTexts,
                    CleanupOptions(joinHyphenation = preset != TextPreset.POETRY),
                ).pages
                val outline = readOutline(document)
                val fromOutline = if (outline.size >= MIN_OUTLINE_ENTRIES) {
                    chaptersFromOutline(cleanedPages, outline, preset)
                } else {
                    emptyList()
                }
                val chapters = when {
                    fromOutline.size >= 2 -> fromOutline
                    preset == TextPreset.POETRY -> {
                        val fromHeadings = chaptersFromHeadings(document, cleanedPages, preset)
                        if (fromHeadings.size >= 2) {
                            fromHeadings
                        } else {
                            listOf(
                                Chapter(
                                    "Стихи",
                                    TextSegmenter.segmentFor(cleanedPages.joinToString("\n\n"), preset),
                                ),
                            )
                        }
                    }
                    else -> chaptersFromHeadings(document, cleanedPages, preset).ifEmpty {
                        chaptersFromPages(cleanedPages, preset)
                    }
                }
                ParsedBook(title, author, chapters)
            }
        } catch (_: Exception) {
            // Повреждённый, зашифрованный или недоступный PDF — вернём пустую книгу,
            // репозиторий подставит заглушку.
            ParsedBook(fallbackTitle, null, emptyList())
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
                val text = pages.subList(startPage, safeEnd).joinToString("\n\n")
                val segments = TextSegmenter.segmentFor(text, preset)
                if (segments.isNotEmpty()) {
                    chapters += Chapter(title, segments, depth)
                }
            }
            index = next
        }
        return chapters
    }

    private fun chaptersFromPages(pages: List<String>, preset: TextPreset): List<Chapter> =
        pages.mapIndexedNotNull { index, text ->
            val segments = TextSegmenter.segmentFor(text, preset)
            if (segments.isEmpty()) null else Chapter("Страница ${index + 1}", segments)
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
                    pages.subList(startPage, safeEnd).joinToString("\n\n"),
                    preset,
                )
                if (segments.isNotEmpty()) {
                    chapters += Chapter(title, segments, depth)
                }
            }
            index = next
        }
        return chapters
    }

    /** PDF-генераторы часто оставляют заглушки вроде «untitled»/«anonymous». */
    private fun cleanMetadata(value: String?, placeholders: Set<String>): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.takeIf { it.lowercase() !in placeholders }
    }

    private companion object {
        val TITLE_PLACEHOLDERS = setOf("untitled", "без названия", "unknown")
        val AUTHOR_PLACEHOLDERS = setOf("anonymous", "unknown", "user", "admin")
        val WHITESPACE = Regex("\\s+")
        const val MIN_OUTLINE_ENTRIES = 3
        const val MAX_DEPTH = 2
    }
}
