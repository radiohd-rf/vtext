package ru.vtext.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vtext.app.R
import ru.vtext.app.VtextApp

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

    private val cache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
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
    onText: () -> Unit,
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
    val currentPage = listState.firstVisibleItemIndex + 1

    // Масштаб общий для всех страниц и не сбрасывается при прокрутке.
    var scale by rememberSaveable(bookId) { mutableStateOf(1f) }
    var fitWidth by rememberSaveable(bookId) { mutableStateOf(true) }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.pdf_original),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (renderer != null) {
                            val zoom = if (fitWidth) "" else {
                                " · " + stringResource(
                                    R.string.pdf_zoom_percent,
                                    (scale * 100).roundToInt(),
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.pdf_page_of,
                                    currentPage,
                                    renderer.pageCount,
                                ) + zoom,
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
                            scale = 1f
                            fitWidth = true
                            scope.launch { horizontalScroll.scrollTo(0) }
                        },
                    ) {
                        Icon(
                            Icons.Filled.FitScreen,
                            contentDescription = stringResource(R.string.pdf_fit_width),
                            tint = if (fitWidth) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    TextButton(onClick = onText) {
                        Icon(
                            Icons.Filled.TextFields,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(stringResource(R.string.pdf_text_mode))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
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
                    .horizontalScroll(horizontalScroll)
                    .pointerInput(renderer) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (scale > MIN_SCALE) {
                                    scale = MIN_SCALE
                                    fitWidth = true
                                    scope.launch { horizontalScroll.scrollTo(0) }
                                } else {
                                    scale = 2f
                                    fitWidth = false
                                    val target = (offset.x * (scale - 1)).roundToInt()
                                    scope.launch {
                                        horizontalScroll.scrollTo(target.coerceAtLeast(0))
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(renderer) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom.isFinite() && zoom != 1f) {
                                        val old = scale
                                        val next = (scale * zoom)
                                            .coerceIn(MIN_SCALE, MAX_SCALE)
                                        if (next != old) {
                                            val focus = event.calculateCentroid().x
                                            val contentX = horizontalScroll.value + focus
                                            scale = next
                                            fitWidth = false
                                            if (next > MIN_SCALE) {
                                                scope.launch {
                                                    horizontalScroll.scrollTo(
                                                        (contentX * next / old - focus)
                                                            .roundToInt()
                                                            .coerceAtLeast(0),
                                                    )
                                                }
                                            } else {
                                                scope.launch { horizontalScroll.scrollTo(0) }
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

@Composable
private fun PdfPage(renderer: PdfPageRenderer, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
        value = withContext(Dispatchers.IO) { renderer.page(index, widthPx) }
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
                    .background(Color.White),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
