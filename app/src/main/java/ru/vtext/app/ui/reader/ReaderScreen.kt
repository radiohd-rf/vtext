package ru.vtext.app.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val colors = style.theme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val activity = context.findActivity()
    var rotationLocked by rememberSaveable { mutableStateOf(false) }
    // Состояние применяется и после пересоздания Activity (смена темы, шрифта).
    LaunchedEffect(rotationLocked) {
        activity?.requestedOrientation = if (rotationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Покидая читалку, возвращаем системе обычное поведение поворота.
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentItem by remember { mutableStateOf<ReaderItem?>(null) }
    // Интерфейс читалки лежит поверх текста: тап по центру скрывает его.
    var uiVisible by rememberSaveable { mutableStateOf(true) }
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

    DisposableEffect(style.brightness) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null && style.brightness >= 0f) {
            val attrs = window.attributes
            attrs.screenBrightness = style.brightness
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
                style.lineHeight,
                style.marginDp,
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
                                is ReaderItem.Paragraph -> ReaderParagraph(item.text, style, state.verse)
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
                                        )
                                    }
                                } else if (pageOffsetX < 0f) {
                                    pages.getOrNull(pageIndex + 1)?.let { next ->
                                        PageLayer(
                                            page = next,
                                            style = style,
                                            offsetX = pageWidth + pageOffsetX,
                                        )
                                    }
                                }
                                PageLayer(current, style, pageOffsetX)
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
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.background,
                            titleContentColor = colors.text,
                            actionIconContentColor = colors.text,
                            navigationIconContentColor = colors.text,
                        ),
                    )
                    ReaderActions(
                        isPdf = state.book?.format == ru.vtext.app.domain.model.BookFormat.PDF,
                        rotationLocked = rotationLocked,
                        onToggleRotation = { rotationLocked = !rotationLocked },
                        onListen = { onListen(state.chapterIndex, state.segmentIndex) },
                        onOpenPdf = {
                            val page = state.book?.chapters
                                ?.getOrNull(state.chapterIndex)?.page ?: -1
                            onOpenPdf(page)
                        },
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
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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
    lineHeight = (style.fontSizeSp * style.lineHeight).sp,
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
    val marginPx = with(density) { style.marginDp.dp.roundToPx() }
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
                    remaining, isHeader, chapterIndex, segment, startsItem, topDp, bottom.dp, offset,
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
private fun PageLayer(page: ReaderPage, style: ReaderStyle, offsetX: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .background(style.theme.colors.background),
    ) {
        ReaderPageContent(page, style)
    }
}

@Composable
private fun ReaderPageContent(page: ReaderPage, style: ReaderStyle) {
    val colors = style.theme.colors
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
                        .padding(horizontal = style.marginDp.dp)
                        .padding(top = block.topDp, bottom = block.bottomDp)
                        .height(with(density) { block.imageHeight.toDp() }),
                )
            } else {
                Text(
                    text = block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = style.marginDp.dp)
                        .padding(top = block.topDp, bottom = block.bottomDp),
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
                .padding(horizontal = style.marginDp.dp, vertical = 10.dp)
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

@Composable
private fun ReaderHeader(title: String, style: ReaderStyle) {
    val colors = style.theme.colors
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = style.marginDp.dp,
                end = style.marginDp.dp,
                top = 26.dp,
                bottom = 10.dp,
            ),
        color = colors.text,
        style = headerTextStyle(style),
    )
}

@Composable
private fun ReaderParagraph(text: String, style: ReaderStyle, verse: Boolean) {
    val colors = style.theme.colors
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.marginDp.dp, vertical = if (verse) 6.dp else 7.dp),
        color = colors.text,
        style = bodyTextStyle(style),
    )
}

/**
 * Строка действий под верхней панелью: фиксация ориентации, PDF, «Слушать»,
 * оформление, закладки. Кнопки прижаты вправо.
 */
