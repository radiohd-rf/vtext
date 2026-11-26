package ru.vtext.app.ui.reader

import android.content.Context
import androidx.compose.foundation.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import ru.vtext.app.domain.notes.NoteRef
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.data.Bookmark
import ru.vtext.app.data.ReaderSettingsStore
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import android.app.Activity
import android.content.ContextWrapper

/**
 * Обычная читалка: прокрутка, темы, типографика, оглавление и закладки.
 * Позиция общая с TTS-плеером — «Слушать» продолжает с текущего абзаца.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onListen: (chapterIndex: Int, segmentIndex: Int) -> Unit,
    onOpenPdf: (page: Int) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModel.factory(
            bookId = bookId,
            repository = container.repository,
            progressStore = container.progressStore,
            bookmarks = container.bookmarks,
            settings = container.readerSettings,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val style = state.style
    val colors = style.colors()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var noteSheet by remember { mutableStateOf<NoteSheetState?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentItem by remember { mutableStateOf<ReaderItem?>(null) }
    // Интерфейс читалки лежит поверх текста: тап по центру скрывает его.
    var uiVisible by rememberSaveable { mutableStateOf(true) }
    // Высота подвала: панель примечания всегда должна быть выше него.
    var footerHeightPx by remember { mutableStateOf(0) }
    // Постраничный режим: страницы текущей главы и индекс видимой.
    val measurer = rememberTextMeasurer()
    var pages by remember { mutableStateOf<List<ReaderPage>>(emptyList()) }
    var pagesChapter by remember { mutableStateOf(-1) }
    var pageIndex by remember { mutableStateOf(0) }
    var wantLastPage by remember { mutableStateOf(false) }
    var paginatedHeight by remember { mutableStateOf(0) }
    // Якорь страницы: абзац и смещение в нём, чтобы при пересборке не терять место.
    var anchorChapter by remember { mutableStateOf(-1) }
    var anchorSegment by remember { mutableStateOf(-1) }
    var anchorOffset by remember { mutableStateOf(0) }
    // Ручная пересборка страниц (перемотка таймлайном).
    var pagingGeneration by remember { mutableStateOf(0) }
    var paginatedGeneration by remember { mutableStateOf(0) }
    // Сдвиг страницы при пролистывании (пиксели) и текущая анимация доводки.
    var pageOffsetX by remember { mutableStateOf(0f) }
    var pageTurnJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(style.followSystemBrightness, style.brightness) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = if (style.followSystemBrightness) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                style.brightness
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

    LaunchedEffect(state.items, state.startIndex) {
        if (style.scrollMode && state.items.isNotEmpty()) {
            listState.scrollToItem(state.startIndex)
        }
    }

    LaunchedEffect(listState, state.items, style.scrollMode) {
        // В постраничном режиме позицию задают страницы, а не прокрутка.
        if (!style.scrollMode) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { index ->
                delay(350)
                val item = state.items.getOrNull(index)
                currentItem = item
                viewModel.onPosition(item, index)
            }
    }

    // Возврат из постраничного режима — прокрутка к текущему абзацу.
    LaunchedEffect(style.scrollMode) {
        if (style.scrollMode && state.items.isNotEmpty()) {
            listState.scrollToItem(
                viewModel.itemIndexFor(state.chapterIndex, state.segmentIndex),
            )
        }
    }

    ReaderThemeStyle(style) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val contentTop = insets.calculateTopPadding() + 4.dp
            val contentBottom = insets.calculateBottomPadding() + 28.dp
            val widthPx = constraints.maxWidth -
                with(density) { insets.calculateLeftPadding(layoutDirection).roundToPx() } -
                with(density) { insets.calculateRightPadding(layoutDirection).roundToPx() }
            val contentHeightPx = with(density) {
                (constraints.maxHeight - contentTop.roundToPx() - contentBottom.roundToPx())
                    .coerceAtLeast(1)
            }

            // Страницы главы пересобираются при смене главы, шрифта, размера
            // экрана и режима чтения; позиция остаётся на текущем абзаце.
            LaunchedEffect(
                state.chapterIndex,
                state.items.size,
                style.font,
                style.fontSizeSp,
                style.lineSpacing.multiplier,
                style.margins.dp,
                style.scrollMode,
                state.verse,
                widthPx,
                contentHeightPx,
                pagingGeneration,
            ) {
                if (style.scrollMode) {
                    pages = emptyList()
                    pagesChapter = -1
                    return@LaunchedEffect
                }
                // Пока двигают ползунок шрифта, страницы не пересобираем на каждый
                // шаг; смена высоты области (интерфейс, поворот) и перемотка — сразу.
                val heightChanged = paginatedHeight != contentHeightPx ||
                    paginatedGeneration != pagingGeneration
                if (anchorChapter != state.chapterIndex) {
                    anchorChapter = state.chapterIndex
                    anchorSegment = state.segmentIndex
                    anchorOffset = 0
                }
                if (pagesChapter != state.chapterIndex) {
                    pages = emptyList()
                } else if (!heightChanged && pages.isNotEmpty()) {
                    delay(120)
                }
                paginatedHeight = contentHeightPx
                paginatedGeneration = pagingGeneration
                // Страницы собираются от текущего места, поэтому верхняя строка
                // остаётся на месте при смене шрифта или повороте.
                val built = paginate(
                    items = state.items,
                    chapterIndex = state.chapterIndex,
                    style = style,
                    verse = state.verse,
                    measurer = measurer,
                    widthPx = widthPx,
                    heightPx = contentHeightPx,
                    density = density,
                    startSegment = anchorSegment,
                    startOffset = anchorOffset,
                )
                pages = built
                pagesChapter = state.chapterIndex
                // Недокрученная анимация не должна сдвигать новую страницу.
                pageOffsetX = 0f
                pageIndex = if (wantLastPage) {
                    wantLastPage = false
                    built.lastIndex.coerceAtLeast(0)
                } else {
                    0
                }
                built.getOrNull(pageIndex)?.blocks?.firstOrNull()?.let { block ->
                    anchorChapter = block.chapterIndex
                    anchorSegment = block.segmentIndex ?: -1
                    anchorOffset = block.charOffset
                }
            }

            fun firstBlock(page: ReaderPage?): ReaderPageBlock? =
                page?.blocks?.firstOrNull { !it.isHeader } ?: page?.blocks?.firstOrNull()

            fun applyPagePosition(page: ReaderPage?) {
                val block = firstBlock(page) ?: return
                anchorChapter = block.chapterIndex
                anchorSegment = block.segmentIndex ?: -1
                anchorOffset = block.charOffset
                currentItem = state.items.firstOrNull {
                    it is ReaderItem.Paragraph &&
                        it.chapterIndex == block.chapterIndex &&
                        it.segmentIndex == block.segmentIndex
                }
                viewModel.selectPosition(block.chapterIndex, block.segmentIndex ?: 0)
            }

            fun commitNextPage() {
                if (pages.isEmpty()) return
                if (pageIndex < pages.lastIndex) {
                    pageIndex += 1
                    applyPagePosition(pages.getOrNull(pageIndex))
                } else if (state.chapterIndex < state.chapterCount - 1) {
                    pages = emptyList()
                    pageIndex = 0
                    anchorChapter = state.chapterIndex + 1
                    anchorSegment = 0
                    anchorOffset = 0
                    viewModel.selectPosition(state.chapterIndex + 1, 0)
                }
            }

            fun commitPrevPage() {
                if (pages.isEmpty()) return
                if (pageIndex > 0) {
                    pageIndex -= 1
                    applyPagePosition(pages.getOrNull(pageIndex))
                    return
                }
                // Список мог начинаться с текущего места — пересобираем главу
                // с начала и встаём на страницу перед текущей.
                val full = paginate(
                    items = state.items,
                    chapterIndex = state.chapterIndex,
                    style = style,
                    verse = state.verse,
                    measurer = measurer,
                    widthPx = widthPx,
                    heightPx = contentHeightPx,
                    density = density,
                )
                val anchorPage = pageIndexFor(full, anchorSegment, anchorOffset)
                // Заголовок главы в начале страницы не считается текстом:
                // сравниваем с первым абзацем страницы.
                val pageStart = full.getOrNull(anchorPage)?.blocks
                    ?.firstOrNull { it.segmentIndex != null }
                val anchorAtPageStart = pageStart != null &&
                    (pageStart.segmentIndex ?: -1) == anchorSegment &&
                    pageStart.charOffset == anchorOffset
                if (anchorPage >= 0 && !anchorAtPageStart) {
                    // Текущая страница началась с середины полной страницы —
                    // показываем её с начала и запоминаем это место.
                    pages = full
                    pageIndex = anchorPage
                    applyPagePosition(full.getOrNull(pageIndex))
                    full.getOrNull(pageIndex)?.blocks?.firstOrNull()?.let { block ->
                        anchorChapter = block.chapterIndex
                        anchorSegment = block.segmentIndex ?: 0
                        anchorOffset = block.charOffset
                    }
                } else if (anchorPage > 0) {
                    pages = full
                    pageIndex = anchorPage - 1
                    applyPagePosition(full.getOrNull(pageIndex))
                } else if (state.chapterIndex > 0) {
                    pages = emptyList()
                    pageIndex = 0
                    anchorChapter = state.chapterIndex - 1
                    anchorSegment = 0
                    anchorOffset = 0
                    wantLastPage = true
                    viewModel.selectPosition(state.chapterIndex - 1, 0)
                }
            }

            fun hasNeighbor(direction: Int): Boolean =
                pages.getOrNull(pageIndex + direction) != null

            /** Листание тапом: страница уезжает целиком, затем меняется номер. */
            fun turnPage(direction: Int) {
                val width = widthPx.toFloat()
                if (!hasNeighbor(direction)) {
                    if (direction > 0) commitNextPage() else commitPrevPage()
                    return
                }
                pageTurnJob?.cancel()
                pageTurnJob = scope.launch {
                    animate(
                        initialValue = pageOffsetX,
                        targetValue = -direction * width,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                    ) { value, _ -> pageOffsetX = value }
                    if (direction > 0) commitNextPage() else commitPrevPage()
                    pageOffsetX = 0f
                }
            }

            /** Отпускание пальца: доводим страницу или возвращаем на место. */
            fun settleTurn(offset: Float, velocityX: Float, width: Float) {
                val flip = abs(offset) > width * 0.25f || abs(velocityX) > width * 2.5f
                val direction = if (offset < 0f || (offset == 0f && velocityX < 0f)) 1 else -1
                pageTurnJob?.cancel()
                pageTurnJob = scope.launch {
                    if (flip && !hasNeighbor(direction)) {
                        pageOffsetX = 0f
                        if (direction > 0) commitNextPage() else commitPrevPage()
                        return@launch
                    }
                    val target = if (flip) -direction * width else 0f
                    animate(
                        initialValue = pageOffsetX,
                        targetValue = target,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                    ) { value, _ -> pageOffsetX = value }
                    if (flip) {
                        if (direction > 0) commitNextPage() else commitPrevPage()
                        pageOffsetX = 0f
                    }
                }
            }

            // Первый абзац видимой страницы — по нему ставим закладку.
            LaunchedEffect(pages, pageIndex) {
                val page = pages.getOrNull(pageIndex) ?: return@LaunchedEffect
                val block = firstBlock(page) ?: return@LaunchedEffect
                currentItem = state.items.firstOrNull {
                    it is ReaderItem.Paragraph &&
                        it.chapterIndex == block.chapterIndex &&
                        it.segmentIndex == block.segmentIndex
                }
            }

            val onNote: (String) -> Unit = { label ->
                // Метка сноски страницы — «p10-1», в заголовке показываем номер.
                val display = if (label.contains('-')) label.substringAfterLast('-') else label
                noteSheet = NoteSheetState(display, viewModel.noteFor(label))
            }
            val percent = state.book?.let {
                ru.vtext.app.domain.bookPercent(
                    it.chapters,
                    state.chapterIndex,
                    state.segmentIndex,
                )
            } ?: 0
            val onSeek: (Float) -> Unit = { fraction ->
                val lastIndex = state.items.lastIndex
                if (lastIndex >= 0) {
                    val index = (lastIndex * fraction.coerceIn(0f, 1f))
                        .roundToInt()
                        .coerceIn(0, lastIndex)
                    val item = state.items.getOrNull(index)
                    if (item != null) {
                        currentItem = item
                        val chapter = item.chapterIndex
                        val segment = (item as? ReaderItem.Paragraph)?.segmentIndex ?: 0
                        viewModel.selectPosition(chapter, segment)
                        if (style.scrollMode) {
                            scope.launch { listState.scrollToItem(index) }
                        } else {
                            anchorChapter = chapter
                            anchorSegment = segment
                            anchorOffset = 0
                            pagingGeneration += 1
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .pointerInput(style, widthPx, contentHeightPx, uiVisible) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (style.scrollMode) {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val width = size.width.toFloat()
                                    val x = up.position.x
                                    if (uiVisible) {
                                        if (x in width * 0.3f..width * 0.7f) uiVisible = false
                                    } else {
                                        uiVisible = true
                                    }
                                }
                                return@awaitEachGesture
                            }

                            // Постранично: страница едет за пальцем.
                            var dragging = false
                            var dragBase = 0f
                            var dragX = 0f
                            var upX = down.position.x
                            val slop = viewConfiguration.touchSlop
                            val tracker = VelocityTracker()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) {
                                    upX = change.position.x
                                    break
                                }
                                if (!dragging) {
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (abs(dx) > slop || abs(dy) > slop) {
                                        dragging = true
                                        dragBase = pageOffsetX
                                        pageTurnJob?.cancel()
                                    }
                                }
                                if (dragging) {
                                    dragX += change.positionChange().x
                                    val direction = if (dragX < 0) 1 else -1
                                    // За краем главы — тянется с сопротивлением.
                                    val value = if (hasNeighbor(direction)) dragX else dragX * 0.25f
                                    pageOffsetX = dragBase + value
                                    change.consume()
                                }
                            }
                            if (!dragging) {
                                val width = size.width.toFloat()
                                when {
                                    upX < width * 0.3f -> turnPage(-1)
                                    upX > width * 0.7f -> turnPage(1)
                                    else -> uiVisible = !uiVisible
                                }
                            } else {
                                settleTurn(
                                    offset = pageOffsetX,
                                    velocityX = tracker.calculateVelocity().x,
                                    width = size.width.toFloat(),
                                )
                            }
                        }
                    },
            ) {
                when {
                    state.loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.reader_parsing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.muted,
                            )
                        }
                    }
                    state.error != null || state.book == null -> Text(
                        text = state.error ?: stringResource(R.string.book_not_found),
                        modifier = Modifier.padding(24.dp),
                        color = colors.muted,
                    )
                    style.scrollMode -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                    ) {
                        itemsIndexed(
                            items = state.items,
                            key = { _, item -> itemKey(item) },
                        ) { _, item ->
                            when (item) {
                                is ReaderItem.Header -> ReaderHeader(item.title, style)
                                is ReaderItem.Paragraph -> ReaderParagraph(
                                    text = item.text,
                                    notes = item.notes,
                                    style = style,
                                    verse = state.verse,
                                    onNote = onNote,
                                )
                                is ReaderItem.Image -> ReaderImageItem(item, style)
                            }
                        }
                    }
                    else -> {
                        val current = pages.getOrNull(pageIndex)
                        if (current != null) {
                            val pageWidth = widthPx.toFloat()
                            Box(Modifier.fillMaxSize()) {
                                if (pageOffsetX > 0f) {
                                    pages.getOrNull(pageIndex - 1)?.let { prev ->
                                        PageLayer(
                                            page = prev,
                                            style = style,
                                            offsetX = -pageWidth + pageOffsetX,
                                            onNote = onNote,
                                        )
                                    }
                                } else if (pageOffsetX < 0f) {
                                    pages.getOrNull(pageIndex + 1)?.let { next ->
                                        PageLayer(
                                            page = next,
                                            style = style,
                                            offsetX = pageWidth + pageOffsetX,
                                            onNote = onNote,
                                        )
                                    }
                                }
                                PageLayer(current, style, pageOffsetX, onNote)
                            }
                        }
                    }
                }
            }

            if (uiVisible) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(colors.background)
                        // Пустое место шапки не должно скрывать интерфейс.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = state.book?.title.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                            if (state.book?.format == ru.vtext.app.domain.model.BookFormat.PDF) {
                                IconButton(onClick = {
                                    val page = state.book?.chapters
                                        ?.getOrNull(state.chapterIndex)?.page ?: -1
                                    onOpenPdf(page)
                                }) {
                                    Icon(
                                        Icons.Filled.PictureAsPdf,
                                        contentDescription = stringResource(R.string.pdf_original),
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.background,
                            titleContentColor = colors.text,
                            actionIconContentColor = colors.text,
                            navigationIconContentColor = colors.text,
                        ),
                    )
                }
            }

            if (uiVisible) {
                ReaderFooter(
                    chapterTitle = state.chapterTitle,
                    percent = percent,
                    onSeek = onSeek,
                    onToc = { showToc = true },
                    onAppearance = { showSettings = true },
                    onBookmarks = { showBookmarks = true },
                    onListen = { onListen(state.chapterIndex, state.segmentIndex) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { footerHeightPx = it.height },
                )
            }

            // Панель примечания — от низа экрана, поверх подвала.
            noteSheet?.let { sheet ->
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f))
                            .pointerInput(Unit) {
                                detectTapGestures { noteSheet = null }
                            },
                    )
                    NotePanel(
                        state = sheet,
                        onDismiss = { noteSheet = null },
                        // Всегда выше подвала, иначе у короткой сноски он торчит сверху.
                        minHeight = with(LocalDensity.current) { footerHeightPx.toDp() } + 8.dp,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        if (showToc && state.book != null) {
            ReaderTocSheet(
                chapters = state.book!!.chapters.map { it.title to it.depth },
                current = state.chapterIndex,
                onSelect = { index ->
                    showToc = false
                    viewModel.selectPosition(index, 0)
                    scope.launch {
                        listState.animateScrollToItem(viewModel.itemIndexForChapter(index))
                    }
                },
                onDismiss = { showToc = false },
            )
        }

        if (showBookmarks) {
            ReaderBookmarksSheet(
                bookmarks = state.bookmarks,
                onAdd = { viewModel.addBookmark(currentItem) },
                onOpen = { bookmark ->
                    showBookmarks = false
                    viewModel.selectPosition(bookmark.chapterIndex, bookmark.segmentIndex)
                    scope.launch {
                        listState.animateScrollToItem(
                            viewModel.itemIndexFor(bookmark.chapterIndex, bookmark.segmentIndex),
                        )
                    }
                },
                onDelete = viewModel::removeBookmark,
                onDismiss = { showBookmarks = false },
            )
        }

        if (showSettings) {
            ReaderSettingsSheet(
                style = style,
                onChange = viewModel::setStyle,
                onDismiss = { showSettings = false },
            )
        }
    }
}

