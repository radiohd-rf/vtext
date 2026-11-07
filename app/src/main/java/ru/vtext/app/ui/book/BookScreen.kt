package ru.vtext.app.ui.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.domain.TextMarkup
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.player.PlayerState
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.SileroText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: BookViewModel = viewModel(
        key = bookId,
        factory = BookViewModel.factory(
            bookId = bookId,
            repository = container.repository,
            progressStore = container.progressStore,
            playback = container.playback,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val error = state.error ?: state.player.error
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissError()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    SpeedAction(
                        speed = state.player.speed,
                        onSelect = viewModel::setSpeed,
                    )
                    VoiceAction(
                        current = state.player.sileroVoice,
                        onSelect = viewModel::setVoice,
                    )
                    SleepAction(
                        current = state.player.sleepTimerMinutes,
                        untilChapterEnd = state.player.sleepUntilChapterEnd,
                        onSelect = viewModel::setSleepTimer,
                        onChapterEnd = viewModel::setSleepUntilChapterEnd,
                    )
                    var bookmarksOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { bookmarksOpen = true }) {
                        Icon(
                            Icons.Filled.BookmarkBorder,
                            contentDescription = stringResource(R.string.bookmarks),
                        )
                    }
                    if (bookmarksOpen) {
                        BookmarksDialog(
                            bookmarks = state.player.bookmarks,
                            onDismiss = { bookmarksOpen = false },
                            onAdd = {
                                viewModel.addBookmark()
                                bookmarksOpen = false
                            },
                            onOpen = { bookmark ->
                                viewModel.openBookmark(bookmark)
                                bookmarksOpen = false
                            },
                            onDelete = viewModel::removeBookmark,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val book = state.book
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                book == null -> Text(
                    text = stringResource(R.string.book_not_found),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> PlayerContent(state = state, book = book, viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(state: BookUiState, book: Book, viewModel: BookViewModel) {
    val player = state.player
    val chapter = book.chapters.getOrNull(player.chapterIndex) ?: return
    val listState = rememberLazyListState()
    var chapterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(player.chapterIndex, player.segmentIndex) {
        if (chapter.segments.isEmpty()) return@LaunchedEffect
        val target = player.segmentIndex.coerceIn(0, chapter.segments.lastIndex)
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!visible) listState.animateScrollToItem(target)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val sidebarWidth = (maxWidth * 0.38f).coerceIn(320.dp, 420.dp)
        if (wide) {
            // Планшет/ландшафт: текст слева, карточка и плеер — справа.
            Row(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    SegmentList(
                        segments = chapter.segments,
                        player = player,
                        onSelect = viewModel::selectSegment,
                        listState = listState,
                        horizontalPadding = 32.dp,
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
                    )
                }
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                Column(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight(),
                ) {
                    Spacer(Modifier.height(6.dp))
                    BookHeader(
                        book = book,
                        player = player,
                        chapter = chapter,
                        onClickChapter = { chapterSheet = true },
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerControls(state = state, chapter = chapter, viewModel = viewModel)
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxHeight().widthIn(max = 760.dp)) {
                    BookHeader(
                        book = book,
                        player = player,
                        chapter = chapter,
                        onClickChapter = { chapterSheet = true },
                    )
                    SegmentList(
                        segments = chapter.segments,
                        player = player,
                        onSelect = viewModel::selectSegment,
                        listState = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    PlayerControls(state = state, chapter = chapter, viewModel = viewModel)
                }
            }
        }
    }

    if (chapterSheet) {
        ChapterSheet(
            book = book,
            current = player.chapterIndex,
            onDismiss = { chapterSheet = false },
            onSelect = { index ->
                viewModel.selectChapter(index)
                chapterSheet = false
            },
        )
    }
}

@Composable
private fun SegmentList(
    segments: List<String>,
    player: PlayerState,
    onSelect: (Int) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            top = 10.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            SegmentRow(
                segment = segment,
                current = index == player.segmentIndex,
                currentWord = if (index == player.segmentIndex) player.currentWord else null,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun BookHeader(
    book: Book,
    player: PlayerState,
    chapter: Chapter,
    onClickChapter: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let { author ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        ChapterButton(
            chapter = chapter,
            index = player.chapterIndex,
            total = book.chapters.size,
            onClick = onClickChapter,
        )
    }
}

@Composable
private fun ChapterButton(
    chapter: Chapter,
    index: Int,
    total: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = chapter.title.ifBlank {
                    stringResource(R.string.chapter_of, index + 1, total)
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SegmentRow(
    segment: String,
    current: Boolean,
    currentWord: Int?,
    onClick: () -> Unit,
) {
    val display = remember(segment) { TextMarkup.strip(SileroText.displayText(segment)) }
    val wordRange = if (current) {
        remember(display, currentWord) { wordRangeAt(display, currentWord) }
    } else {
        null
    }
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f)
    val styled = remember(display, wordRange, highlight) {
        buildAnnotatedString {
            append(display)
            if (wordRange != null && wordRange.first >= 0 && wordRange.last < display.length) {
                addStyle(
                    SpanStyle(background = highlight, fontWeight = FontWeight.Medium),
                    wordRange.first,
                    wordRange.last + 1,
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    if (current) MaterialTheme.colorScheme.secondary else Color.Transparent,
                ),
        )
        Text(
            text = styled,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (current) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
            },
        )
    }
}

private fun wordRangeAt(text: String, index: Int?): IntRange? {
    if (index == null || index < 0) return null
    return SileroText.spokenWordRanges(text).getOrNull(index)
}

@Composable
private fun PlayerControls(state: BookUiState, chapter: Chapter, viewModel: BookViewModel) {
    val player = state.player
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = viewModel::previous,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.previous),
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                FilledIconButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.size(68.dp),
                    enabled = player.engineState == EngineState.READY,
                ) {
                    val playLabel = when {
                        player.isFinished -> R.string.restart
                        player.isPlaying -> R.string.pause
                        else -> R.string.play
                    }
                    Icon(
                        imageVector = when {
                            player.isFinished -> Icons.Filled.Replay
                            player.isPlaying -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(playLabel),
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                IconButton(
                    onClick = viewModel::next,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.next),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            val progress = if (chapter.segments.isEmpty()) {
                0f
            } else {
                (player.segmentIndex + 1f) / chapter.segments.size
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            if (player.isFinished) {
                Text(
                    text = stringResource(R.string.book_finished),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (player.engineState != EngineState.READY) {
                Text(
                    text = stringResource(
                        if (player.engineState == EngineState.ERROR) {
                            R.string.tts_error
                        } else {
                            R.string.tts_initializing
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (player.engineState == EngineState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    book: Book,
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.choose_chapter),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.chapters_count,
                    book.chapters.size,
                    book.chapters.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(book.chapters) { index, chapter ->
                    val selected = index == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(index) }
                            .padding(
                                start = (12 + chapter.depth * 14).dp,
                                end = 12.dp,
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
                            text = chapter.title,
                            style = if (chapter.depth > 0) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<ru.vtext.app.data.Bookmark>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ru.vtext.app.data.Bookmark) -> Unit,
    onDelete: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (bookmarks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.bookmarks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    bookmarks.forEach { bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onOpen(bookmark) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
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
                                if (bookmark.label.isNotBlank()) {
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
                            }
                            IconButton(onClick = { onDelete(bookmark.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.bookmark_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SpeedAction(speed: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val description = stringResource(R.string.speed_action, formatSpeed(speed))
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "${formatSpeed(speed)}\u00d7",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        SpeedMenu(expanded = expanded, speed = speed, options = options, onDismiss = { expanded = false }, onSelect = onSelect)
    }
}

@Composable
private fun SpeedMenu(
    expanded: Boolean,
    speed: Float,
    options: List<Float>,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text("${formatSpeed(option)}\u00d7") },
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
                trailingIcon = if (option == speed) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun VoiceAction(current: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val voice = ru.vtext.app.tts.SileroVoice.fromId(current)
    val description = stringResource(R.string.voice_action, voice.title)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.RecordVoiceOver,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ru.vtext.app.tts.SileroVoice.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        onSelect(item.id)
                        expanded = false
                    },
                    trailingIcon = if (item.id == voice.id) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepAction(
    current: Int?,
    untilChapterEnd: Boolean,
    onSelect: (Int?) -> Unit,
    onChapterEnd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = current != null || untilChapterEnd
    val description = when {
        untilChapterEnd -> stringResource(R.string.sleep_action_chapter)
        current != null -> stringResource(R.string.sleep_action_minutes, current)
        else -> stringResource(R.string.sleep_action_off)
    }
    val options = listOf(15, 30, 60)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = description,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_off)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                trailingIcon = if (!active) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_chapter_end)) },
                onClick = {
                    onChapterEnd()
                    expanded = false
                },
                trailingIcon = if (untilChapterEnd) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_minutes, minutes)) },
                    onClick = {
                        onSelect(minutes)
                        expanded = false
                    },
                    trailingIcon = if (current == minutes) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun formatSpeed(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
