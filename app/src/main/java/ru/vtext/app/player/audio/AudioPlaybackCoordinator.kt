package ru.vtext.app.player.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.vtext.app.data.AudioBookmark
import ru.vtext.app.data.AudioBookmarkStore
import ru.vtext.app.data.AudioProgressStore
import ru.vtext.app.data.AudioRepository
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.YandexDiskClient
import ru.vtext.app.domain.model.AudioTrack
import ru.vtext.app.domain.model.Book
import kotlin.math.roundToInt

/** Состояние аудиоплеера. Отдельный тип: у TTS-плеера своя модель (глава + фрагмент). */
data class AudioPlayerState(
    val bookId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val coverPath: String? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val chapterIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val isFinished: Boolean = false,
    val sleepTimerMinutes: Int? = null,
    val sleepUntilChapterEnd: Boolean = false,
    /** Момент окончания таймера сна (мс с эпохи); null — таймер не задан. */
    val sleepEndsAt: Long? = null,
    val bookmarks: List<AudioBookmark> = emptyList(),
    val error: String? = null,
    /** Публичная ссылка Яндекс.Диска для онлайн-книги; null у локальных. */
    val audioPublicKey: String? = null,
) {
    val chapterTitle: String? get() = tracks.getOrNull(chapterIndex)?.title
    val chapterCount: Int get() = tracks.size
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Единый источник правды о воспроизведении аудиокниг — по образцу TTS-координатора.
 * Живёт в Application, сервис и UI только читают состояние и вызывают методы.
 */
class AudioPlaybackCoordinator(
    context: Context,
    private val books: BookRepository,
    private val audio: AudioRepository,
    private val progress: AudioProgressStore,
    private val settings: PlaybackSettingsStore,
    private val bookmarkStore: AudioBookmarkStore,
    private val yandex: YandexDiskClient,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /**
     * Онлайн-дорожки описаны схемой `yandex://…`: перед открытием потока
     * источник заменяет её на временную ссылку downloader.disk.yandex.ru.
     */
    private val remoteResolver = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
            val uri = dataSpec.uri
            if (uri.scheme != REMOTE_SCHEME) return dataSpec
            val key = uri.getQueryParameter("key") ?: return dataSpec
            val path = uri.getQueryParameter("path") ?: return dataSpec
            val href = runBlocking { yandex.resolveHref(key, path) }
            return dataSpec.withUri(Uri.parse(href))        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(DefaultDataSource.Factory(appContext), remoteResolver),
            ),
        )
        .build()

    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    /** Клиент к собственной сессии: гарантирует, что сервис поднят до старта звука. */
    private var controller: androidx.media3.session.MediaController? = null
    private var connecting = false
    private var pendingAction: (() -> Unit)? = null

    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var durationProbe: Job? = null
    private var pauseAtChapterEnd = false
    private var lastSavedMs = 0L
    private var retriedRemote = false

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    retriedRemote = false
                    startTicker()
                } else {
                    stopTicker()
                }
                if (!isPlaying) savePosition()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                _state.update {
                    it.copy(
                        chapterIndex = index,
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = it.tracks.getOrNull(index)?.durationMs ?: 0L,
                        isFinished = false,
                    )
                }
                if (pauseAtChapterEnd) {
                    pauseAtChapterEnd = false
                    player.pause()
                    _state.update { it.copy(sleepUntilChapterEnd = false) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _state.update { it.copy(isFinished = true, isPlaying = false) }
                    savePosition()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("AudioPlayback", "player error", error)
                val key = _state.value.audioPublicKey
                val track = _state.value.tracks.getOrNull(player.currentMediaItemIndex)
                // Временная ссылка Яндекса могла истечь: сбрасываем кэш и пробуем ещё раз.
                if (key != null && track?.remote == true && !retriedRemote && isExpiredHref(error)) {
                    retriedRemote = true
                    scope.launch {
                        yandex.invalidateHref(key, track.path)
                        player.prepare()
                        player.play()
                    }
                    return
                }
                _state.update { it.copy(error = error.message, isPlaying = false) }
            }
        })
    }

    /** Открыть книгу и, при необходимости, начать воспроизведение. */
    fun load(bookId: String, autoPlay: Boolean) {
        durationProbe?.cancel()
        // Позицию прошлой книги сохраняем до пересборки плейлиста: иначе события
        // нового плейлиста успевают записаться под старым bookId.
        savePosition()
        stopTicker()
        _state.value = AudioPlayerState()
        scope.launch {
            val book = books.loadBook(bookId) ?: return@launch
            val tracks = if (book.audioPublicKey != null) {
                // Перед открытием перечитываем папку: в неё могли добавить файлы.
                audio.refreshRemoteTracks(bookId)
            } else {
                audio.tracks(bookId)
            }
            if (tracks.isEmpty()) {
                _state.value = AudioPlayerState(
                    bookId = bookId,
                    title = book.title,
                    author = book.author,
                    error = "Файлы книги недоступны",
                )
                return@launch
            }

            val saved = progress.get(bookId)
            val startChapter = (saved?.chapterIndex ?: 0).coerceIn(0, tracks.lastIndex)
            val startPosition = ((saved?.positionMs ?: 0L) - RESUME_REWIND_MS).coerceAtLeast(0L)

            val speed = SpeedPolicy.snap(settings.speed)
            player.setPlaybackSpeed(speed)
            preparePlayer(book, tracks, startChapter, startPosition)

            _state.value = AudioPlayerState(
                bookId = bookId,
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                tracks = tracks,
                chapterIndex = startChapter,
                positionMs = startPosition,
                durationMs = tracks[startChapter].durationMs,
                speed = speed,
                bookmarks = bookmarkStore.bookmarks(bookId),
                audioPublicKey = book.audioPublicKey,
            )

            book.audioPublicKey?.let { probeDurations(bookId, it) }
            if (autoPlay) player.play()
        }
    }

    /**
     * Перечитывает папку онлайн-книги при повторном открытии. Если состав дорожек
     * изменился, плейлист пересобирается с сохранением текущего места.
     */
    fun refreshTracks(bookId: String) {
        scope.launch {
            val book = books.loadBook(bookId) ?: return@launch
            if (book.audioPublicKey == null) return@launch
            val before = _state.value.tracks
            val after = audio.refreshRemoteTracks(bookId)
            if (after.map { it.path } == before.map { it.path }) return@launch

            val currentPath = before.getOrNull(_state.value.chapterIndex)?.path
            val wasPlaying = player.isPlaying
            val position = player.currentPosition.coerceAtLeast(0L)
            val found = after.indexOfFirst { it.path == currentPath }
            val targetIndex = if (found >= 0) found else 0
            val targetPosition = if (found >= 0) position else 0L

            preparePlayer(book, after, targetIndex, targetPosition)
            _state.update {
                it.copy(
                    tracks = after,
                    chapterIndex = targetIndex,
                    positionMs = targetPosition,
                    durationMs = after[targetIndex].durationMs,
                    audioPublicKey = book.audioPublicKey,
                )
            }
            if (wasPlaying) player.play()
        }
    }

    /**
     * У онлайн-книг длительности дорожек неизвестны, пока их не проиграешь.
     * Дозапрашиваем метаданные в фоне, чтобы счётчик «до конца книги» был точным,
     * и сохраняем результат в tracks.json.
     */
    private fun probeDurations(bookId: String, publicKey: String) {
        durationProbe?.cancel()
        durationProbe = scope.launch(Dispatchers.IO) {
            val pending = _state.value.tracks.mapIndexedNotNull { index, track ->
                if (track.durationMs <= 0L) index to track else null
            }
            if (pending.isEmpty()) return@launch
            var changed = false
            for ((index, track) in pending) {
                if (!isActive) break
                val duration = runCatching {
                    val href = yandex.resolveHref(publicKey, track.path)
                    readDurationMs(href)
                }.getOrDefault(0L)
                if (duration > 0L) {
                    _state.update { state ->
                        if (state.bookId != bookId) return@update state
                        val list = state.tracks.toMutableList()
                        if (index in list.indices) {
                            list[index] = list[index].copy(durationMs = duration)
                        }
                        state.copy(tracks = list)
                    }
                    changed = true
                }
            }
            if (changed && _state.value.bookId == bookId) {
                audio.saveTracks(bookId, _state.value.tracks)
            }
        }
    }

    private fun readDurationMs(href: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(href, emptyMap<String, String>())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun preparePlayer(book: Book, tracks: List<AudioTrack>, chapter: Int, position: Long) {
        val publicKey = book.audioPublicKey
        val safeChapter = chapter.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(
            tracks.map { track ->
                val uri = if (track.remote && publicKey != null) {
                    remoteUri(publicKey, track.path)
                } else {
                    Uri.fromFile(File(track.path))
                }
                MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(mimeTypeOf(track))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(book.author)
                            .build(),
                    )
                    .build()
            },
            safeChapter,
            position.coerceAtLeast(0L),
        )
        player.prepare()
    }

    fun play() {
        if (_state.value.isFinished) {
            player.seekTo(0, 0L)
            _state.update { it.copy(isFinished = false) }
        }
        withService { player.play() }
    }

    /**
     * Поднимает сервис через MediaController и только потом выполняет действие.
     * Без этого сессия создаётся после старта воспроизведения и уведомление не появляется.
     */
    private fun withService(action: () -> Unit) {
        if (controller != null) {
            action()
            return
        }
        if (connecting) {
            pendingAction = action
            return
        }
        connecting = true
        val token = androidx.media3.session.SessionToken(
            appContext,
            android.content.ComponentName(appContext, AudioPlaybackService::class.java),
        )
        val future = androidx.media3.session.MediaController.Builder(appContext, token)
            .buildAsync()
        future.addListener({
            runCatching {
                controller = future.get()
                connecting = false
                action()
                pendingAction?.invoke()
                pendingAction = null
            }.onFailure {
                connecting = false
                pendingAction = null
                _state.update { state -> state.copy(error = it.message) }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
    }

    fun pause() = player.pause()

    fun togglePlay() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun nextChapter() {
        val next = (_state.value.chapterIndex + 1).coerceAtMost(player.mediaItemCount - 1)
        if (next != _state.value.chapterIndex) player.seekTo(next, 0L)
    }

    fun previousChapter() {
        val position = _state.value.positionMs
        val index = _state.value.chapterIndex
        if (position > RESTART_THRESHOLD_MS || index == 0) {
            player.seekTo(index, 0L)
        } else {
            player.seekTo(index - 1, 0L)
        }
    }

    fun selectChapter(index: Int) {
        if (index in 0 until player.mediaItemCount) player.seekTo(index, 0L)
    }

    /** Скорость меняется на ходу и сохраняется глобально — общей для аудио и текста. */
    fun setSpeed(value: Float) {
        val snapped = SpeedPolicy.snap(value)
        player.setPlaybackSpeed(snapped)
        _state.update { it.copy(speed = snapped) }
        settings.speed = snapped
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        val endsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        _state.update {
            it.copy(
                sleepTimerMinutes = minutes,
                sleepUntilChapterEnd = false,
                sleepEndsAt = endsAt,
            )
        }
        if (minutes == null) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            fadeOutAndPause()
        }
    }

    fun setSleepUntilChapterEnd() {
        sleepJob?.cancel()
        sleepJob = null
        pauseAtChapterEnd = true
        _state.update {
            it.copy(
                sleepTimerMinutes = null,
                sleepUntilChapterEnd = true,
                sleepEndsAt = null,
            )
        }
    }

    fun addBookmark(label: String): AudioBookmark? {
        val current = _state.value
        val bookId = current.bookId ?: return null
        val bookmark = bookmarkStore.add(
            bookId = bookId,
            chapterIndex = current.chapterIndex,
            positionMs = current.positionMs,
            label = label,
        )
        _state.update { it.copy(bookmarks = bookmarkStore.bookmarks(bookId)) }
        return bookmark
    }

    fun removeBookmark(id: String) {
        val bookId = _state.value.bookId ?: return
        bookmarkStore.remove(bookId, id)
        _state.update { it.copy(bookmarks = bookmarkStore.bookmarks(bookId)) }
    }

    fun jumpToBookmark(bookmark: AudioBookmark) {
        player.seekTo(bookmark.chapterIndex, bookmark.positionMs)
    }

    /** Останавливает плеер, если сейчас играет указанная книга. */
    fun clearIfBook(bookId: String) {
        if (_state.value.bookId == bookId) stop()
    }

    fun stop() {
        savePosition()
        player.pause()
        player.clearMediaItems()
        _state.value = AudioPlayerState()
    }

    fun release() {
        savePosition()
        ticker?.cancel()
        sleepJob?.cancel()
        durationProbe?.cancel()
        controller?.release()
        controller = null
        player.release()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val position = player.currentPosition.coerceAtLeast(0)
                val duration = player.duration.takeIf { it > 0 }
                    ?: _state.value.tracks.getOrNull(player.currentMediaItemIndex)?.durationMs
                    ?: 0L
                val index = player.currentMediaItemIndex
                _state.update {
                    it.copy(
                        chapterIndex = index,
                        positionMs = position,
                        durationMs = duration,
                    )
                }
                // У онлайн-дорожек длительность неизвестна до открытия потока — запоминаем её.
                if (duration > 0) {
                    val track = _state.value.tracks.getOrNull(index)
                    if (track != null && track.durationMs != duration) {
                        _state.update { current ->
                            val list = current.tracks.toMutableList()
                            if (index in list.indices) {
                                list[index] = list[index].copy(durationMs = duration)
                            }
                            current.copy(tracks = list)
                        }
                    }
                }
                if (position - lastSavedMs >= SAVE_EVERY_MS) savePosition()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun savePosition() {
        val current = _state.value
        val bookId = current.bookId ?: return
        progress.save(
            bookId,
            current.chapterIndex,
            current.positionMs,
            bookPercent(current),
        )
        lastSavedMs = current.positionMs
    }

    /**
     * Процент книги: суммарное время до текущей позиции к общему времени дорожек.
     * Если длительности известны не у всех дорожек (онлайн-книга ещё не открывалась),
     * считаем по номеру главы и доле внутри текущей.
     */
    private fun bookPercent(state: AudioPlayerState): Int {
        val tracks = state.tracks
        if (tracks.isEmpty()) return 0
        val total = tracks.sumOf { it.durationMs }
        val allKnown = total > 0L && tracks.all { it.durationMs > 0L }
        if (allKnown) {
            val before = tracks.take(state.chapterIndex).sumOf { it.durationMs }
            val done = (before + state.positionMs.coerceAtLeast(0L)).coerceIn(0L, total)
            return (done * 100.0 / total).roundToInt().coerceIn(0, 100)
        }
        val currentDuration = state.durationMs
        val inside = if (currentDuration > 0L) {
            (state.positionMs.toFloat() / currentDuration).coerceIn(0f, 1f)
        } else {
            0f
        }
        return ((state.chapterIndex + inside) * 100.0 / tracks.size).roundToInt().coerceIn(0, 99)
    }

    private suspend fun fadeOutAndPause() {
        val steps = 10
        repeat(steps) { step ->
            player.volume = 1f - (step + 1f) / steps
            delay(150)
        }
        player.pause()
        player.volume = 1f
        _state.update { it.copy(sleepTimerMinutes = null, sleepEndsAt = null) }
    }

    private fun remoteUri(publicKey: String, path: String): Uri =
        Uri.Builder()
            .scheme(REMOTE_SCHEME)
            .authority("disk")
            .appendQueryParameter("key", publicKey)
            .appendQueryParameter("path", path)
            .build()

    private fun mimeTypeOf(track: AudioTrack): String? =
        when (track.path.substringAfterLast('.', "").lowercase()) {
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "m4b", "mp4" -> MimeTypes.AUDIO_MP4
            "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus" -> MimeTypes.AUDIO_OPUS
            "wav" -> MimeTypes.AUDIO_WAV
            else -> null
        }

    private fun isExpiredHref(error: Throwable?): Boolean {
        var cause = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                cause.responseCode in 401..410
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 5_000L
        const val RESUME_REWIND_MS = 3_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val REMOTE_SCHEME = "yandex"
    }
}