private fun headerTextStyle(style: ReaderStyle): TextStyle = TextStyle(
    fontFamily = style.font.family,
    fontWeight = FontWeight.SemiBold,
    fontSize = (style.fontSizeSp * 1.3f).sp,
    lineHeight = (style.fontSizeSp * 1.3f * 1.3f).sp,
)

private fun bodyTextStyle(style: ReaderStyle): TextStyle = TextStyle(
    fontFamily = style.font.family,
    fontSize = style.fontSizeSp.sp,
    lineHeight = (style.fontSizeSp * style.lineSpacing.multiplier).sp,
)

/** Кусок текста на странице: заголовок или абзац (возможно, обрезанный). */
private data class ReaderPageBlock(
    val text: String,
    val isHeader: Boolean,
    val chapterIndex: Int,
    val segmentIndex: Int?,
    /** Блок начинает элемент книги — по нему восстанавливаем позицию. */
    val startsItem: Boolean,
    val topDp: Dp,
    val bottomDp: Dp,
    /** Смещение куска внутри исходного абзаца. */
    val charOffset: Int = 0,
    /** Картинка: путь к файлу, если блок — изображение. */
    val imagePath: String? = null,
    /** Высота картинки в пикселях (для вёрстки страницы). */
    val imageHeight: Int = 0,
    /** Ссылки на примечания внутри текста блока. */
    val notes: List<NoteSpan> = emptyList(),
)

