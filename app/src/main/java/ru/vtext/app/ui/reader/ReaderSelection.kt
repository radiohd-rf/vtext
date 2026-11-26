package ru.vtext.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.vtext.app.R
import ru.vtext.app.data.Annotation
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Строки растянуты по ширине: позиции из раскладки надо сдвигать. */
    val justified: Boolean = false,
) {
    /** Порядковый номер регистрации: свежий блок важнее устаревшего. */
    var seq: Long = 0L
    val length: Int get() = text.length

    /** Смещение символа под точкой экрана (в координатах фрагмента). */
    fun offsetAt(point: Offset): Int {
        val local = Offset(
            (point.x - bounds.left).coerceIn(0f, layout.size.width.toFloat()),
            (point.y - bounds.top).coerceIn(0f, layout.size.height.toFloat()),
        )
        val raw = layout.getOffsetForPosition(local).coerceIn(0, length)
        if (!justified || length == 0) return raw
        val line = layout.getLineForVerticalPosition(local.y)
        val from = layout.getLineStart(line)
        val to = layout.getLineEnd(line, visibleEnd = true)
        if (to <= from) return raw
        return (from..to).minByOrNull { index ->
            kotlin.math.abs(layout.getCursorRect(index).left + justifyShift(line, index) - local.x)
        } ?: raw
    }

    /** Слово под точкой: диапазон [from, to) в координатах фрагмента. */
    fun wordAt(point: Offset): IntRange? {
        if (text.isEmpty()) return null
        var index = offsetAt(point).coerceIn(0, text.lastIndex)
        // Пробел или знак — само по себе не выделяем: берём ближайшее слово,
        // сначала слева (как на платформе), иначе первое справа.
        if (!text[index].isLetterOrDigit()) {
            var left = index
            while (left >= 0 && !text[left].isLetterOrDigit()) left--
            if (left >= 0) {
                index = left
            } else {
                var right = index
                while (right < text.length && !text[right].isLetterOrDigit()) right++
                if (right >= text.length) return null
                index = right
            }
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

    /** Точка под краем выделения: острие ручки в координатах экрана. */
    fun edgePoint(offset: Int, isEnd: Boolean): Offset {
        val rect = edge(offset, isEnd)
        return Offset(rect.left, rect.bottom)
    }

    /**
     * Край выделения в координатах экрана: x берём из каретки (она знает про
     * выравнивание по ширине), y — из строки самого символа, чтобы на границе
     * строки ручка не уезжала на соседнюю.
     */
    fun edge(offset: Int, isEnd: Boolean): Rect {
        if (length <= 0) {
            return Rect(bounds.left, bounds.top, bounds.left, bounds.top)
        }
        val local = offset.coerceIn(0, length)
        val char = if (isEnd) (local - 1).coerceIn(0, length - 1) else local.coerceIn(0, length - 1)
        val line = layout.getLineForOffset(char)
        val x = layout.getCursorRect(local).left + if (justified) justifyShift(line, char) else 0f
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        return Rect(x, top, x, bottom).translate(bounds.left, bounds.top)
    }

    /**
     * Поправка на выравнивание по ширине: раскладка отдаёт позиции до
     * растягивания, а рисуется строка уже растянутой. Лишняя ширина делится
     * поровну между пробелами, поэтому символ сдвигается на долю пробелов до него.
     */
    private fun justifyShift(line: Int, char: Int): Float {
        if (line >= layout.lineCount - 1) return 0f
        val from = layout.getLineStart(line)
        val to = layout.getLineEnd(line, visibleEnd = true)
        if (to <= from) return 0f
        val extra = layout.size.width - layout.getLineRight(line)
        if (extra <= 0.5f) return 0f
        var total = 0
        var before = 0
        for (index in from until to) {
            if (text.getOrNull(index) == ' ') {
                total++
                if (index < char) before++
            }
        }
        if (total == 0) return 0f
        return extra * before / total
    }
}

/** Кэш видимых кусков текста: обновляется из [onTextLayout] и [onGloballyPositioned]. */
class BlockRegistry {
    private val blocks = HashMap<String, TextBlock>()
    private var sequence = 0L

    /**
     * Счётчик изменений: подписка на него в композиции заставляет ручки
     * пересчитываться, когда текст переразложился или страница сдвинулась.
     */
    var version by mutableIntStateOf(0)
        private set

    fun put(key: String, block: TextBlock) {
        block.seq = ++sequence
        blocks[key] = block
        version++
    }

    fun remove(key: String) {
        if (blocks.remove(key) != null) version++
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

    /** Край выделения в координатах экрана (если блок виден). */
    fun caret(chapter: Int, pos: TextPos, isEnd: Boolean): Rect? {
        // Берём самый свежий зарегистрированный кусок: устаревшие записи от
        // предыдущей страницы иначе дают смещённые ручки.
        val block = blocks.values
            .filter {
                it.chapter == chapter &&
                    it.segment == pos.segment &&
                    pos.offset >= it.charOffset &&
                    pos.offset <= it.charOffset + it.length
            }
            .maxByOrNull { it.seq } ?: return null
        return block.edge(pos.offset - block.charOffset, isEnd)
    }
}

/** Точки краёв выделения в координатах экрана: их пишут сами куски текста. */
class SelectionAnchors {
    var start by mutableStateOf<Offset?>(null)
    var end by mutableStateOf<Offset?>(null)

    fun clear() {
        start = null
        end = null
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
 * Границы предложения вокруг смещения [offset]: [first, last] включительно.
 * Работает по знакам конца предложения, учитывает сокращения и инициалы
 * («1892 г.», «А. П. Чехов»), иначе одно удержание выделяло бы пол-абзаца.
 */
fun sentenceRange(text: String, offset: Int): IntRange {
    if (text.isEmpty()) return 0..0
    val position = offset.coerceIn(0, text.lastIndex)

    var start = 0
    var index = position - 1
    while (index >= 0) {
        if (isSentenceEnd(text, index)) {
            start = index + 1
            break
        }
        index--
    }
    // Пропускаем пробелы после конца предыдущего предложения.
    while (start < text.length && text[start].isWhitespace()) {
        start++
    }

    var end = text.length
    index = position
    while (index < text.length) {
        if (isSentenceEnd(text, index)) {
            end = index + 1
            // Захватываем хвостовые кавычки/скобки и повторные знаки («?!», «…»).
            while (end < text.length &&
                (text[end] in "»“”\"')]" || text[end] == '.' || text[end] == '!'
                    || text[end] == '?' || text[end] == '…')
            ) {
                end++
            }
            break
        }
        index++
    }

    if (end <= start) {
        val first = start.coerceIn(0, text.lastIndex)
        return first..first
    }
    return start until end
}

/** Конец предложения в позиции [index]: знак препинания, а не сокращение. */
private fun isSentenceEnd(text: String, index: Int): Boolean {
    when (text[index]) {
        '!', '?', '…' -> return true
        '.' -> Unit
        else -> return false
    }
    // Многоточие: «..» — конец, но точку внутри «...» обработает последняя.
    if (index > 0 && text[index - 1] == '.') return true
    if (index + 1 < text.length && text[index + 1] == '.') return false

    var next = index + 1
    while (next < text.length && text[next].isWhitespace()) next++
    if (next >= text.length) return true
    val nextChar = text[next]
    // После конца предложения идёт заглавная, цифра, кавычка или тире.
    if (nextChar.isLowerCase()) return false

    var from = index - 1
    while (from >= 0 && (text[from].isLetter() || text[from] == '.')) from--
    val word = text.substring(from + 1, index)
    if (word.isEmpty()) return true
    // Инициалы: «А. П. Чехов», «М.А. Булгаков» — в том числе склеенные,
    // где слово перед точкой выглядит как «М.А» (одни заглавные по одной).
    val parts = word.split('.')
    if (parts.all { it.length == 1 && it[0].isUpperCase() }) return false
    // Склеенные сокращения: «т.д.», «т.п.», «г.г.».
    if (parts.size > 1 &&
        parts.all { it.length == 1 && it.lowercase() in SENTENCE_ABBREVIATIONS }
    ) {
        return false
    }
    if (word.lowercase() in SENTENCE_ABBREVIATIONS) return false
    return true
}

/** Сокращения, после которых точка не закрывает предложение. */
private val SENTENCE_ABBREVIATIONS = setOf(
    "г", "гг", "т", "д", "п", "е", "др", "проч", "проф", "акад", "доц",
    "рис", "стр", "см", "напр", "тыс", "млн", "млрд", "вв", "им", "ул",
    "кв", "руб", "коп", "англ", "лат", "нем", "фр", "ср", "св", "т.е",
)

/**
 * Долгое нажатие по тексту: 1 секунда — выделяется слово, ещё секунда
 * (итого 2) — всё предложение. Дальше протяжка расширяет выделение.
 * События при активном выделении поглощаются в Initial-проходе, поэтому
 * страница не листается, а лента не прокручивается.
 */
fun Modifier.textSelectionGestures(
    registry: BlockRegistry,
    origin: () -> Offset,
    selection: () -> ReaderSelection?,
    onSelect: (ReaderSelection?) -> Unit,
    onHold: () -> Unit = {},
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

        // 1. Держим палец WORD_HOLD_MS — выделяем слово под ним.
        // Любое движение за пределы slop — это прокрутка/листание, а не выделение.
        val slop = viewConfiguration.touchSlop
        var cancelled = false
        withTimeoutOrNull(WORD_HOLD_MS) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
                if ((change.position - down.position).getDistance() > slop) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
            }
        }
        if (cancelled) return@awaitEachGesture

        val root = origin() + down.position
        val block = registry.at(root) ?: return@awaitEachGesture
        // Пробел и знаки сами по себе не выделяем; если слова рядом нет — выходим.
        val word = block.wordAt(root) ?: return@awaitEachGesture
        var anchor = TextPos(block.segment, block.charOffset + word.first)
        onSelect(
            orderedSelection(
                block.chapter,
                anchor,
                TextPos(block.segment, block.charOffset + word.last),
            ),
        )
        onHold()

        // 2. Ещё секунда без движения — расширяем до предложения.
        var reason = HOLD_TIMEOUT
        withTimeoutOrNull(SENTENCE_HOLD_MS - WORD_HOLD_MS) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    reason = HOLD_RELEASED
                    return@withTimeoutOrNull
                }
                if (!change.pressed) {
                    // Палец отпущен уже после начала выделения: поглощаем событие,
                    // иначе листание сочтёт жест тапом и перевернёт страницу.
                    change.consume()
                    reason = HOLD_RELEASED
                    return@withTimeoutOrNull
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    reason = HOLD_MOVED
                    return@withTimeoutOrNull
                }
            }
        }
        if (reason == HOLD_RELEASED) return@awaitEachGesture
        if (reason == HOLD_TIMEOUT) {
            val sentence = sentenceRange(block.text, word.first)
            val from = TextPos(block.segment, block.charOffset + sentence.first)
            val to = TextPos(block.segment, block.charOffset + sentence.last + 1)
            onSelect(orderedSelection(block.chapter, from, to))
            // Дальше тянем уже от начала предложения.
            anchor = from
            onHold()
        }

        // 3. Протяжка: тянем второй конец выделения, можно через абзацы.
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

/** Держим палец столько — выделяется слово. */
private const val WORD_HOLD_MS = 1_000L

/** Держим палец столько — выделяется предложение. */
private const val SENTENCE_HOLD_MS = 2_000L

private const val HOLD_TIMEOUT = 0
private const val HOLD_RELEASED = 1
private const val HOLD_MOVED = 2

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
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val density = LocalDensity.current
    // Точки краёв берём из того же реестра текста, что и подсветку.
    @Suppress("UNUSED_VARIABLE") val registryVersion = registry.version
    val startPoint = registry.caret(selection.chapter, selection.start, isEnd = false)
        ?.let { Offset(it.left, it.bottom) }
    val endPoint = registry.caret(selection.chapter, selection.end, isEnd = true)
        ?.let { Offset(it.left, it.bottom) }

    Box(Modifier.fillMaxSize()) {
        val topAnchor = startPoint ?: endPoint
        val bottomAnchor = endPoint ?: startPoint

        // Панель рисуем первой: ручки должны быть поверх неё, иначе их
        // область касания перекрывается кнопками и за ручку не ухватиться.
        if (topAnchor != null) {
            var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
            val gap = with(density) { 8.dp.toPx() }
            // Верх области касания ручки поднимается на полвысоты над строкой.
            val handleLift = with(density) { HANDLE_TOUCH_HEIGHT.toPx() } / 2f
            val liftBelow = with(density) { HANDLE_HEIGHT.toPx() } + gap
            val limit = (screenWidth - toolbarSize.width - gap).coerceAtLeast(gap)
            val x = (topAnchor.x - toolbarSize.width / 2f).coerceIn(gap, limit)
            val above = topAnchor.y - handleLift - toolbarSize.height - gap
            val y = if (above > gap) {
                above
            } else {
                (bottomAnchor?.y ?: topAnchor.y) + liftBelow
            }
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
                onShare = onShare,
                onCopy = onCopy,
            )
        }

        // Обе ручки — под строкой, каплей вниз; рисуются поверх панели.
        startPoint?.let { point ->
            SelectionHandle(
                rootTip = point,
                screenOrigin = screenOrigin,
                onChange = onExtendStart,
            )
        }
        endPoint?.let { point ->
            SelectionHandle(
                rootTip = point,
                screenOrigin = screenOrigin,
                onChange = onExtendEnd,
            )
        }
    }
}

