package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.roundToInt

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

    /** Слово страницы с координатами: нужно, чтобы собрать строки и отсеять сноски. */
    private data class PageWord(
        val text: String,
        val startX: Float,
        val endX: Float,
        val y: Float,
        val size: Float,
    )

    private data class PageLine(
        val text: String,
        val startX: Float,
        val endX: Float,
        val y: Float,
        val size: Float,
        var newParagraph: Boolean = false,
    )

    private val pageWords = ArrayList<PageWord>()
    private var bufferedPage = -1

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

        val page = currentPageNo
        if (page != bufferedPage) {
            pageWords.clear()
            bufferedPage = page
        }
        pageWords += PageWord(
            text = cleaned,
            startX = withoutMarkers.first().xDirAdj,
            endX = withoutMarkers.maxOf { it.xDirAdj + it.widthDirAdj },
            y = withoutMarkers.first().yDirAdj,
            size = withoutMarkers.first().fontSizeInPt.takeIf { it > 0f } ?: 10f,
        )
    }

    override fun writePageEnd() {
        if (pageWords.isNotEmpty()) {
            emitPage()
        }
        pageWords.clear()
        super.writePageEnd()
    }

    /**
     * Собирает строки страницы и размечает абзацы. Текст сносок внизу страницы
     * (заметно мельче основного) отбрасывается: он не относится к основному
     * тексту, а в постраничном извлечении ещё и разрывает перенос слова.
     */
    private fun emitPage() {
        val bodySize = bodyFontSize(pageWords)
        val bodyBottom = pageWords
            .filter { it.size >= bodySize * 0.95f }
            .maxOfOrNull { it.y }
        val words = if (bodyBottom == null) {
            pageWords
        } else {
            pageWords.filterNot {
                it.size < bodySize * FOOTNOTE_SIZE_RATIO &&
                    it.y > bodyBottom + bodySize * 0.1f
            }
        }
        if (words.isEmpty()) return

        val lines = buildLines(words)
        markParagraphs(lines)

        val text = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                text.append(if (line.newParagraph) "\n\n" else "\n")
            }
            text.append(line.text)
        }
        super.writeString(text.toString())
    }

    /** Основной кегль страницы: размер, которым набрано больше всего символов. */
    private fun bodyFontSize(words: List<PageWord>): Float {
        val weights = HashMap<Int, Int>()
        for (word in words) {
            val key = (word.size * 10f).roundToInt()
            weights[key] = (weights[key] ?: 0) + word.text.length
        }
        val best = weights.maxByOrNull { it.value }?.key ?: 100
        return best / 10f
    }

    private fun buildLines(words: List<PageWord>): List<PageLine> {
        val lines = ArrayList<PageLine>()
        val text = StringBuilder()
        var startX = 0f
        var endX = 0f
        var y = Float.NaN
        var size = 0f

        fun flush() {
            if (text.isEmpty()) return
            lines += PageLine(text.toString(), startX, endX, y, size)
            text.setLength(0)
        }

        for (word in words) {
            val sameLine = !y.isNaN() && kotlin.math.abs(word.y - y) < word.size * 0.4f
            if (!sameLine) {
                flush()
                startX = word.startX
                endX = word.endX
                y = word.y
                size = word.size
            } else {
                endX = maxOf(endX, word.endX)
            }
            if (text.isNotEmpty()) text.append(' ')
            text.append(word.text)
        }
        flush()
        return lines
    }

    /**
     * Абзац начинается с отступа, после короткой строки или через увеличенный интервал.
     * В стихах короткая строка — это строка стихотворения, а не конец абзаца,
     * поэтому там разрывы даёт только отступ, тире и увеличенный интервал (строфы).
     */
    private fun markParagraphs(lines: List<PageLine>) {
        if (lines.size < 2) return
        // Основное поле — самый частый отступ «длинных» строк, а не самый левый:
        // номер страницы или выступ списка не должны сдвигать поле для всех строк,
        // иначе каждая строка абзаца считалась новым абзацем.
        val bodyLines = lines.filter { it.text.length >= BODY_LINE_MIN_CHARS }
            .ifEmpty { lines }
        val leftMargin = modeStartX(bodyLines)
        val rightEdge = percentileEndX(bodyLines, 0.9f)
        val lengths = lines.map { it.text.length }.sorted()
        val medianLength = lengths[lengths.size / 2]
        val verse = lines.size >= MIN_VERSE_LINES && medianLength <= MAX_VERSE_LINE_LENGTH
        // Обычный межстрочный интервал — медиана всех разрывов на странице.
        // Сравнение с предыдущим разрывом сбивалось, если страница начинается
        // с крупного заголовка или строфы.
        val gaps = (1 until lines.size)
            .map { lines[it].y - lines[it - 1].y }
            .filter { it > 0f }
            .sorted()
        val lineGap = if (gaps.isEmpty()) 0f else gaps[gaps.size / 2]
        for (index in 1 until lines.size) {
            val line = lines[index]
            val previous = lines[index - 1]
            val gap = line.y - previous.y
            // Отступ первой строки абзаца заметно больше погрешности вёрстки.
            val indent = maxOf(6f, line.size * 1.2f)
            val shortBefore = !verse &&
                previous.endX < rightEdge - line.size * 2.5f &&
                startsParagraph(line.text)
            val largeGap = lineGap > 0f && gap > lineGap * 1.45f
            val dashStarts = line.text.startsWith("—") || line.text.startsWith("–")
            // Строка продолжает предложение (начинается со строчной, а предыдущая
            // не закончилась знаком конца) — это перенос, а не новый абзац.
            val startsLower = line.text.firstOrNull()?.isLowerCase() == true
            val previousEndsSentence =
                previous.text.lastOrNull()?.let { it in SENTENCE_ENDINGS } == true
            // Абзац не начинается со строчной: это продолжение предложения,
            // даже если вёрстка дала большой интервал (буквица, отбивка).
            val continuesSentence = startsLower && !previousEndsSentence
            line.newParagraph = !continuesSentence && (
                line.startX > leftMargin + indent || shortBefore || largeGap || dashStarts
                )
        }
    }

    /** Мода левых краёв строк: основной текст, а не номера страниц и выступы. */
    private fun modeStartX(lines: List<PageLine>): Float {
        val counts = lines.groupingBy { it.startX.roundToInt() }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return lines.minOf { it.startX }
        val value = counts.filter { it.value == best.value }.keys.minOrNull() ?: best.key
        return value.toFloat()
    }

    /** Правый край по перцентилю: одна широкая строка не должна растягивать поле. */
    private fun percentileEndX(lines: List<PageLine>, percentile: Float): Float {
        val sorted = lines.map { it.endX }.sorted()
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * percentile).roundToInt()
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
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

/** Стихи: средняя длина строки на странице заметно меньше прозы. */
private const val MIN_VERSE_LINES = 4
private const val MAX_VERSE_LINE_LENGTH = 55

/** Строки короче этого не участвуют в оценке полей: номера, колонтитулы. */
private const val BODY_LINE_MIN_CHARS = 20

/** Знаки, которыми заканчивается предложение или прямая речь. */
private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…', ':', ';', '»', '"')

/** Текст мельче этого отношения к основному кеглю внизу страницы - сноска. */
private const val FOOTNOTE_SIZE_RATIO = 0.88f

private const val SUPERSCRIPT_SIZE_RATIO = 0.8f
private const val SUPERSCRIPT_RAISE_RATIO = 0.15f

internal fun isSymbolFont(name: String?): Boolean {
    val value = name?.lowercase().orEmpty()
    return SYMBOL_FONT_MARKERS.any { it in value }
}

/** Похоже ли начало строки на начало нового абзаца: заглавная, цифра, кавычка, тире. */
internal fun startsParagraph(text: String): Boolean {
    val first = text.firstOrNull() ?: return false
    return first.isUpperCase() || first.isDigit() || first in "«\"'([—–-*•"
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