private data class ReaderPage(val blocks: List<ReaderPageBlock>)

/** Страница, на которой начинается позиция (абзац + смещение). */
private fun pageIndexFor(pages: List<ReaderPage>, segment: Int, offset: Int): Int {
    var best = -1
    pages.forEachIndexed { index, page ->
        val first = page.blocks.firstOrNull() ?: return@forEachIndexed
        val seg = first.segmentIndex ?: -1
        if (seg < segment || (seg == segment && first.charOffset <= offset)) best = index
    }
    return best.coerceAtLeast(0)
}

/**
 * Раскладывает главу по страницам: меряет текст тем же стилем, что и показ,
 * и режет абзац по границам строк, если он не влез целиком.
 */
private fun paginate(
    items: List<ReaderItem>,
    chapterIndex: Int,
    style: ReaderStyle,
    verse: Boolean,
    measurer: TextMeasurer,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    startSegment: Int = -1,
    startOffset: Int = 0,
): List<ReaderPage> {
    val marginPx = with(density) { style.margins.dp.dp.roundToPx() }
    val textWidth = (widthPx - marginPx * 2).coerceAtLeast(1)
    val pageHeight = heightPx.toFloat()
    val pages = mutableListOf<ReaderPage>()
    var blocks = mutableListOf<ReaderPageBlock>()
    var used = 0f

    fun flush() {
        if (blocks.isNotEmpty()) pages += ReaderPage(blocks)
        blocks = mutableListOf()
        used = 0f
    }

    var started = startSegment <= 0
    for (item in items) {
        if (item.chapterIndex != chapterIndex) continue
        if (item is ReaderItem.Image) {
            val imageHeight = imageHeightPx(item.path, textWidth, heightPx, density)
            val top = 8.dp
            val bottom = 8.dp
            val topPx = with(density) { top.roundToPx() }
            val bottomPx = with(density) { bottom.roundToPx() }
            val total = topPx + imageHeight + bottomPx
            if (blocks.isNotEmpty() && used + total > pageHeight) flush()
            blocks += ReaderPageBlock(
                text = item.alt,
                isHeader = false,
                chapterIndex = chapterIndex,
                segmentIndex = item.afterSegment,
                startsItem = false,
                topDp = top,
                bottomDp = bottom,
                imagePath = item.path,
                imageHeight = imageHeight,
            )
            used += total
            continue
        }
        val isHeader = item is ReaderItem.Header
        val itemSegment = (item as? ReaderItem.Paragraph)?.segmentIndex
        if (!started) {
            if (itemSegment == null || itemSegment < startSegment) continue
            started = true
        }
        val fullText = when (item) {
            is ReaderItem.Header -> item.title
            is ReaderItem.Paragraph -> item.text
            else -> ""
        }.trim()
        val itemNotes = (item as? ReaderItem.Paragraph)?.notes.orEmpty()
        if (fullText.isEmpty()) continue
        val textStyle = if (isHeader) headerTextStyle(style) else bodyTextStyle(style)
        val top = if (isHeader) 26 else if (verse) 6 else 7
        val bottom = if (isHeader) 10 else if (verse) 6 else 7
        val segment = itemSegment
        val startPos = if (segment != null && segment == startSegment) {
            startOffset.coerceIn(0, fullText.length)
        } else {
            0
        }
        var remaining = fullText.substring(startPos)
        var startsItem = startPos == 0
        var offset = startPos
        while (remaining.isNotEmpty()) {
            val topDp = if (startsItem) top.dp else 0.dp
            val layout = measurer.measure(
                text = AnnotatedString(remaining),
                style = textStyle,
                constraints = Constraints(maxWidth = textWidth),
            )
            val topPx = with(density) { topDp.roundToPx() }
            val bottomPx = with(density) { bottom.dp.roundToPx() }
            if (used + topPx + layout.size.height + bottomPx <= pageHeight) {
                blocks += ReaderPageBlock(
                    text = remaining,
                    isHeader = isHeader,
                    chapterIndex = chapterIndex,
                    segmentIndex = segment,
                    startsItem = startsItem,
                    topDp = topDp,
                    bottomDp = bottom.dp,
                    charOffset = offset,
                    notes = notesInRange(itemNotes, offset, offset + remaining.length),
                )
                used += topPx + layout.size.height + bottomPx
                break
            }
            // Кусок всегда последний на странице, поэтому учитываем и нижний отступ.
            val available = pageHeight - used - topPx - bottomPx
            var lastLine = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= available) lastLine = line else break
            }
            if (lastLine < 0) {
                if (blocks.isNotEmpty()) {
                    flush()
                    continue
                }
                // Пустая страница: даже одна строка не влезает — берём её.
                lastLine = 0
            }
            val end = layout.getLineEnd(lastLine, visibleEnd = true)
            if (end <= 0) {
                blocks += ReaderPageBlock(
                    remaining, isHeader, chapterIndex, segment, startsItem, topDp, bottom.dp, offset,
                )
                used += topPx + layout.size.height + bottomPx
                break
            }
            blocks += ReaderPageBlock(
                text = remaining.substring(0, end).trimEnd(),
                isHeader = isHeader,
                chapterIndex = chapterIndex,
                segmentIndex = segment,
                startsItem = startsItem,
                topDp = topDp,
                bottomDp = bottom.dp,
                charOffset = offset,
                notes = notesInRange(itemNotes, offset, offset + end),
            )
            used += topPx + layout.getLineBottom(lastLine) + bottomPx
            offset += end
            remaining = remaining.substring(end)
            val trimmed = remaining.length - remaining.trimStart().length
            offset += trimmed
            remaining = remaining.trimStart()
            startsItem = false
            flush()
        }
    }
    flush()
    return pages
}

