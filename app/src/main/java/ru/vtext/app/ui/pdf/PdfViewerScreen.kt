package ru.vtext.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.ui.reader.ReaderThemeStyle
import ru.vtext.app.ui.reader.toStyle

/** Минимальный и максимальный зум просмотра оригинала PDF. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f

/** Битмап рисуем не крупнее этого множителя: выше — только растягиваем, экономим память. */
private const val MAX_RENDER_SCALE = 2f

/** Рендер страниц PDF «как в файле» с кэшем битмапов. */
private class PdfPageRenderer(file: File) : Closeable {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    val pageCount: Int = renderer.pageCount
    private val aspects = HashMap<Int, Float>()

    private val cache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Отношение ширины страницы к высоте — нужно для стабильной высоты до отрисовки. */
    @Synchronized
    fun aspect(index: Int): Float {
        aspects[index]?.let { return it }
        val ratio = runCatching {
            val page = renderer.openPage(index)
            try {
                page.width.toFloat() / page.height.toFloat()
            } finally {
                page.close()
            }
        }.getOrDefault(0.72f)
        aspects[index] = ratio
        return ratio
    }

    @Synchronized
    fun page(index: Int, widthPx: Int): Bitmap? {
        if (index !in 0 until pageCount || widthPx <= 0) return null
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return runCatching {
            val page = renderer.openPage(index)
            try {
                val height = (page.height.toFloat() * widthPx / page.width)
                    .toInt()
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            } finally {
                page.close()
            }
        }.getOrNull()
    }

    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * Оригинальный вид PDF: страницы рендерятся как в файле. Щипок двумя пальцами
 * меняет масштаб, кнопка «По ширине» фиксирует страницу по ширине экрана.
 * Переключатель «Текст» возвращает в читалку с извлечённым текстом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    bookId: String,
    startPage: Int = -1,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val file = remember(bookId) {
        container.repository.bookDir(bookId)
            .listFiles()
            ?.firstOrNull { it.name.equals("source.pdf", ignoreCase = true) }
    }
    val renderer = remember(bookId, file?.absolutePath) {
        file?.let { runCatching { PdfPageRenderer(it) }.getOrNull() }
    }
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }

    val prefs = remember { context.getSharedPreferences("pdf_view", android.content.Context.MODE_PRIVATE) }
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var horizontalJob by remember { mutableStateOf<Job?>(null) }
    val currentPage = listState.firstVisibleItemIndex + 1

    // Масштаб общий для всех страниц и не сбрасывается при прокрутке.
    var scale by rememberSaveable(bookId) {
        mutableStateOf(prefs.getFloat("scale_$bookId", 1f))
    }
    // Фиксация: текущий масштаб и горизонтальный сдвиг остаются прежними,
    // пока не снять замок.
    var zoomLocked by rememberSaveable(bookId) {
        mutableStateOf(prefs.getBoolean("locked_$bookId", false))
    }
    val snackbar = remember { SnackbarHostState() }

    // Цвета — как в читалке: тема из «Оформления текста» (бумага/сепия/ночь…).
    val readerStyle = remember(bookId) { container.readerSettings.toStyle() }
    DisposableEffect(readerStyle.followSystemBrightness, readerStyle.brightness) {
        val window = (context as? android.app.Activity)?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = if (readerStyle.followSystemBrightness) {
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                readerStyle.brightness
            }
            window.attributes = attrs
        }
        onDispose {
            if (window != null && previous != null) {
                val attrs = window.attributes
                attrs.screenBrightness = previous
                window.attributes = attrs
            }
        }
    }

    // Горизонтальный сдвиг восстанавливаем один раз после раскладки: до неё
    // максимальная прокрутка ещё нулевая.
    var restoreDone by remember { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        val saved = prefs.getInt("hscroll_$bookId", 0)
        if (saved > 0) {
            // Ждём раскладку с ненулевой прокруткой, но не бесконечно: при масштабе
            // 100 % диапазона может не быть вовсе (например, остался старый сдвиг).
            withTimeoutOrNull(1500) {
                snapshotFlow { horizontalScroll.maxValue }.first { it > 0 }
                horizontalScroll.scrollTo(saved.coerceAtMost(horizontalScroll.maxValue))
            }
        }
        restoreDone = true
    }

    // Масштаб, замок и сдвиг переживают уход из просмотра. Пишем с задержкой,
    // чтобы не трогать настройки на каждом кадре щипка.
    LaunchedEffect(bookId) {
        snapshotFlow { Triple(scale, zoomLocked, horizontalScroll.value) }
            .collectLatest { (savedScale, savedLock, savedShift) ->
                if (!restoreDone) return@collectLatest
                delay(300)
                prefs.edit()
                    .putFloat("scale_$bookId", savedScale)
                    .putBoolean("locked_$bookId", savedLock)
                    .putInt("hscroll_$bookId", savedShift)
                    .apply()
            }
    }

    // Глава из читалки знает свою страницу — открываем оригинал ровно на ней.
    // Если страницы нет, возвращаемся туда, где листали в прошлый раз.
    LaunchedEffect(renderer, startPage) {
        val count = renderer?.pageCount ?: return@LaunchedEffect
        val target = when {
            startPage in 0 until count -> startPage
            else -> prefs.getInt(bookId, 0).coerceIn(0, (count - 1).coerceAtLeast(0))
        }
        if (target > 0) listState.scrollToItem(target)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { index -> prefs.edit().putInt(bookId, index).apply() }
    }


    ReaderThemeStyle(readerStyle) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.pdf_original),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (renderer != null && scale > MIN_SCALE) {
                                Text(
                                    text = stringResource(
                                        R.string.pdf_zoom_percent,
                                        (scale * 100).roundToInt(),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            zoomLocked = !zoomLocked
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(
                                        if (zoomLocked) {
                                            R.string.pdf_zoom_locked_hint
                                        } else {
                                            R.string.pdf_zoom_unlocked_hint
                                        },
                                    ),
                                )
                            }
                        },
                    ) {
                        Icon(
                            if (zoomLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = stringResource(
                                if (zoomLocked) R.string.pdf_zoom_unlock
                                else R.string.pdf_zoom_lock,
                            ),
                            tint = if (zoomLocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            val count = renderer?.pageCount ?: 0
            if (count > 1) {
                PdfFooter(
                    page = currentPage.coerceIn(1, count),
                    pageCount = count,
                    onSeek = { fraction ->
                        val index = ((count - 1) * fraction)
                            .roundToInt()
                            .coerceIn(0, count - 1)
                        scope.launch { listState.scrollToItem(index) }
                    },
                )
            }
        },
    ) { padding ->
        if (renderer == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.pdf_open_error),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            val viewportPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val contentWidth = maxWidth * scale
            // Битмап крупнее двух ширин экрана не нужен: дальше он только растягивается.
            val renderWidthPx = (viewportPx * minOf(scale, MAX_RENDER_SCALE))
                .toInt()
                .coerceAtLeast(1)

            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScroll, enabled = !zoomLocked)
                    .pointerInput(renderer) {
                        detectTapGestures(
                            onDoubleTap = doubleTap@{ offset ->
                                if (zoomLocked) return@doubleTap
                                val old = scale
                                val next = if (old > MIN_SCALE) MIN_SCALE else 2f
                                val factor = next / old
                                scale = next
                                listState.pinnedScroll(offset.y, factor)?.let { (index, offsetY) ->
                                    listState.requestScrollToItem(index, offsetY)
                                }
                                val contentX = horizontalScroll.value + offset.x
                                val targetX = if (next <= MIN_SCALE) {
                                    0
                                } else {
                                    (contentX * factor - offset.x).roundToInt().coerceAtLeast(0)
                                }
                                horizontalJob?.cancel()
                                horizontalJob = scope.launch { horizontalScroll.scrollTo(targetX) }
                            },
                        )
                    }
                    .pointerInput(renderer) {
                        awaitEachGesture {
                            // Слушаем в Initial-проходе и потребляем события с двумя
                            // пальцами: иначе прокрутка списка (дочерний узел) успевает
                            // сдвинуть страницу, пока идёт щипок.
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val zoom = if (zoomLocked) 1f else event.calculateZoom()
                                    if (zoom.isFinite() && zoom != 1f) {
                                        val old = scale
                                        val next = (scale * zoom)
                                            .coerceIn(MIN_SCALE, MAX_SCALE)
                                        if (next != old) {
                                            val focus = event.calculateCentroid()
                                            val factor = next / old
                                            scale = next
                                            listState.pinnedScroll(focus.y, factor)
                                                ?.let { (index, offsetY) ->
                                                    listState.requestScrollToItem(index, offsetY)
                                                }
                                            val contentX = horizontalScroll.value + focus.x
                                            val targetX = if (next <= MIN_SCALE) {
                                                0
                                            } else {
                                                (contentX * factor - focus.x)
                                                    .roundToInt()
                                                    .coerceAtLeast(0)
                                            }
                                            horizontalJob?.cancel()
                                            horizontalJob = scope.launch {
                                                horizontalScroll.scrollTo(targetX)
                                            }
                                        }
                                    }
                                    pressed.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(renderer.pageCount) { index ->
                        PdfPage(renderer = renderer, index = index, widthPx = renderWidthPx)
                    }
                }
            }
        }
        }
    }
}

