package ru.vtext.app.ui.audio

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import ru.vtext.app.data.AudioBookmarkStore
import ru.vtext.app.player.audio.AudioPlayerState
import ru.vtext.app.player.audio.SpeedPolicy
import ru.vtext.app.ui.components.SpeedSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    bookId: String,
    viewModel: AudioPlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Обратный отсчёт таймера сна: обновляем раз в секунду, пока он задан.
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.sleepEndsAt) {
        val endsAt = state.sleepEndsAt
        if (endsAt == null) {
            sleepRemaining = 0L
            return@LaunchedEffect
        }
        while (true) {
            sleepRemaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
            if (sleepRemaining == 0L) break
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(bookId) { viewModel.load(bookId) }

    var showSpeed by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.chapterTitle ?: state.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = "Закладки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (state.bookId == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Cover(state, Modifier.width(210.dp))

            Spacer(Modifier.height(18.dp))
            Text(
                text = state.title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.author.isNullOrBlank()) {
                Text(
                    text = state.author.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOfNotNull(
                    state.chapterTitle,
                    if (state.chapterCount > 0) "Глава ${state.chapterIndex + 1} из ${state.chapterCount}" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Transport(state, viewModel)
            }
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { showSpeed = true },
                    label = { Text(SpeedPolicy.chipLabel(state.speed)) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                AssistChip(
                    onClick = { showChapters = true },
                    label = { Text("Главы") },
                    leadingIcon = { Icon(Icons.Default.List, null, Modifier.size(18.dp)) },
                )
                val sleepActive =
                    state.sleepTimerMinutes != null || state.sleepUntilChapterEnd
                AssistChip(
                    onClick = { showSleep = true },
                    label = {
                        when {
                            state.sleepTimerMinutes != null -> Text(formatCountdown(sleepRemaining))
                            sleepActive -> Icon(
                                Icons.Default.Timer,
                                contentDescription = "Сон",
                                modifier = Modifier.size(18.dp),
                            )
                            else -> Icon(
                                Icons.Default.Alarm,
                                contentDescription = "Сон",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (sleepActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Progress(state, onSeek = viewModel::seekTo)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showSpeed) {
        SpeedSheet(
            current = state.speed,
            onChange = viewModel::setSpeed,
            onDismiss = { showSpeed = false },
        )
    }
    if (showChapters) {
        ChaptersSheet(
            state = state,
            onSelect = {
                viewModel.selectChapter(it)
                showChapters = false
            },
            onDismiss = { showChapters = false },
        )
    }
    if (showSleep) {
        SleepSheet(
            state = state,
            onMinutes = {
                viewModel.setSleepTimer(it)
                showSleep = false
            },
            onChapterEnd = {
                viewModel.sleepUntilChapterEnd()
                showSleep = false
            },
            onDismiss = { showSleep = false },
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            state = state,
            onAdd = {
                viewModel.addBookmark(state.chapterTitle.orEmpty())
            },
            onJump = {
                viewModel.jumpToBookmark(it)
                showBookmarks = false
            },
            onRemove = viewModel::removeBookmark,
            onDismiss = { showBookmarks = false },
        )
    }
}

@Composable
private fun Cover(state: AudioPlayerState, modifier: Modifier = Modifier) {
    val bitmap = remember(state.coverPath) {
        state.coverPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = state.title.orEmpty().take(1),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Progress(state: AudioPlayerState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1L)
    val value = if (dragging) dragValue else state.positionMs.toFloat()
    val shown = if (dragging) dragValue.toLong() else state.positionMs

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragValue.toLong())
            },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = AudioBookmarkStore.formatTime(shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AudioBookmarkStore.formatTime(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Transport(state: AudioPlayerState, viewModel: AudioPlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::previousChapter) {
            Icon(Icons.Default.SkipPrevious, "Предыдущая глава")
        }
        IconButton(onClick = { viewModel.seekBy(-15_000) }) {
            Icon(Icons.Default.Replay, "Назад 15 секунд")
        }
        FilledIconButton(
            onClick = viewModel::togglePlay,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Пауза" else "Слушать",
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = { viewModel.seekBy(30_000) }) {
            Icon(Icons.Default.Forward30, "Вперёд 30 секунд")
        }
        IconButton(onClick = viewModel::nextChapter) {
            Icon(Icons.Default.SkipNext, "Следующая глава")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(
    state: AudioPlayerState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.tracks) { track ->
                val index = state.tracks.indexOf(track)
                val active = index == state.chapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (track.durationMs > 0) {
                        Text(
                            text = AudioBookmarkStore.formatTime(track.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSelect(index) }) { Text("Слушать") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(
    state: AudioPlayerState,
    onMinutes: (Int?) -> Unit,
    onChapterEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember {
        listOf(
            SleepOption("—", null, false),
            SleepOption("конец главы", null, true),
        ) + SLEEP_MINUTES.map {
            SleepOption(String.format(java.util.Locale.getDefault(), "%02d:00", it), it, false)
        }
    }
    val selectedIndex = when {
        state.sleepUntilChapterEnd -> 1
        state.sleepTimerMinutes != null ->
            2 + SLEEP_MINUTES.indexOf(state.sleepTimerMinutes).coerceAtLeast(0)
        else -> 0
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(198.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Таймер сна",
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
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val itemWidth = 100.dp
                    val side = (maxWidth - itemWidth) / 2
                    val listState = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(selectedIndex)
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = side),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        flingBehavior = rememberSnapFlingBehavior(listState),
                    ) {
                        itemsIndexed(options) { index, option ->
                            val selected = index == selectedIndex
                            val scale by animateFloatAsState(
                                targetValue = if (selected) 1f else 0.92f,
                                label = "sleepItem",
                            )
                            Text(
                                text = option.label,
                                style = if (option.minutes != null || option.label == "—") {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.labelLarge
                                },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier
                                    .width(itemWidth)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clickable {
                                        if (option.chapterEnd) onChapterEnd() else onMinutes(option.minutes)
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SleepOption(
    val label: String,
    val minutes: Int?,
    val chapterEnd: Boolean,
)

private val SLEEP_MINUTES = listOf(5, 10, 15, 20, 30, 40, 50, 60)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    state: AudioPlayerState,
    onAdd: () -> Unit,
    onJump: (ru.vtext.app.data.AudioBookmark) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Закладки",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAdd) { Text("Добавить") }
            }
            if (state.bookmarks.isEmpty()) {
                Text(
                    text = "Пока пусто. Закладка сохраняет главу и время.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(state.bookmarks) { bookmark ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = bookmark.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Глава " + (bookmark.chapterIndex + 1) + " · " +
                                    AudioBookmarkStore.formatTime(bookmark.positionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onJump(bookmark) }) { Text("Перейти") }
                        TextButton(onClick = { onRemove(bookmark.id) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

/** Обратный отсчёт для кнопки сна: «14:59», «0:00». */
private fun formatCountdown(millis: Long): String {
    val totalSeconds = ((millis + 999) / 1000).toInt()
    return String.format(
        java.util.Locale.getDefault(),
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60,
    )
}
