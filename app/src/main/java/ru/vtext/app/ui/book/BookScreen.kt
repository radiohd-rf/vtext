package ru.vtext.app.ui.book

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.player.PlayerState
import ru.vtext.app.tts.EngineState

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.book?.title ?: stringResource(R.string.loading),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.book?.author?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
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

@Composable
private fun PlayerContent(state: BookUiState, book: Book, viewModel: BookViewModel) {
    val player = state.player
    val chapter = book.chapters.getOrNull(player.chapterIndex) ?: return
    val listState = rememberLazyListState()

    LaunchedEffect(player.chapterIndex, player.segmentIndex) {
        if (chapter.segments.isNotEmpty()) {
            listState.animateScrollToItem(player.segmentIndex.coerceIn(0, chapter.segments.lastIndex))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChapterSelector(state = state, chapters = book.chapters, onSelect = viewModel::selectChapter)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            itemsIndexed(chapter.segments) { index, segment ->
                val current = index == player.segmentIndex
                val shape = MaterialTheme.shapes.medium
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .clickable { viewModel.selectSegment(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Surface(
                        color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = shape,
                    ) {
                        val displayText = ru.vtext.app.domain.TextMarkup.strip(segment)
                        val wordRange = if (current) {
                            remember(displayText, player.currentWord) {
                                wordRangeAt(displayText, player.currentWord)
                            }
                        } else {
                            null
                        }
                        val styled = remember(displayText, wordRange) {
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(displayText)
                                if (wordRange != null) {
                                    addStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            background = androidx.compose.ui.graphics.Color(0x33000000),
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        ),
                                        wordRange.first,
                                        wordRange.last + 1,
                                    )
                                }
                            }
                        }
                        Text(
                            text = styled,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        PlayerControls(state = state, chapter = chapter, viewModel = viewModel)
    }
}

private fun wordRangeAt(text: String, index: Int?): IntRange? {
    if (index == null || index < 0) return null
    val words = WORD_REGEX.findAll(text).toList()
    val match = words.getOrNull(index) ?: return null
    return match.range
}

private val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")

@Composable
private fun ChapterSelector(state: BookUiState, chapters: List<Chapter>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chapterIndex = state.player.chapterIndex
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelect(chapterIndex - 1) }, enabled = chapterIndex > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = chapters.getOrNull(chapterIndex)?.title
                        ?: stringResource(R.string.chapter_of, chapterIndex + 1, chapters.size),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                chapters.forEachIndexed { index, chapter ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (chapter.depth > 0) {
                                    "• ${chapter.title}"
                                } else {
                                    "${index + 1}. ${chapter.title}"
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = if (chapter.depth > 0) {
                                    MaterialTheme.typography.bodySmall
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                modifier = Modifier.padding(start = (chapter.depth * 16).dp),
                            )
                        },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = { onSelect(chapterIndex + 1) },
            enabled = chapterIndex < chapters.lastIndex,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next))
        }
    }
}

@Composable
private fun PlayerControls(state: BookUiState, chapter: Chapter, viewModel: BookViewModel) {
    val player = state.player
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.segment_of, player.segmentIndex + 1, chapter.segments.size),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(R.string.speed_value, formatSpeed(player.speed)),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            val progress = if (chapter.segments.isEmpty()) 0f else (player.segmentIndex + 1f) / chapter.segments.size
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::previous) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.previous))
                }
                FilledIconButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.size(56.dp),
                    enabled = player.engineState == EngineState.READY,
                ) {
                    val playLabel = when {
                        player.isFinished -> R.string.restart
                        player.isPlaying -> R.string.pause
                        else -> R.string.play
                    }
                    Icon(
                        imageVector = when {
                            player.isFinished -> Icons.Default.Replay
                            player.isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = stringResource(playLabel),
                    )
                }
                IconButton(onClick = viewModel::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.next))
                }
            }
            if (player.isFinished) {
                Text(
                    text = stringResource(R.string.book_finished),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (player.engineState != EngineState.READY) {
                Text(
                    text = stringResource(
                        if (player.engineState == EngineState.ERROR) R.string.tts_error else R.string.tts_initializing,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SpeedMenu(speed = player.speed, onSelect = viewModel::setSpeed)
                SleepMenu(current = player.sleepTimerMinutes, onSelect = viewModel::setSleepTimer)
            }
        }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${stringResource(R.string.speed)} ${formatSpeed(speed)}×")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${formatSpeed(option)}×") },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepMenu(current: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(15, 30, 60)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = "${stringResource(R.string.sleep_timer)}: " +
                    if (current == null) stringResource(R.string.sleep_off)
                    else stringResource(R.string.sleep_minutes, current),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_off)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_minutes, minutes)) },
                    onClick = {
                        onSelect(minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatSpeed(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()
