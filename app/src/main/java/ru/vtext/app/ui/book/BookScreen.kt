package ru.vtext.app.ui.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.domain.TextMarkup
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.player.PlayerState
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.SileroText
import ru.vtext.app.tts.SileroVoice
import ru.vtext.app.tts.VoiceGender
import ru.vtext.app.ui.components.SpeedSheet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: String,
    startChapter: Int = -1,
    startSegment: Int = -1,
    onBack: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: BookViewModel = viewModel(
        key = bookId,
        factory = BookViewModel.factory(
            bookId = bookId,
            repository = container.repository,
            progressStore = container.progressStore,
            playback = container.playback,
            startChapter = startChapter,
            startSegment = startSegment,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineType by container.ttsManager.engineType.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var chapterSheet by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }

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
                    if (state.book != null) {
                        IconButton(onClick = { chapterSheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.reader_toc),
                            )
                        }
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
                else -> PlayerContent(
                    state = state,
                    book = book,
                    viewModel = viewModel,
                    onOpenChapters = { chapterSheet = true },
                    onSpeed = { activeSheet = PlayerSheet.SPEED },
                    onVoice = { activeSheet = PlayerSheet.VOICE },
                    onSleep = { activeSheet = PlayerSheet.SLEEP },
                    onBookmarks = { bookmarksOpen = true },
                )
            }
        }
    }

    activeSheet?.let { sheet ->
        PlayerOptionsSheet(
            sheet = sheet,
            player = state.player,
            engineType = engineType,
            onOpenSettings = {
                activeSheet = null
                onSettings()
            },
            onDismiss = { activeSheet = null },
            onSpeed = viewModel::setSpeed,
            onVoice = { id ->
                viewModel.setVoice(id)
                activeSheet = null
            },
            onSleep = { minutes -> viewModel.setSleepTimer(minutes) },
            onSleepChapter = { viewModel.setSleepUntilChapterEnd() },
        )
    }

    state.book?.let { book ->
        if (chapterSheet) {
            ChapterSheet(
                book = book,
                current = state.player.chapterIndex,
                onDismiss = { chapterSheet = false },
                onSelect = { index ->
                    viewModel.selectChapter(index)
                    chapterSheet = false
                },
            )
        }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    state: BookUiState,
    book: Book,
    viewModel: BookViewModel,
    onOpenChapters: () -> Unit,
    onSpeed: () -> Unit,
    onVoice: () -> Unit,
    onSleep: () -> Unit,
    onBookmarks: () -> Unit,
) {
    val player = state.player
    val chapter = book.chapters.getOrNull(player.chapterIndex) ?: return
    val listState = rememberLazyListState()

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
                    PlayerHeader(
                        book = book,
                        onVoice = onVoice,
                        onBookmarks = onBookmarks,
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerControls(
                        state = state,
                        chapter = chapter,
                        viewModel = viewModel,
                        onSpeed = onSpeed,
                        onSleep = onSleep,
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxHeight().widthIn(max = 760.dp)) {
                    PlayerHeader(
                        book = book,
                        onVoice = onVoice,
                        onBookmarks = onBookmarks,
                    )
                    SegmentList(
                        segments = chapter.segments,
                        player = player,
                        onSelect = viewModel::selectSegment,
                        listState = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    PlayerControls(
                        state = state,
                        chapter = chapter,
                        viewModel = viewModel,
                        onSpeed = onSpeed,
                        onSleep = onSleep,
                    )
                }
            }
        }
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
private fun PlayerHeader(
    book: Book,
    onVoice: () -> Unit,
    onBookmarks: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                icon = Icons.Filled.RecordVoiceOver,
                description = stringResource(R.string.sheet_voice_title),
                onClick = onVoice,
            )
            PlayerIconButton(
                icon = Icons.Filled.BookmarkBorder,
                description = stringResource(R.string.bookmarks),
                onClick = onBookmarks,
            )
        }
        book.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** Компактная кнопка, как в строке действий читалки. */
@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            },
        )
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
private fun PlayerControls(
    state: BookUiState,
    chapter: Chapter,
    viewModel: BookViewModel,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    val player = state.player
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Скорость — текстом слева от перемотки назад.
                Text(
                    text = "${formatSpeed(player.speed)}\u00d7",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (player.speed == 1f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onSpeed)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
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
                PlayerIconButton(
                    icon = Icons.Filled.Alarm,
                    description = stringResource(R.string.sheet_sleep_title),
                    onClick = onSleep,
                    active = player.sleepTimerMinutes != null ||
                        player.sleepUntilChapterEnd,
                )
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
                    .padding(horizontal = 20.dp)
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

private enum class PlayerSheet { SPEED, VOICE, SLEEP }







@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    sheet: PlayerSheet,
    player: PlayerState,
    engineType: ru.vtext.app.tts.TtsEngineType,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onVoice: (String) -> Unit,
    onSleep: (Int?) -> Unit,
    onSleepChapter: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        when (sheet) {
            PlayerSheet.SPEED -> SpeedSheet(
                current = player.speed,
                onChange = onSpeed,
                onDismiss = onDismiss,
                allowFine = true,
                applyLive = true,
                applyDelayMs = 350,
            )
            PlayerSheet.VOICE -> if (engineType == ru.vtext.app.tts.TtsEngineType.SILERO) {
                VoiceSheet(current = player.sileroVoice, onSelect = onVoice)
            } else {
                SystemVoiceSheet(onOpenSettings = onOpenSettings)
            }
            PlayerSheet.SLEEP -> SleepSheet(
                current = player.sleepTimerMinutes,
                untilChapterEnd = player.sleepUntilChapterEnd,
                onTimer = onSleep,
                onChapterEnd = onSleepChapter,
            )
        }
    }
}

