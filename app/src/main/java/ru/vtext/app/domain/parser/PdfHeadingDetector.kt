package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.roundToInt

/** Одна строка PDF с данными шрифта и позицией — для поиска заголовков без закладок. */
data class PdfLineInfo(
    val page: Int,
    val text: String,
    val fontSize: Float,
    val bold: Boolean,
    val y: Float,
    val pageHeight: Float,
)

class PdfLayoutExtractor : PDFTextStripper() {

    val lines = mutableListOf<PdfLineInfo>()

    init {
        sortByPosition = true
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val visible = textPositions.filterNot { isSymbolFont(it.font?.name) }
        if (visible.isEmpty()) return
        val first = visible.first()
        val normalized = visibleText(visible)
        if (normalized.isEmpty()) return
        val size = visible.maxOf { it.fontSizeInPt }
        if (size <= 0f) return
        val fontName = first.font?.name.orEmpty().lowercase()
        lines += PdfLineInfo(
            page = currentPageNo - 1,
            text = normalized,
            fontSize = size,
            bold = BOLD_MARKERS.any { it in fontName },
            y = first.yDirAdj,
            pageHeight = first.pageHeight,
        )
    }

    private companion object {
        val BOLD_MARKERS = listOf("bold", "black", "heavy", "semibold", "demi")
    }
}

/**
 * Эвристика для PDF без встроенного оглавления: заголовки отличаются от тела
 * размером шрифта или жирностью. Уровень определяется относительно основного шрифта.
 */
object PdfHeadingDetector {

    data class Heading(val page: Int, val text: String, val depth: Int)

    fun detect(lines: List<PdfLineInfo>, pageCount: Int): List<Heading> {
        if (lines.isEmpty() || pageCount < MIN_PAGES) return emptyList()
        val bodySize = bodyFontSize(lines)
        val maxSize = lines.maxOfOrNull { it.fontSize } ?: bodySize
        val chapterThreshold = if (maxSize >= bodySize * 1.5f) bodySize * 1.5f else maxSize * 0.95f

        val candidates = lines.filter { line ->
            val large = line.fontSize >= bodySize * 1.25f
            val boldHeading = line.bold && line.fontSize >= bodySize * 1.05f && !line.text.endsWith(".")
            (large || boldHeading) &&
                line.text.length in 2..MAX_HEADING_LENGTH &&
                !PAGE_NUMBER.matches(line.text) &&
                !line.text.contains("...")
        }
        if (candidates.size < 2) return emptyList()

        // Повторяющийся крупный текст — это колонтитул, а не заголовок.
        val normalizedCounts = candidates.groupingBy { normalize(it.text) }.eachCount()
        val maxRepeat = maxOf(3, pageCount / 2)
        val filtered = candidates.filter { (normalizedCounts[normalize(it.text)] ?: 0) < maxRepeat }
        if (filtered.size < 2) return emptyList()

        return filtered
            .distinctBy { it.page to normalize(it.text) }
            .map { line ->
                Heading(
                    page = line.page,
                    text = line.text,
                    depth = if (line.fontSize >= chapterThreshold) 0 else 1,
                )
            }
    }

    private fun bodyFontSize(lines: List<PdfLineInfo>): Float {
        val weights = mutableMapOf<Float, Int>()
        for (line in lines) {
            val rounded = (line.fontSize * 2f).roundToInt() / 2f
            weights[rounded] = (weights[rounded] ?: 0) + line.text.length
        }
        return weights.maxByOrNull { it.value }?.key ?: 10f
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()

    private const val MIN_PAGES = 3
    private const val MAX_HEADING_LENGTH = 120
    private val PAGE_NUMBER = Regex("""^\s*[-–—]?\s*(?:\d{1,4}|[ivxlcdm]{1,7})\s*[-–—]?\s*$""", RegexOption.IGNORE_CASE)
}
