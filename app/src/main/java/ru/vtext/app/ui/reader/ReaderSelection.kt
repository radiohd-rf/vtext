package ru.vtext.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ru.vtext.app.R
import ru.vtext.app.data.Annotation
import kotlin.math.roundToInt

/** Позиция в тексте главы: номер фрагмента и смещение в нём. */
data class TextPos(val segment: Int, val offset: Int)

/** Выделение внутри главы: [start] включительно, [end] — исключая. */
data class ReaderSelection(val chapter: Int, val start: TextPos, val end: TextPos) {
    val empty: Boolean
        get() = start.segment == end.segment && start.offset == end.offset
}

/** Упорядочивает две точки: выделение всегда идёт от начала текста к концу. */
fun orderedSelection(chapter: Int, first: TextPos, second: TextPos): ReaderSelection {
    val direct = first.segment < second.segment ||
        (first.segment == second.segment && first.offset <= second.offset)
    return if (direct) {
        ReaderSelection(chapter, first, second)
    } else {
        ReaderSelection(chapter, second, first)
    }
}

/**
 * Кусок текста на экране: где он нарисован и как разложен.
 * По этим данным ищем слово под пальцем и рисуем ручки выделения.
 */
class TextBlock(
    val chapter: Int,
    val segment: Int,
    /** Смещение куска внутри исходного фрагмента (страница может начинаться с середины). */
    val charOffset: Int,
    val text: String,
    val layout: TextLayoutResult,
    val bounds: Rect,
) {
    val length: Int get() = text.length

    /** Смещение символа под точкой экрана (в координатах фрагмента). */
    fun offsetAt(point: Offset): Int {
        val local = Offset(
            (point.x - bounds.left).coerceIn(0f, layout.size.width.toFloat()),
            (point.y - bounds.top).coerceIn(0f, layout.size.height.toFloat()),
        )
        return layout.getOffsetForPosition(local).coerceIn(0, length)
    }

    /** Слово под точкой: диапазон [from, to) в координатах фрагмента. */
    fun wordAt(point: Offset): IntRange {
        val offset = offsetAt(point)
        if (text.isEmpty()) return 0..0
        val index = offset.coerceIn(0, text.lastIndex)
        if (!text[index].isLetterOrDigit()) {
            return index..(index + 1)
        }
        var from = index
        var to = index
        while (from > 0 && text[from - 1].isLetterOrDigit()) from--
        while (to < text.lastIndex && text[to + 1].isLetterOrDigit()) to++
        return from..(to + 1)
    }

    /** Прямоугольник каретки в координатах экрана. */
    fun caret(offset: Int): Rect =
        layout.getCursorRect(offset.coerceIn(0, length))
            .translate(bounds.left, bounds.top)
}

/** Кэш видимых кусков текста: обновляется из [onTextLayout] и [onGloballyPositioned]. */
class BlockRegistry {
    private val blocks = HashMap<String, TextBlock>()

    fun put(key: String, block: TextBlock) {
        blocks[key] = block
    }

    fun remove(key: String) {
        blocks.remove(key)
    }

    /** Блок под точкой экрана; при промахе — ближайший в главе. */
    fun at(point: Offset, chapter: Int? = null): TextBlock? {
        val visible = if (chapter == null) {
            blocks.values.toList()
        } else {
            blocks.values.filter { it.chapter == chapter }
        }
        if (visible.isEmpty()) return null
        visible.firstOrNull { it.bounds.contains(point) }?.let { return it }
        return visible.minByOrNull { distance(it.bounds, point) }
    }

    /** Каретка позиции в координатах экрана (если блок виден). */
    fun caret(chapter: Int, pos: TextPos): Rect? {
        val block = blocks.values
            .filter {
                it.chapter == chapter &&
                    it.segment == pos.segment &&
                    pos.offset >= it.charOffset &&
                    pos.offset <= it.charOffset + it.length
            }
            .maxByOrNull { it.charOffset } ?: return null
        return block.caret(pos.offset - block.charOffset)
    }
}

/** Значения, которые кусок текста записывает в [BlockRegistry] из колбэков вёрстки. */
class BlockHolder {
    var layout: TextLayoutResult? = null
    var bounds: Rect = Rect.Zero
}

private fun distance(bounds: Rect, point: Offset): Float {
    val dx = when {
        point.x < bounds.left -> bounds.left - point.x
        point.x > bounds.right -> point.x - bounds.right
        else -> 0f
    }
    val dy = when {
        point.y < bounds.top -> bounds.top - point.y
        point.y > bounds.bottom -> point.y - bounds.bottom
        else -> 0f
    }
    return dx + dy
}

/** Часть выделения, попавшая в кусок текста, в координатах куска. */
fun ReaderSelection.rangeIn(segment: Int, charOffset: Int, length: Int): IntRange? {
    if (segment < start.segment || segment > end.segment) return null
    val from = if (segment == start.segment) start.offset - charOffset else 0
    val to = if (segment == end.segment) end.offset - charOffset else length
    val first = from.coerceIn(0, length)
    val last = to.coerceIn(0, length)
    return if (first < last) first until last else null
}