@Composable
private fun SystemVoiceSheet(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.sheet_voice_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.voice_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_settings))
        }
    }
}

@Composable
private fun VoiceSheet(current: String?, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val options = remember { SileroVoice.entries.toList() }
    val initialIndex = options.indexOfFirst {
        it.id == SileroVoice.fromId(current).id
    }.coerceAtLeast(0)
    var appliedIndex by remember { mutableIntStateOf(initialIndex) }
    var pendingIndex by remember { mutableIntStateOf(initialIndex) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(198.dp)
            .padding(bottom = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.sheet_voice_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = (maxWidth - VOICE_ITEM_WIDTH) / 2
                    val listState = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(initialIndex)
                    }
                    LaunchedEffect(listState) {
                        snapshotFlow { centeredCarouselIndex(listState) }
                            .filterNotNull()
                            .distinctUntilChanged()
                            .collect { index ->
                                pendingIndex = index
                                if (index != appliedIndex) {
                                    onSelect(options[index].id)
                                    appliedIndex = index
                                }
                            }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = side),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        flingBehavior = rememberSnapFlingBehavior(listState),
                    ) {
                        itemsIndexed(options) { index, voice ->
                            val selected = index == pendingIndex
                            val scale by animateFloatAsState(
                                targetValue = if (selected) 1f else 0.9f,
                                label = "voiceItem",
                            )
                            Text(
                                text = voice.title,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 30.sp,
                                ),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .width(VOICE_ITEM_WIDTH)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clickable {
                                        scope.launch { listState.animateScrollToItem(index) }
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .offset(y = (-10).dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun SleepSheet(
    current: Int?,
    untilChapterEnd: Boolean,
    onTimer: (Int?) -> Unit,
    onChapterEnd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val options = remember {
        listOf(SleepOption("—", null, true), SleepOption("конец главы", null, false)) +
            SLEEP_MINUTES.map {
                SleepOption(String.format(java.util.Locale.getDefault(), "%02d:00", it), it, false)
            }
    }
    val initialIndex = when {
        untilChapterEnd -> 1
        current != null -> 2 + SLEEP_MINUTES.indexOf(current).coerceAtLeast(0)
        else -> 0
    }
    var appliedIndex by remember { mutableIntStateOf(initialIndex) }
    var pendingIndex by remember { mutableIntStateOf(initialIndex) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(198.dp)
            .padding(bottom = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.sheet_sleep_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = (maxWidth - ITEM_WIDTH) / 2
                    val listState = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(initialIndex)
                    }
                    LaunchedEffect(listState) {
                        snapshotFlow { centeredCarouselIndex(listState) }
                            .filterNotNull()
                            .distinctUntilChanged()
                            .collect { index ->
                                pendingIndex = index
                                if (index != appliedIndex) {
                                    val option = options[index]
                                    if (option.minutes == null && !option.off) {
                                        onChapterEnd()
                                    } else {
                                        onTimer(option.minutes)
                                    }
                                    appliedIndex = index
                                }
                            }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = side),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        flingBehavior = rememberSnapFlingBehavior(listState),
                    ) {
                        itemsIndexed(options) { index, option ->
                            val selected = index == pendingIndex
                            val scale by animateFloatAsState(
                                targetValue = if (selected) 1f else 0.92f,
                                label = "sleepItem",
                            )
                            if (option.off) {
                                Box(
                                    modifier = Modifier
                                        .width(ITEM_WIDTH)
                                        .height(52.dp)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .clickable {
                                            scope.launch { listState.animateScrollToItem(index) }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .width(36.dp)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(2.5.dp))
                                            .background(
                                                if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            ),
                                    )
                                }
                            } else {
                                Text(
                                    text = option.label,
                                    style = if (option.minutes != null) {
                                        MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = 28.sp,
                                            lineHeight = 34.sp,
                                        )
                                    } else {
                                        MaterialTheme.typography.labelLarge
                                    },
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = if (option.minutes != null) 1 else 2,
                                    softWrap = option.minutes == null,
                                    modifier = Modifier
                                        .width(ITEM_WIDTH)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .clickable {
                                            scope.launch { listState.animateScrollToItem(index) }
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .offset(y = (-10).dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private data class SleepOption(
    val label: String,
    val minutes: Int?,
    val off: Boolean,
)

private val SLEEP_MINUTES = listOf(5, 10, 15, 20, 30, 40, 50, 60)

private val ITEM_WIDTH = 88.dp
private val VOICE_ITEM_WIDTH = 120.dp

/** Индекс варианта напротив точки в центре карусели. */
private fun centeredCarouselIndex(
    listState: androidx.compose.foundation.lazy.LazyListState,
): Int? {
    val info = listState.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { item ->
        kotlin.math.abs(item.offset + item.size / 2f - center)
    }?.index
}

private fun formatSpeedScale(value: Float): String {
    val tenths = value * 10f
    val rounded = tenths.roundToInt()
    return if (abs(tenths - rounded) < 0.001f) {
        String.format(Locale.US, "%.1f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

private fun formatSpeed(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