@Composable
private fun ReaderActions(
    isPdf: Boolean,
    rotationLocked: Boolean,
    onToggleRotation: () -> Unit,
    onListen: () -> Unit,
    onOpenPdf: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides COMPACT_ACTION_SIZE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleRotation, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                    Icon(
                        if (rotationLocked) {
                            Icons.Filled.ScreenLockRotation
                        } else {
                            Icons.Filled.ScreenRotation
                        },
                        contentDescription = stringResource(
                            if (rotationLocked) R.string.reader_rotation_unlock
                            else R.string.reader_rotation_lock,
                        ),
                        modifier = Modifier.size(20.dp),
                        tint = if (rotationLocked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                if (isPdf) {
                    IconButton(onClick = onOpenPdf, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                        Icon(
                            Icons.Filled.PictureAsPdf,
                            contentDescription = stringResource(R.string.pdf_original),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onListen, modifier = Modifier.size(COMPACT_ACTION_SIZE)) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = stringResource(R.string.reader_listen),
                        modifier = Modifier.size(20.dp),
                    )
                }

            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
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
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(
                text = stringResource(R.string.reader_appearance),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reader_scroll_mode),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.reader_scroll_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = style.scrollMode,
                    onCheckedChange = { onChange(style.copy(scrollMode = it)) },
                )
            }
            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.reader_theme),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = style.theme == theme,
                        onClick = { onChange(style.copy(theme = theme)) },
                        label = { Text(themeLabel(theme)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.reader_font),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = style.font == font,
                        onClick = { onChange(style.copy(font = font)) },
                        label = { Text(fontLabel(font)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            ReaderSlider(
                title = stringResource(R.string.reader_font_size),
                valueText = "${style.fontSizeSp}",
                value = style.fontSizeSp.toFloat(),
                range = ReaderSettingsStore.MIN_SIZE.toFloat()..ReaderSettingsStore.MAX_SIZE.toFloat(),
                steps = ReaderSettingsStore.MAX_SIZE - ReaderSettingsStore.MIN_SIZE - 1,
                onValue = { onChange(style.copy(fontSizeSp = it.toInt())) },
            )
            ReaderSlider(
                title = stringResource(R.string.reader_line_height),
                valueText = String.format("%.2f", style.lineHeight),
                value = style.lineHeight,
                range = ReaderSettingsStore.MIN_LINE_HEIGHT..ReaderSettingsStore.MAX_LINE_HEIGHT,
                steps = 9,
                onValue = { onChange(style.copy(lineHeight = it)) },
            )
            ReaderSlider(
                title = stringResource(R.string.reader_margins),
                valueText = "${style.marginDp} dp",
                value = style.marginDp.toFloat(),
                range = ReaderSettingsStore.MIN_MARGIN.toFloat()..ReaderSettingsStore.MAX_MARGIN.toFloat(),
                steps = ReaderSettingsStore.MAX_MARGIN - ReaderSettingsStore.MIN_MARGIN - 1,
                onValue = { onChange(style.copy(marginDp = it.toInt())) },
            )
            ReaderSlider(
                title = stringResource(R.string.reader_brightness),
                valueText = if (style.brightness < 0f) {
                    stringResource(R.string.reader_brightness_system)
                } else {
                    "${(style.brightness * 100).toInt()}%"
                },
                value = if (style.brightness < 0f) 0.5f else style.brightness.coerceIn(0.05f, 1f),
                range = 0.05f..1f,
                steps = 18,
                onValue = { onChange(style.copy(brightness = it)) },
            )
            TextButton(onClick = { onChange(style.copy(brightness = -1f)) }) {
                Text(stringResource(R.string.reader_brightness_system))
            }
        }
    }
}

@Composable
private fun ReaderSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun themeLabel(theme: ReaderTheme): String = stringResource(
    when (theme) {
        ReaderTheme.PAPER -> R.string.reader_theme_paper
        ReaderTheme.SEPIA -> R.string.reader_theme_sepia
        ReaderTheme.NIGHT -> R.string.reader_theme_night
    },
)

@Composable
private fun fontLabel(font: ReaderFont): String = stringResource(
    when (font) {
        ReaderFont.SERIF -> R.string.reader_font_serif
        ReaderFont.SANS -> R.string.reader_font_sans
    },
)

private val ReaderFont.family: FontFamily
    get() = if (this == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif

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