/** Одна страница: заголовок и абзацы с теми же отступами, что при разбивке. */
/** Страница на своём месте: при пролистывании сдвигается на offsetX. */
@Composable
private fun PageLayer(
    page: ReaderPage,
    style: ReaderStyle,
    offsetX: Float,
    onNote: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .background(style.colors().background),
    ) {
        ReaderPageContent(page, style, onNote)
    }
}

@Composable
private fun ReaderPageContent(
    page: ReaderPage,
    style: ReaderStyle,
    onNote: (String) -> Unit,
) {
    val colors = style.colors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp, bottom = 28.dp),
    ) {
        page.blocks.forEach { block ->
            val imagePath = block.imagePath
            if (imagePath != null) {
                val density = LocalDensity.current
                ReaderImage(
                    path = imagePath,
                    alt = block.text.takeIf { it.isNotBlank() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = style.margins.dp.dp)
                        .padding(top = block.topDp, bottom = block.bottomDp)
                        .height(with(density) { block.imageHeight.toDp() }),
                )
            } else {
                val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    text = styledText(block.text, block.notes, colors.accent),
                    onTextLayout = { layout.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = style.margins.dp.dp)
                        .padding(top = block.topDp, bottom = block.bottomDp)
                        .noteTap(block.notes, layout, onNote),
                    color = colors.text,
                    style = if (block.isHeader) headerTextStyle(style) else bodyTextStyle(style),
                )
            }
        }
    }
}

