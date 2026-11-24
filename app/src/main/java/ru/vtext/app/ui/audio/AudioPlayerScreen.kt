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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
    onSettings: () -> Unit,
    onPlayerSettings: () -> Unit,
    settings: ru.vtext.app.data.AudioPlayerSettingsStore,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backSkipMs by settings.backSkipMs.collectAsStateWithLifecycle()
    val forwardSkipMs by settings.forwardSkipMs.collectAsStateWithLifecycle()
    val speedFactor = state.speed.coerceAtLeast(0.1f)
    val chapterRemaining = (state.durationMs - state.positionMs).coerceAtLeast(0L)
    val currentDuration = state.durationMs.takeIf { it > 0L }
        ?: state.tracks.getOrNull(state.chapterIndex)?.durationMs
        ?: 0L
    val laterTracksKnown = state.tracks.drop(state.chapterIndex + 1).all { it.durationMs > 0L }
    val bookRemaining = (currentDuration - state.positionMs).coerceAtLeast(0L) +
        state.tracks.drop(state.chapterIndex + 1).sumOf { it.durationMs.coerceAtLeast(0L) }
    val showBookRemaining = currentDuration > 0L && laterTracksKnown

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
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.orEmpty(),
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Настройки плеера")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Настройки плеера") },
                                onClick = {
                                    showMenu = false
                                    onPlayerSettings()
                                },
                            )
                            if (state.audioPublicKey != null) {
                                DropdownMenuItem(
                                    text = { Text("Обновить содержание") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.load(bookId)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Настройки приложения") },
                                onClick = {
                                    showMenu = false
                                    onSettings()
                                },
                            )
                        }
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
                text = state.chapterTitle ?: state.title.orEmpty(),
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
            if (showBookRemaining) {
                Text(
                    text = "До конца книги: " +
                        formatBookRemaining((bookRemaining / speedFactor).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Transport(state, viewModel, backSkipMs, forwardSkipMs)
            }
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val speedIsDefault = SpeedPolicy.snap(state.speed) == 1f
                AssistChip(
                    onClick = { showSpeed = true },
                    label = { Text(SpeedPolicy.chipLabel(state.speed)) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (speedIsDefault) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                )
                AssistChip(
                    onClick = { showChapters = true },
                    label = { Text("Глава ${state.chapterIndex + 1}") },
                    leadingIcon = { Icon(Icons.Default.List, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                val sleepActive =
                    state.sleepTimerMinutes != null || state.sleepUntilChapterEnd
                AssistChip(
                    onClick = { showSleep = true },
                    label = {
                        when {
                            state.sleepTimerMinutes != null ->
                                Text(formatCountdown(sleepRemaining))
                            state.sleepUntilChapterEnd ->
                                Text(formatCountdown((chapterRemaining / speedFactor).toLong()))
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
            onTimer = { viewModel.setSleepTimer(it) },
            onChapterEnd = { viewModel.sleepUntilChapterEnd() },
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

@OptIn(ExperimentalMaterial3Api::class)
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
            thumb = {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val fraction = if (range > 0f) {
                    ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                text = "-" + AudioBookmarkStore.formatTime((duration - shown).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Transport(
    state: AudioPlayerState,
    viewModel: AudioPlayerViewModel,
    backSkipMs: Int,
    forwardSkipMs: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::previousChapter) {
            Icon(Icons.Default.SkipPrevious, "Предыдущая глава")
        }
        SeekButton(
            seconds = backSkipMs / 1000,
            forward = false,
            onClick = { viewModel.seekBy(-backSkipMs.toLong()) },
        )
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
        SeekButton(
            seconds = forwardSkipMs / 1000,
            forward = true,
            onClick = { viewModel.seekBy(forwardSkipMs.toLong()) },
        )
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // Небольшой зазор сверху: за верхний край удобно тянуть шторку вниз.
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Text(
                text = "Содержание",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(state.tracks) { index, track ->
                    val active = index == state.chapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .width(34.dp)
                                .padding(top = 3.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val details = buildString {
                                if (track.durationMs > 0L) {
                                    append(AudioBookmarkStore.formatTime(track.durationMs))
                                }
                                if (track.sizeBytes > 0L) {
                                    if (isNotEmpty()) append(" · ")
                                    append(formatTrackSize(track.sizeBytes))
                                }
                            }
                            if (details.isNotEmpty()) {
                                Text(
                                    text = details,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
private fun SleepSheet(
    state: AudioPlayerState,
    onTimer: (Int?) -> Unit,
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
    // Логика «колеса фортуны»: включён тот вариант, напротив которого стоит
    // точка. Прокрутка останавливается — и этот вариант сразу становится таймером.
    var pendingIndex by remember { mutableIntStateOf(selectedIndex) }
    var appliedIndex by remember { mutableIntStateOf(selectedIndex) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(198.dp)
                .padding(bottom = 10.dp),
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val side = (maxWidth - 96.dp) / 2
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(Unit) {
                            listState.scrollToItem(selectedIndex)
                        }
                        // Центральный элемент — тот, что стоит напротив точки.
                        LaunchedEffect(listState) {
                            snapshotFlow { centeredSleepIndex(listState) }
                                .filterNotNull()
                                .distinctUntilChanged()
                                .collect { index ->
                                    pendingIndex = index
                                    if (index != appliedIndex) {
                                        val option = options[index]
                                        if (option.chapterEnd) {
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            flingBehavior = rememberSnapFlingBehavior(listState),
                        ) {
                            itemsIndexed(options) { index, option ->
                                val selected = index == pendingIndex
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1f else 0.92f,
                                    label = "sleepItem",
                                )
                                // Тап подводит вариант к точке, дальше выбор применится сам.
                                val onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                    Unit
                                }
                                if (option.label == "—") {
                                    // Рисуем штрих линией, чтобы промежуток до соседей
                                    // был таким же, как у цифр (у глифа тире большие поля).
                                    Box(
                                        modifier = Modifier
                                            .width(96.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                            .clickable(
                                                onClickLabel = "Выключить таймер",
                                                onClick = onClick,
                                            )
                                            .padding(horizontal = 4.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            Modifier
                                                .width(40.dp)
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
                                                fontSize = 36.sp,
                                                lineHeight = 44.sp,
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
                                            .width(96.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                            .clickable(onClick = onClick)
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Точка-индикатор: напротив неё стоит выбранный таймер.
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
}

/** Индекс варианта, который стоит ровно напротив точки в центре карусели. */
private fun centeredSleepIndex(listState: androidx.compose.foundation.lazy.LazyListState): Int? {
    val info = listState.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { item ->
        kotlin.math.abs(item.offset + item.size / 2f - center)
    }?.index
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

/** «9 ч 44 мин» или «17 мин» — остаток до конца книги. */
private fun formatBookRemaining(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}

/** «12,3 МБ» — размер аудиофайла. */
private fun formatTrackSize(bytes: Long): String =
    String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0))

/** Кнопка перемотки: круговая стрелка, под ней — выбранный интервал в секундах. */
@Composable
private fun SeekButton(seconds: Int, forward: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Replay,
            contentDescription = if (forward) "Вперёд $seconds секунд" else "Назад $seconds секунд",
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer { scaleX = if (forward) -1f else 1f },
        )
        Text(
            text = "$seconds",
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current,
        )
    }
}