/**
 * Ручка выделения: капля под строкой, острие прижато к тексту.
 * Область касания — как у системных ручек: [HANDLE_TOUCH] при меньшем рисунке,
 * иначе за каплю невозможно ухватиться.
 */
@Composable
private fun SelectionHandle(
    rootTip: Offset,
    screenOrigin: Offset,
    onChange: (Offset) -> Unit,
) {
    val density = LocalDensity.current
    val touchWidth = with(density) { HANDLE_TOUCH_WIDTH.toPx() }
    val touchHeight = with(density) { HANDLE_TOUCH_HEIGHT.toPx() }
    val dropletWidth = with(density) { HANDLE_WIDTH.toPx() }
    val dropletHeight = with(density) { HANDLE_HEIGHT.toPx() }
    // Острие капли внутри области касания: по центру, у нижней кромки рисунка.
    val tipInBox = Offset(touchWidth / 2f, touchHeight - dropletHeight)
    val currentTip by rememberUpdatedState(rootTip)
    // Колбэк берём свежий: выделение меняется каждый кадр протяжки, а
    // pointerInput с ключом Unit иначе держал бы замыкание первого кадра.
    val currentOnChange by rememberUpdatedState(onChange)
    Box(
        Modifier
            .offset {
                IntOffset(
                    (rootTip.x - tipInBox.x - screenOrigin.x).roundToInt(),
                    (rootTip.y - tipInBox.y - screenOrigin.y).roundToInt(),
                )
            }
            .size(width = HANDLE_TOUCH_WIDTH, height = HANDLE_TOUCH_HEIGHT)
            .pointerInput(Unit) {
                // Тянем сразу, без slop: палец и так стоит на ручке.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // Смещение пальца относительно острия в момент захвата:
                    // дальше ведём острие за пальцем с этим же смещением,
                    // поэтому выделение не прыгает при захвате.
                    val grab = tipInBox - down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        // Абсолютная позиция пальца: верхний левый угол ручки
                        // берём из текущей позиции острия (она едет за выделением).
                        val nodeOrigin = currentTip - tipInBox
                        currentOnChange(nodeOrigin + change.position + grab)
                    }
                }
            },
    ) {
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            val radius = dropletWidth / 2f
            val centerY = tipInBox.y + dropletHeight - radius
            // Острие капли смотрит вверх, к строке.
            val tipPoint = Path().apply {
                moveTo(tipInBox.x, tipInBox.y)
                lineTo(tipInBox.x - radius * 0.62f, centerY)
                lineTo(tipInBox.x + radius * 0.62f, centerY)
                close()
            }
            drawPath(tipPoint, color)
            drawCircle(color, radius = radius, center = Offset(tipInBox.x, centerY))
        }
    }
}

/** Область касания ручки: как minimum touch target у системных ручек. */
private val HANDLE_TOUCH_WIDTH = 36.dp
private val HANDLE_TOUCH_HEIGHT = 48.dp
private val HANDLE_WIDTH = 18.dp
private val HANDLE_HEIGHT = 24.dp

@Composable
private fun SelectionToolbar(
    modifier: Modifier = Modifier,
    onQuote: () -> Unit,
    onNote: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionAction(stringResource(R.string.quote_action), onQuote)
            SelectionAction(stringResource(R.string.note_action), onNote)
            SelectionAction(stringResource(R.string.annotations_share), onShare)
            SelectionAction(stringResource(R.string.annotations_copy), onCopy)
        }
    }
}

@Composable
private fun SelectionAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}