/** Часть сохранённой цитаты или заметки, попавшая в кусок текста. */
fun Annotation.rangeIn(segment: Int, charOffset: Int, length: Int): IntRange? {
    if (segment < startSegment || segment > endSegment) return null
    val from = if (segment == startSegment) startOffset - charOffset else 0
    val to = if (segment == endSegment) endOffset - charOffset else length
    val first = from.coerceIn(0, length)
    val last = to.coerceIn(0, length)
    return if (first < last) first until last else null
}

/**
 * Долгое нажатие по тексту начинает выделение слова, протяжка его расширяет.
 * События при активном выделении поглощаются в Initial-проходе, поэтому
 * страница не листается, а лента не прокручивается.
 */
fun Modifier.textSelectionGestures(
    registry: BlockRegistry,
    origin: () -> Offset,
    selection: () -> ReaderSelection?,
    onSelect: (ReaderSelection?) -> Unit,
): Modifier = pointerInput(registry) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Выделение уже есть: одиночный тап его снимает.
        if (selection() != null) {
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > 8f) moved = true
                if (!change.pressed) {
                    if (!moved) onSelect(null)
                    change.consume()
                    return@awaitEachGesture
                }
            }
            return@awaitEachGesture
        }

        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val root = origin() + longPress.position
        val block = registry.at(root) ?: return@awaitEachGesture
        val word = block.wordAt(root)
        val anchor = TextPos(block.segment, block.charOffset + word.first)
        onSelect(
            orderedSelection(
                block.chapter,
                anchor,
                TextPos(block.segment, block.charOffset + word.last),
            ),
        )
        longPress.consume()

        // Протяжка: тянем второй конец выделения, можно через абзацы.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            val point = origin() + change.position
            val target = registry.at(point, block.chapter) ?: continue
            val pos = TextPos(target.segment, target.charOffset + target.offsetAt(point))
            onSelect(orderedSelection(block.chapter, anchor, pos))
        }
    }
}

/**
 * Ручки выделения и всплывающие кнопки «Цитата» / «Заметка».
 * Координаты приходят в координатах экрана, рисуем их со сдвигом [screenOrigin].
 */
@Composable
fun ReaderSelectionOverlay(
    selection: ReaderSelection,
    registry: BlockRegistry,
    screenOrigin: Offset,
    screenWidth: Int,
    onExtendStart: (Offset) -> Unit,
    onExtendEnd: (Offset) -> Unit,
    onQuote: () -> Unit,
    onNote: () -> Unit,
) {
    val density = LocalDensity.current
    val startRect = remember(selection) { registry.caret(selection.chapter, selection.start) }
    val endRect = remember(selection) { registry.caret(selection.chapter, selection.end) }
    val handleHalf = with(density) { 11.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        startRect?.let { rect ->
            SelectionHandle(
                rootPoint = Offset(rect.left, rect.top),
                screenOrigin = screenOrigin,
                onChange = onExtendStart,
            )
        }
        endRect?.let { rect ->
            SelectionHandle(
                rootPoint = Offset(rect.right, rect.bottom),
                screenOrigin = screenOrigin,
                onChange = onExtendEnd,
            )
        }

        val anchor = endRect ?: startRect
        if (anchor != null) {
            var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
            val gap = with(density) { 10.dp.toPx() }
            val limit = (screenWidth - toolbarSize.width - gap).coerceAtLeast(gap)
            val x = (anchor.left - toolbarSize.width / 2f).coerceIn(gap, limit)
            val above = anchor.top - toolbarSize.height - gap * 2
            val y = if (above > gap) above else anchor.bottom + gap * 2
            SelectionToolbar(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (x - screenOrigin.x).roundToInt(),
                            (y - screenOrigin.y).roundToInt(),
                        )
                    }
                    .onSizeChanged { toolbarSize = it },
                onQuote = onQuote,
                onNote = onNote,
            )
        }
    }
}

@Composable
private fun SelectionHandle(
    rootPoint: Offset,
    screenOrigin: Offset,
    onChange: (Offset) -> Unit,
) {
    val currentPoint by rememberUpdatedState(rootPoint)
    var dragged by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val half = with(density) { 11.dp.toPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (rootPoint.x - half - screenOrigin.x).roundToInt(),
                    (rootPoint.y - half - screenOrigin.y).roundToInt(),
                )
            }
            .size(22.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { local ->
                        dragged = currentPoint - Offset(half, half) + local
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        dragged += delta
                        onChange(dragged)
                    },
                )
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun SelectionToolbar(
    modifier: Modifier = Modifier,
    onQuote: () -> Unit,
    onNote: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row {
            TextButton(onClick = onQuote) {
                Text(
                    text = stringResource(R.string.quote_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(onClick = onNote) {
                Text(
                    text = stringResource(R.string.note_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
