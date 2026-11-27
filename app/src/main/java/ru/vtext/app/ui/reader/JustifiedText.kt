package ru.vtext.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Слово на экране: где нарисовано и как разложено.
 * Единица и отрисовки, и выделения — поэтому координаты всегда совпадают.
 */
class TextRun(
    val start: Int,
    val length: Int,
    val x: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val layout: TextLayoutResult,
)

/** Разложенный текст: строки и слова с точными координатами. */
class TextPlan(
    val runs: List<TextRun>,
    val width: Float,
    val height: Float,
    val lineTops: FloatArray,
    val lineBottoms: FloatArray,
) {
    val length: Int = runs.maxOfOrNull { it.start + it.length } ?: 0

    fun runAt(offset: Int): TextRun? = runs.lastOrNull { offset in it.start..(it.start + it.length) }

    /** Горизонталь символа: для начала — левый край, для конца — правый. */
    fun charX(offset: Int, isEnd: Boolean): Float? {
        val run = runAt(offset) ?: runAt(offset - 1) ?: return null
        val local = (offset - run.start).coerceIn(0, run.length)
        val rect = run.layout.getCursorRect(local)
        return if (isEnd) run.x + rect.right else run.x + rect.left
    }

    fun lineOf(offset: Int): Int {
        val y = runAt(offset)?.let { it.top + it.height / 2f } ?: return 0
        var best = 0
        for (index in lineTops.indices) {
            if (y >= lineTops[index] - 1f) best = index
        }
        return best
    }
}

/**
 * Раскладывает текст по строкам средствами движка (переносы слов не меняем),
 * а затем считает горизонтальные сдвиги слов для выравнивания по ширине.
 * Рисуется потом ровно по этим числам.
 */
fun buildTextPlan(
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    measurer: TextMeasurer,
    justify: Boolean,
): TextPlan {
    if (widthPx <= 0) return TextPlan(emptyList(), 0f, 0f, FloatArray(0), FloatArray(0))
    val base = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = widthPx),
        maxLines = Int.MAX_VALUE,
    )
    val runs = ArrayList<TextRun>(base.lineCount * 6)
    val tops = FloatArray(base.lineCount)
    val bottoms = FloatArray(base.lineCount)
    for (line in 0 until base.lineCount) {
        tops[line] = base.getLineTop(line)
        bottoms[line] = base.getLineBottom(line)
        val start = base.getLineStart(line)
        val end = base.getLineEnd(line, visibleEnd = true)
        if (end <= start) continue
        // Слова строки: последовательности непробельных символов.
        val words = ArrayList<IntRange>(16)
        var index = start
        while (index < end) {
            if (text[index].isWhitespace()) {
                index++
                continue
            }
            var stop = index
            while (stop < end && !text[stop].isWhitespace()) stop++
            words += index until stop
            index = stop
        }
        if (words.isEmpty()) continue
        val gaps = words.size - 1
        val last = line == base.lineCount - 1
        val extra = widthPx - base.getLineRight(line)
        val gap = if (justify && !last && gaps > 0 && extra > 0.5f) extra / gaps else 0f
        words.forEachIndexed { order, range ->
            val slice = text.subSequence(range.first, range.last + 1)
            val wordLayout = measurer.measure(
                text = slice,
                style = style,
                constraints = Constraints(maxWidth = widthPx),
                maxLines = 1,
            )
            val naturalX = base.getBoundingBox(range.first).left
            runs += TextRun(
                start = range.first,
                length = range.last - range.first + 1,
                x = naturalX + gap * order,
                top = tops[line],
                width = wordLayout.size.width.toFloat(),
                height = (bottoms[line] - tops[line]),
                layout = wordLayout,
            )
        }
    }
    return TextPlan(runs, base.size.width.toFloat(), base.size.height.toFloat(), tops, bottoms)
}

/** Ключ кэша разложенного текста. */
private data class PlanKey(
    val text: AnnotatedString,
    val style: TextStyle,
    val width: Int,
    val justify: Boolean,
)

/** Разложенный текст переиспользуем: иначе каждый кадр меряет слова заново. */
private val planCache = object : android.util.LruCache<PlanKey, TextPlan>(24) {}

/**
 * Текст с выравниванием по ширине: слова рисуются по нашим координатам.
 * Планы принимает [onPlan] — по ним же работает выделение.
 */
@Composable
fun JustifiedText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    justify: Boolean,
    modifier: Modifier = Modifier,
    /** Фоновые пометки (цитаты, текущее выделение) — рисуем прямоугольниками. */
    backgrounds: List<Pair<IntRange, Color>> = emptyList(),
    onPlan: (TextPlan, Rect) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Ширину берём из ограничений сразу: иначе первый кадр считается с
        // width = 0, блок получает высоту 0 и текст «прыгает» на втором кадре.
        val width = constraints.maxWidth
        val plan = remember(text, style, width, justify) {
            val key = PlanKey(text, style, width, justify)
            planCache.get(key) ?: buildTextPlan(text, style, width, measurer, justify)
                .also { planCache.put(key, it) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onPlan(plan, it.boundsInRoot()) }
                .height(if (plan.height > 0f) with(density) { plan.height.toDp() } else 0.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
            // Фон пометок: от первого до последнего символа диапазона на строке.
            backgrounds.forEach { (range, bg) ->
                if (range.last + 1 <= range.first) return@forEach
                val from = range.first
                val to = range.last + 1
                plan.lineTops.indices.forEach { line ->
                    val onLine = plan.runs.filter { it.top == plan.lineTops[line] }
                    val touched = onLine.filter { it.start < to && it.start + it.length > from }
                    if (touched.isEmpty()) return@forEach
                    val first = touched.first()
                    val last = touched.last()
                    val x1 = first.x + first.layout.getCursorRect(
                        (from - first.start).coerceIn(0, first.length),
                    ).left
                    val x2 = last.x + last.layout.getCursorRect(
                        (to - last.start).coerceIn(0, last.length),
                    ).left
                    if (x2 <= x1) return@forEach
                    drawRect(
                        color = bg,
                        topLeft = Offset(x1, plan.lineTops[line]),
                        size = androidx.compose.ui.geometry.Size(x2 - x1, plan.lineBottoms[line] - plan.lineTops[line]),
                    )
                }
            }
            plan.runs.forEach { run ->
                drawText(
                    textLayoutResult = run.layout,
                    topLeft = Offset(run.x, run.top),
                    color = color,
                )
            }
            }
        }
    }
}