/** Картинка в ленте: ширина по экрану, пропорции из файла. */
@Composable
private fun ReaderImageItem(item: ReaderItem.Image, style: ReaderStyle) {
    val ratio = remember(item.path) { imageAspect(item.path) }
    if (ratio > 0f) {
        ReaderImage(
            path = item.path,
            alt = item.alt.takeIf { it.isNotBlank() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = style.margins.dp.dp, vertical = 10.dp)
                .aspectRatio(ratio),
        )
    }
}

@Composable
private fun ReaderImage(path: String, alt: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { decodeScaledBitmap(path, 1600) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = alt,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

private fun imageAspect(path: String): Float {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return 0f
    return options.outWidth.toFloat() / options.outHeight
}

private fun imageHeightPx(path: String, width: Int, pageHeight: Int, density: Density): Int {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return 0
    val height = width.toFloat() * options.outHeight / options.outWidth
    val maxHeight = pageHeight - with(density) { 40.dp.roundToPx() }
    return height.roundToInt().coerceIn(1, maxHeight.coerceAtLeast(1))
}

private fun decodeScaledBitmap(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

private data class NoteSheetState(val label: String, val note: NoteRef?)

/** Текст со ссылками: диапазоны [NoteSpan] окрашиваем цветом акцента. */
private fun styledText(text: String, notes: List<NoteSpan>, color: Color): AnnotatedString {
    if (notes.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        notes.forEach { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start < end) {
                addStyle(
                    SpanStyle(
                        color = color,
                        textDecoration = TextDecoration.Underline,
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

/**
 * Тап по ссылке примечания: ищем метку по позиции в макете текста.
 * Если попали — событие поглощаем, иначе страница листается как обычно.
 */
private fun Modifier.noteTap(
    notes: List<NoteSpan>,
    layout: MutableState<TextLayoutResult?>,
    onNote: (String) -> Unit,
): Modifier {
    if (notes.isEmpty()) return this
    return pointerInput(notes) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            val result = layout.value ?: return@awaitEachGesture
            if (up.position.x !in 0f..result.size.width.toFloat() ||
                up.position.y !in 0f..result.size.height.toFloat()
            ) {
                return@awaitEachGesture
            }
            val offset = result.getOffsetForPosition(up.position)
            val note = notes.firstOrNull { offset >= it.start && offset < it.end }
            if (note != null) {
                up.consume()
                onNote(note.label)
            }
        }
    }
}

/** Ссылки, попавшие в кусок текста [from, to): сдвигаем к началу куска. */
private fun notesInRange(notes: List<NoteSpan>, from: Int, to: Int): List<NoteSpan> =
    notes.mapNotNull { span ->
        val start = maxOf(span.start, from)
        val end = minOf(span.end, to)
        if (start < end) NoteSpan(start - from, end - from, span.label) else null
    }

@Composable
private fun NotePanel(
    state: NoteSheetState,
    onDismiss: () -> Unit,
    minHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var offsetY by remember { mutableStateOf(0f) }
    val threshold = with(density) { 96.dp.toPx() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .offset { IntOffset(0, offsetY.roundToInt()) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            // Шапка панели: потянуть вниз — закрыть.
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (offsetY > threshold) {
                                    onDismiss()
                                } else {
                                    scope.launch {
                                        animate(offsetY, 0f, animationSpec = tween(180)) { value, _ ->
                                            offsetY = value
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    animate(offsetY, 0f, animationSpec = tween(180)) { value, _ ->
                                        offsetY = value
                                    }
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                Text(
                    text = stringResource(R.string.note_title, state.label),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.note?.section?.takeIf { it.isNotBlank() }?.let { section ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = section,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val scroll = rememberScrollState()
            Text(
                text = state.note?.text ?: stringResource(R.string.note_not_found),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontStyle = FontStyle.Italic,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun ReaderHeader(title: String, style: ReaderStyle) {
    val colors = style.colors()
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = style.margins.dp.dp,
                end = style.margins.dp.dp,
                top = 26.dp,
                bottom = 10.dp,
            ),
        color = colors.text,
        style = headerTextStyle(style),
    )
}

@Composable
private fun ReaderParagraph(
    text: String,
    notes: List<NoteSpan>,
    style: ReaderStyle,
    verse: Boolean,
    onNote: (String) -> Unit,
) {
    val colors = style.colors()
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = styledText(text, notes, colors.accent),
        onTextLayout = { layout.value = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.margins.dp.dp, vertical = if (verse) 6.dp else 7.dp)
            .noteTap(notes, layout, onNote),
        color = colors.text,
        style = bodyTextStyle(style),
    )
}

/**
 * Подвал читалки: сверху строка оглавления с названием главы,
 * ниже — оформление текста и закладки.
 */
@Composable
private fun ReaderFooter(
    chapterTitle: String?,
    percent: Int,
    onSeek: (Float) -> Unit,
    onToc: () -> Unit,
    onAppearance: () -> Unit,
    onBookmarks: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            // Тап по пустому месту подвала не должен листать страницу.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToc)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colors.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = chapterTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.reader_toc),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        // Таймлайн: толстая дорожка с точкой-ползунком, её можно тянуть.
        var scrubFraction by remember { mutableStateOf<Float?>(null) }
        var scrubbing by remember { mutableStateOf(false) }
        // Пока тянем — показываем долю пальца; когда позиция обновится, снимаем.
        LaunchedEffect(percent) {
            if (!scrubbing) scrubFraction = null
        }
        val fraction = scrubFraction ?: (percent.coerceIn(0, 100) / 100f)
        val shownPercent = if (scrubFraction != null) {
            (fraction * 100f).roundToInt().coerceIn(0, 100)
        } else {
            percent
        }
        val trackColor = colors.outlineVariant
        val fillColor = MaterialTheme.colorScheme.primary
        val seek: (Float) -> Unit = { raw ->
            val value = raw.coerceIn(0f, 1f)
            scrubFraction = value
            onSeek(value)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        seek(offset.x / size.width)
                    }
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
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val trackWidth = 6.dp.toPx()
            val radius = trackWidth / 2f
            val dotRadius = 6.dp.toPx()
            val centerY = size.height / 2f
            val startX = dotRadius
            val endX = size.width - dotRadius
            drawLine(
                color = trackColor,
                start = androidx.compose.ui.geometry.Offset(startX, centerY),
                end = androidx.compose.ui.geometry.Offset(endX, centerY),
                strokeWidth = trackWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            val dotX = startX + (endX - startX) * fraction
            if (fraction > 0f) {
                drawLine(
                    color = fillColor,
                    start = androidx.compose.ui.geometry.Offset(startX, centerY),
                    end = androidx.compose.ui.geometry.Offset(dotX, centerY),
                    strokeWidth = trackWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            drawCircle(
                color = fillColor,
                radius = dotRadius,
                center = androidx.compose.ui.geometry.Offset(dotX, centerY),
            )
        }
        }
        Text(
            text = stringResource(R.string.reading_progress, shownPercent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides COMPACT_ACTION_SIZE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAppearance, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                    Icon(
                        Icons.Filled.TextFields,
                        contentDescription = stringResource(R.string.reader_appearance),
                        modifier = Modifier.size(24.dp),
                        tint = colors.onSurface,
                    )
                }
                IconButton(onClick = onBookmarks, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = stringResource(R.string.bookmarks),
                        modifier = Modifier.size(24.dp),
                        tint = colors.onSurface,
                    )
                }
                IconButton(onClick = onListen, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = stringResource(R.string.reader_listen),
                        modifier = Modifier.size(24.dp),
                        tint = colors.onSurface,
                    )
                }
            }
        }
    }
}

/** Высота кнопок строки действий: компактнее стандартных 48 dp. */
private val COMPACT_ACTION_SIZE = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTocSheet(
    chapters: List<Pair<String, Int>>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Оглавление занимает экран до строки состояния: высота = экран минус
    // статус-бар и ручка шторки, без наезда на системные иконки.
    val density = LocalDensity.current
    val statusBarHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val view = LocalView.current
    val screenHeight = with(density) { view.height.toDp() }
    val sheetHeight = (screenHeight - statusBarHeight - 44.dp).coerceAtLeast(240.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_toc),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(chapters) { index, (title, depth) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(
                                start = (20 + depth * 14).dp,
                                end = 20.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        Text(
                            text = title.ifBlank { stringResource(R.string.reader_chapter_short, index + 1) },
                            style = if (depth > 0) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                            color = if (index == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBookmarksSheet(
    bookmarks: List<Bookmark>,
    onAdd: () -> Unit,
    onOpen: (Bookmark) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.bookmarks),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.bookmark_add))
            }
            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.bookmarks_empty),
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    itemsIndexed(bookmarks) { _, bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(bookmark) }
                                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.label.ifBlank {
                                        stringResource(
                                            R.string.bookmark_position,
                                            bookmark.chapterIndex + 1,
                                            bookmark.segmentIndex + 1,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.bookmark_position,
                                        bookmark.chapterIndex + 1,
                                        bookmark.segmentIndex + 1,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(bookmark.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    style: ReaderStyle,
    onChange: (ReaderStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_appearance),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))

            // Яркость: как в системе; если выключено — ползунок с точкой.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChange(style.copy(followSystemBrightness = !style.followSystemBrightness))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = style.followSystemBrightness,
                    onCheckedChange = { onChange(style.copy(followSystemBrightness = it)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reader_brightness_system),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (!style.followSystemBrightness) {
                ReaderBrightnessSlider(
                    value = style.brightness,
                    onChange = { onChange(style.copy(brightness = it)) },
                )
            }
            Spacer(Modifier.height(8.dp))

            // Тема фона: карусель.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(ReaderTheme.entries) { theme ->
                    ReaderThemeChip(
                        theme = theme,
                        selected = style.theme == theme,
                        onClick = { onChange(style.copy(theme = theme)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Шрифт: образец, шесть точек и размер.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable {
                            val fonts = ReaderFont.entries
                            onChange(style.copy(font = fonts[(style.font.ordinal + 1) % fonts.size]))
                        },
                ) {
                    // Высота фиксирована: у разных шрифтов разный рост букв,
                    // иначе при переключении прыгает вся шторка.
                    Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = style.font.title,
                            style = TextStyle(
                                fontFamily = style.font.family,
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ReaderFontDots(style.font)
                }
                Spacer(Modifier.width(16.dp))
                ReaderSizeStepper(
                    onMinus = {
                        onChange(
                            style.copy(
                                fontSizeSp = (style.fontSizeSp - 1)
                                    .coerceAtLeast(ReaderSettingsStore.MIN_SIZE),
                            ),
                        )
                    },
                    onPlus = {
                        onChange(
                            style.copy(
                                fontSizeSp = (style.fontSizeSp + 1)
                                    .coerceAtMost(ReaderSettingsStore.MAX_SIZE),
                            ),
                        )
                    },
                )
            }
            Spacer(Modifier.height(16.dp))

            // Межстрочный интервал и поля — по три режима.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ReaderLineSpacingGroup(
                    mode = style.lineSpacing,
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(style.copy(lineSpacing = it)) },
                )
                ReaderMarginsGroup(
                    mode = style.margins,
                    onChange = { onChange(style.copy(margins = it)) },
                )
            }
            Spacer(Modifier.height(14.dp))

            // Прокрутка текста: выключено — листание тапом по краю.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_scroll_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(14.dp))
                Switch(
                    checked = style.scrollMode,
                    onCheckedChange = { onChange(style.copy(scrollMode = it)) },
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBrightnessSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(
        value = value.coerceIn(ReaderSettingsStore.MIN_BRIGHTNESS, 1f),
        onValueChange = onChange,
        valueRange = ReaderSettingsStore.MIN_BRIGHTNESS..1f,
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        ),
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        },
        track = { state ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.value.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 6.dp, end = 6.dp),
    )
}

@Composable
private fun ReaderThemeChip(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = theme.colors(isSystemInDarkTheme())
    val label = stringResource(
        when (theme) {
            ReaderTheme.WHITE -> R.string.reader_theme_white
            ReaderTheme.SEPIA -> R.string.reader_theme_sepia
            ReaderTheme.GRAY -> R.string.reader_theme_gray
            ReaderTheme.BLACK -> R.string.reader_theme_black
            ReaderTheme.SYSTEM -> R.string.reader_theme_system
        },
    )
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(colors.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else colors.line,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReaderFontDots(font: ReaderFont) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReaderFont.entries.forEach { item ->
            val active = item == font
            Box(
                Modifier
                    .size(if (active) 9.dp else 7.dp)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun ReaderSizeStepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clickable(onClick = onMinus),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "\u2212", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier
                    .size(52.dp)
                    .clickable(onClick = onPlus),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ReaderLineSpacingGroup(
    mode: ReaderLineSpacing,
    onChange: (ReaderLineSpacing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ReaderLineSpacing.entries
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { item ->
            ReaderLineSpacingIcon(
                mode = item,
                active = item == mode,
                onClick = { onChange(item) },
            )
        }
    }
}

@Composable
private fun ReaderLineSpacingIcon(
    mode: ReaderLineSpacing,
    active: Boolean,
    onClick: () -> Unit,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    // Густота передаётся числом строк, а не их разбегом: так все три
    // иконки занимают одну и ту же высоту.
    val lines = when (mode) {
        ReaderLineSpacing.COMPACT -> 5
        ReaderLineSpacing.NORMAL -> 4
        ReaderLineSpacing.WIDE -> 3
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val lineHeight = 2.dp.toPx()
            val step = (size.height - lineHeight) / (lines - 1)
            repeat(lines) { index ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, step * index),
                    size = Size(size.width, lineHeight),
                    cornerRadius = CornerRadius(lineHeight / 2),
                )
            }
        }
    }
}

@Composable
private fun ReaderMarginsGroup(
    mode: ReaderMargins,
    onChange: (ReaderMargins) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ReaderMargins.entries
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { item ->
            ReaderMarginsIcon(
                mode = item,
                active = item == mode,
                onClick = { onChange(item) },
            )
        }
    }
}

@Composable
private fun ReaderMarginsIcon(
    mode: ReaderMargins,
    active: Boolean,
    onClick: () -> Unit,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val inset = when (mode) {
        ReaderMargins.NARROW -> 3.dp
        ReaderMargins.MEDIUM -> 6.dp
        ReaderMargins.WIDE -> 9.dp
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                color = color,
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = stroke),
            )
            val pad = inset.toPx()
            val lineHeight = 2.dp.toPx()
            val gap = 3.dp.toPx()
            val step = lineHeight + gap
            val lines = 4
            val startY = (size.height - (step * lines - gap)) / 2
            repeat(lines) { index ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(pad, startY + step * index),
                    size = Size(size.width - pad * 2, lineHeight),
                    cornerRadius = CornerRadius(lineHeight / 2),
                )
            }
        }
    }
}

private fun itemKey(item: ReaderItem): String = when (item) {
    is ReaderItem.Header -> "h-${item.chapterIndex}"
    is ReaderItem.Paragraph -> "p-${item.chapterIndex}-${item.segmentIndex}"
    is ReaderItem.Image -> "i-${item.chapterIndex}-${item.afterSegment}-${item.path.hashCode()}"
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
