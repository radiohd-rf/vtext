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
        val cleaned = if (tracker.disabled) {
            visibleText(visible)
        } else {
            visibleTextWithEmphasis(visible, tracker)
        }
        if (cleaned.isBlank()) return
        super.writeString(cleaned)
    }
}

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
