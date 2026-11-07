package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * PDFTextStripper, который выбрасывает текст служебных шрифтов (Wingdings, Symbol,
 * Webdings и т.п.). В таких шрифтах PDFBox иногда отдаёт мусорные пары вроде «9z»
 * вместо маркера списка или иконки.
 */
class PdfTextExtractor : PDFTextStripper() {

    init {
        sortByPosition = true
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val visible = textPositions.filterNot { isSymbolFont(it.font?.name) }
        if (visible.isEmpty()) return
        val cleaned = visibleText(visible)
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
