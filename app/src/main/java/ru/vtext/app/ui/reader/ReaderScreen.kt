package ru.vtext.app.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
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
        if (state.items.isNotEmpty()) listState.scrollToItem(state.startIndex)
    }

    LaunchedEffect(listState, state.items) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { index ->
                delay(350)
                val item = state.items.getOrNull(index)
                currentItem = item
                viewModel.onPosition(item, index)
            }
    }

    ReaderThemeStyle(style) {
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
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
                            IconButton(onClick = { showToc = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(R.string.reader_toc),
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
                        onAppearance = { showSettings = true },
                        onBookmarks = { showBookmarks = true },
                    )
                }
            },
        ) { padding ->
            when {
                state.loading -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.error != null || state.book == null -> Text(
                    text = state.error ?: stringResource(R.string.book_not_found),
                    modifier = Modifier.padding(padding).padding(24.dp),
                    color = colors.muted,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                ) {
                    itemsIndexed(
                        items = state.items,
                        key = { _, item -> itemKey(item) },
                    ) { _, item ->
                        when (item) {
                            is ReaderItem.Header -> ReaderHeader(item.title, style)
                            is ReaderItem.Paragraph -> ReaderParagraph(item.text, style, state.verse)
                        }
                    }
                }
            }
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
        style = TextStyle(
            fontFamily = style.font.family,
            fontWeight = FontWeight.SemiBold,
            fontSize = (style.fontSizeSp * 1.3f).sp,
            lineHeight = (style.fontSizeSp * 1.3f * 1.3f).sp,
        ),
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
        style = TextStyle(
            fontFamily = style.font.family,
            fontSize = style.fontSizeSp.sp,
            lineHeight = (style.fontSizeSp * style.lineHeight).sp,
        ),
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
    onAppearance: () -> Unit,
    onBookmarks: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleRotation) {
                Icon(
                    if (rotationLocked) Icons.Filled.ScreenLockRotation else Icons.Filled.ScreenRotation,
                    contentDescription = stringResource(
                        if (rotationLocked) R.string.reader_rotation_unlock
                        else R.string.reader_rotation_lock,
                    ),
                    tint = if (rotationLocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                )
            }
            if (isPdf) {
                IconButton(onClick = onOpenPdf) {
                    Icon(
                        Icons.Filled.PictureAsPdf,
                        contentDescription = stringResource(R.string.pdf_original),
                    )
                }
            }
            IconButton(onClick = onListen) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = stringResource(R.string.reader_listen),
                )
            }
            IconButton(onClick = onAppearance) {
                Icon(
                    Icons.Filled.TextFields,
                    contentDescription = stringResource(R.string.reader_appearance),
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(
                    Icons.Filled.BookmarkBorder,
                    contentDescription = stringResource(R.string.bookmarks),
                )
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTocSheet(
    chapters: List<Pair<String, Int>>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.reader_toc),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
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
            Spacer(Modifier.height(6.dp))

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
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