/**
 * Подвал оригинала PDF: полоса прокрутки с точкой-ползунком и счётчик
 * «N из M стр» под ней — как полоса и счётчик в читалке.
 */
@Composable
private fun PdfFooter(
    page: Int,
    pageCount: Int,
    onSeek: (Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    // Пока тянем — показываем долю пальца; когда страница обновится, снимаем.
    LaunchedEffect(page) {
        if (!scrubbing) scrubFraction = null
    }
    val fraction = scrubFraction
        ?: if (pageCount <= 1) 0f else (page - 1).toFloat() / (pageCount - 1)
    val shownPage = if (scrubFraction != null) {
        (fraction * (pageCount - 1)).roundToInt() + 1
    } else {
        page
    }
    val seek: (Float) -> Unit = { raw ->
        val value = raw.coerceIn(0f, 1f)
        scrubFraction = value
        onSeek(value)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            // Тап по пустому месту подвала не должен уходить в страницу.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        HorizontalDivider(color = colors.outlineVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp)
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> seek(offset.x / size.width) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            seek(offset.x / size.width)
                        },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        change.consume()
                        seek(change.position.x / size.width)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackWidth = 6.dp.toPx()
                val dotRadius = 6.dp.toPx()
                val centerY = size.height / 2f
                val startX = dotRadius
                val endX = size.width - dotRadius
                drawLine(
                    color = colors.outlineVariant,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
                val dotX = startX + (endX - startX) * fraction
                if (fraction > 0f) {
                    drawLine(
                        color = colors.primary,
                        start = Offset(startX, centerY),
                        end = Offset(dotX, centerY),
                        strokeWidth = trackWidth,
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(
                    color = colors.primary,
                    radius = dotRadius,
                    center = Offset(dotX, centerY),
                )
            }
        }
        Text(
            text = stringResource(R.string.pdf_page_short, shownPage, pageCount),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun PdfPage(renderer: PdfPageRenderer, index: Int, widthPx: Int) {
    // Старый битмап держим, пока не готов новый: при зуме страница не прыгает.
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    val aspect by produceState(initialValue = 0.72f, index) {
        value = withContext(Dispatchers.IO) { renderer.aspect(index) }
    }
    LaunchedEffect(index, widthPx) {
        val next = withContext(Dispatchers.IO) { renderer.page(index, widthPx) }
        if (next != null) bitmap = next
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color.White),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/**
 * Куда прокрутить список, чтобы точка внутри страницы под пальцем осталась на месте
 * при изменении масштаба в [factor] раз. Возвращает индекс элемента и смещение.
 */
private fun LazyListState.pinnedScroll(focusY: Float, factor: Float): Pair<Int, Int>? {
    val info = layoutInfo.visibleItemsInfo
    if (info.isEmpty()) return null
    val item = info.firstOrNull { focusY >= it.offset && focusY < it.offset + it.size }
        ?: info.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - focusY) }
        ?: return null
    val local = (focusY - item.offset).coerceIn(0f, item.size.toFloat())
    return item.index to (local * factor - focusY).roundToInt()
}
