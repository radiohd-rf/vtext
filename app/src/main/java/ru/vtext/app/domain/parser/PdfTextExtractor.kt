package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Счётчик разметки на весь документ: если «выделен» почти весь текст, эмфазу отключаем. */
class PdfStyleTracker {
    var total = 0
    var marked = 0
    var disabled = false
}

/**
 * PDFTextStripper, который выбрасывает текст служебных шрифтов (Wingdings, Symbol,
 * Webdings и т.п.) и размечает курсив/жирный маркерами `*`/`**` для эмфазы.
 * В таких шрифтах PDFBox иногда отдаёт мусорные пары вроде «9z» вместо маркера
 * списка или иконки.
 */
class PdfTextExtractor(
    private val tracker: PdfStyleTracker = PdfStyleTracker(),
) : PDFTextStripper() {

    init {
        sortByPosition = true
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val visible = textPositions.filterNot { isSymbolFont(it.font?.name) }
        if (visible.isEmpty()) return
        val withoutMarkers = removeFootnoteMarkers(visible)
        if (withoutMarkers.isEmpty()) return
        val cleaned = if (tracker.disabled) {
            visibleText(withoutMarkers)
        } else {
            visibleTextWithEmphasis(withoutMarkers, tracker)
        }
        if (cleaned.isBlank()) return
        super.writeString(cleaned)
    }

    /** Убирает верхние индексы-номера сносок, оставляя остальной текст строки. */
    private fun removeFootnoteMarkers(positions: List<TextPosition>): List<TextPosition> {
        if (positions.size < 2) return positions
        val sizes = FloatArray(positions.size) { positions[it].fontSizeInPt }
        val ys = FloatArray(positions.size) { positions[it].yDirAdj }
        val mask = superscriptMask(sizes, ys)
        if (mask.none { it }) return positions
        val keep = BooleanArray(positions.size) { true }
        var index = 0
        while (index < positions.size) {
            if (!mask[index]) {
                index++
                continue
            }
            var end = index
            while (end < positions.size && mask[end]) end++
            val run = positions.subList(index, end)
            val marker = run.joinToString("") { it.unicode ?: "" }
            val markerLike = marker.isNotEmpty() && marker.length <= 3 &&
                marker.all { it.isDigit() || it.isLetter() || it == '*' || it == '†' || it == '‡' }
            if (markerLike) {
                for (position in index until end) keep[position] = false
            }
            index = end
        }
        return positions.filterIndexed { position, _ -> keep[position] }
    }
}

/** Верхний индекс: заметно мельче основного текста строки и приподнят над базовой линией. */
internal fun superscriptMask(sizes: FloatArray, yPositions: FloatArray): BooleanArray {
    if (sizes.isEmpty() || sizes.size != yPositions.size) return BooleanArray(sizes.size)
    val bodySize = median(sizes)
    if (bodySize <= 0f) return BooleanArray(sizes.size)
    val baseline = median(yPositions)
    return BooleanArray(sizes.size) { index ->
        val size = sizes[index]
        size > 0.1f && size <= bodySize * SUPERSCRIPT_SIZE_RATIO &&
            yPositions[index] < baseline - bodySize * SUPERSCRIPT_RAISE_RATIO
    }
}

private fun median(values: FloatArray): Float {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
}

private const val SUPERSCRIPT_SIZE_RATIO = 0.8f
private const val SUPERSCRIPT_RAISE_RATIO = 0.15f

internal fun isSymbolFont(name: String?): Boolean {
    val value = name?.lowercase().orEmpty()
    return SYMBOL_FONT_MARKERS.any { it in value }
}

internal fun visibleText(positions: List<TextPosition>): String = buildString {
    for (position in positions) {
        append(position.unicode ?: "")
    }
}.replace(WHITESPACE, " ").trim()

/** Текст строки с маркерами эмфазы по шрифту: `*курсив*`, `**жирный**`. */
internal fun visibleTextWithEmphasis(positions: List<TextPosition>, tracker: PdfStyleTracker): String {
    val chars = positions.map { (it.unicode ?: "") to styleOf(it.font) }
    return emphasizedText(chars, tracker)
}

/** Разметка эмфазы для последовательности «символ → стиль» (0 — обычный, 1 — курсив, 2 — жирный). */
internal fun emphasizedText(chars: List<Pair<String, Int>>, tracker: PdfStyleTracker): String {
    val plain = buildString { chars.forEach { append(it.first) } }.replace(WHITESPACE, " ").trim()
    tracker.total += plain.length

    val lineStyles = chars.map { it.second }.distinct()
    val lastChar = plain.lastOrNull()
    val headingLike = lineStyles.size == 1 && lineStyles.first() == STYLE_BOLD &&
        plain.length <= HEADING_MAX_CHARS && (lastChar == null || lastChar !in ".!?…")

    val builder = StringBuilder()
    val run = StringBuilder()
    var runStyle = STYLE_NORMAL

    fun flush() {
        if (run.isEmpty()) return
        val value = run.toString()
        run.setLength(0)
        if (runStyle == STYLE_NORMAL) {
            builder.append(value)
            return
        }
        val core = value.trim()
        if (core.length < 2 || core.none { it.isLetter() }) {
            builder.append(value)
            return
        }
        val lead = if (value.first().isWhitespace()) " " else ""
        val trail = if (value.last().isWhitespace()) " " else ""
        val marker = if (runStyle == STYLE_BOLD) "**" else "*"
        builder.append(lead).append(marker).append(core).append(marker).append(trail)
        tracker.marked += core.length
    }

    for ((char, style) in chars) {
        if (style != runStyle) {
            flush()
            runStyle = style
        }
        run.append(char)
    }
    flush()

    val marked = builder.toString().replace(WHITESPACE, " ").trim()
    // Защита от книг, где весь набор жирный или курсивный.
    if (tracker.total > RATIO_MIN_CHARS && tracker.marked * 100 > tracker.total * MAX_MARKED_PERCENT) {
        tracker.disabled = true
        return plain
    }
    return if (headingLike) plain else marked
}

private fun styleOf(font: PDFont?): Int {
    val name = font?.name?.lowercase().orEmpty()
    if (name.isEmpty()) return STYLE_NORMAL
    if (BOLD_MARKERS.any { it in name }) return STYLE_BOLD
    if (ITALIC_MARKERS.any { it in name }) return STYLE_ITALIC
    return STYLE_NORMAL
}

private const val STYLE_NORMAL = 0
private const val STYLE_ITALIC = 1
private const val STYLE_BOLD = 2
private const val HEADING_MAX_CHARS = 60
private const val RATIO_MIN_CHARS = 4000
private const val MAX_MARKED_PERCENT = 35

private val BOLD_MARKERS = listOf("bold", "black", "heavy", "demi", "semibold", "extrabold")
private val ITALIC_MARKERS = listOf("italic", "oblique")

private val SYMBOL_FONT_MARKERS = listOf(
    "wingdings",
    "webdings",
    "symbol",
    "dingbats",
    "marlett",
    "fontawesome",
    "material icons",
)

private val WHITESPACE = Regex("\\s+")
