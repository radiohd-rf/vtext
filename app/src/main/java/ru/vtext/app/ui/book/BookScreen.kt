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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    var activeSheet by remember { mutableStateOf<PlayerSheet?>(null) }

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
                    SpeedAction(speed = state.player.speed) {
                        activeSheet = PlayerSheet.SPEED
                    }
                    VoiceAction {
                        activeSheet = PlayerSheet.VOICE
                    }
                    SleepAction(
                        active = state.player.sleepTimerMinutes != null ||
                            state.player.sleepUntilChapterEnd,
                    ) {
                        activeSheet = PlayerSheet.SLEEP
                    }
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

    activeSheet?.let { sheet ->
        PlayerOptionsSheet(
            sheet = sheet,
            player = state.player,
            onDismiss = { activeSheet = null },
            onSpeed = viewModel::setSpeed,
            onVoice = { id ->
                viewModel.setVoice(id)
                activeSheet = null
            },
            onSleep = { minutes ->
                viewModel.setSleepTimer(minutes)
                activeSheet = null
            },
            onSleepChapter = {
                viewModel.setSleepUntilChapterEnd()
                activeSheet = null
            },
        )
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

private enum class PlayerSheet { SPEED, VOICE, SLEEP }

@Composable
private fun SpeedAction(speed: Float, onClick: () -> Unit) {
    val description = stringResource(R.string.speed_action, formatSpeed(speed))
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
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
}

@Composable
private fun VoiceAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.RecordVoiceOver,
            contentDescription = stringResource(R.string.sheet_voice_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepAction(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = stringResource(R.string.sheet_sleep_title),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    sheet: PlayerSheet,
    player: PlayerState,
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
            PlayerSheet.SPEED -> SpeedSheet(speed = player.speed, onSelect = onSpeed)
            PlayerSheet.VOICE -> VoiceSheet(current = player.sileroVoice, onSelect = onVoice)
            PlayerSheet.SLEEP -> SleepSheet(
                current = player.sleepTimerMinutes,
                untilChapterEnd = player.sleepUntilChapterEnd,
                onSelect = onSleep,
                onChapterEnd = onSleepChapter,
            )
        }
    }
}

/** Шторка скорости: горизонтальная шкала 0.5–2.0 с шагом 0.1. */
@Composable
private fun SpeedSheet(speed: Float, onSelect: (Float) -> Unit) {
    val values = remember(speed) {
        val base = (5..20).map { it / 10f }
        if (base.any { abs(it - speed) < 0.001f }) {
            base
        } else {
            (base + speed).sorted()
        }
    }
    val selectedIndex = values
        .indexOfFirst { abs(it - speed) < 0.001f }
        .coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0),
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Text(
            text = stringResource(R.string.sheet_speed_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(values) { _, value ->
                val selected = abs(value - speed) < 0.001f
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${formatSpeedScale(value)}\u00d7",
                        style = if (selected) {
                            MaterialTheme.typography.headlineMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSheet(current: String?, onSelect: (String) -> Unit) {
    val selected = SileroVoice.fromId(current)
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Text(
            text = stringResource(R.string.sheet_voice_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        VoiceGroup(
            title = stringResource(R.string.voices_female_title),
            voices = SileroVoice.entries.filter { it.gender == VoiceGender.FEMALE },
            selectedId = selected.id,
            onSelect = onSelect,
        )
        VoiceGroup(
            title = stringResource(R.string.voices_male_title),
            voices = SileroVoice.entries.filter { it.gender == VoiceGender.MALE },
            selectedId = selected.id,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun VoiceGroup(
    title: String,
    voices: List<SileroVoice>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
    voices.forEach { voice ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(voice.id) }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = voice.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (voice.id == selectedId) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SleepSheet(
    current: Int?,
    untilChapterEnd: Boolean,
    onSelect: (Int?) -> Unit,
    onChapterEnd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Text(
            text = stringResource(R.string.sheet_sleep_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        SleepRow(
            title = stringResource(R.string.sleep_off),
            selected = !untilChapterEnd && current == null,
            onClick = { onSelect(null) },
        )
        SleepRow(
            title = stringResource(R.string.sleep_chapter_end_title),
            selected = untilChapterEnd,
            onClick = onChapterEnd,
        )
        listOf(15, 30, 60).forEach { minutes ->
            SleepRow(
                title = stringResource(R.string.sleep_minutes, minutes),
                selected = current == minutes,
                onClick = { onSelect(minutes) },
            )
        }
    }
}

@Composable
private fun SleepRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
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
