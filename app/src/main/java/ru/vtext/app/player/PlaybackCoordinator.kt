package ru.vtext.app.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.SpeechEngine
import ru.vtext.app.tts.SpeechEvent

data class PlayerState(
    val bookId: String? = null,
    val bookTitle: String? = null,
    val author: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val chapterIndex: Int = 0,
    val segmentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isFinished: Boolean = false,
    val speed: Float = 1f,
    val fragmentPauseMs: Int = 0,
    val sleepTimerMinutes: Int? = null,
    /** Таймер сна «до конца главы». */
    val sleepUntilChapterEnd: Boolean = false,
    /** Закладки текущей книги. */
    val bookmarks: List<ru.vtext.app.data.Bookmark> = emptyList(),
    /** Голос Silero, действующий для текущей книги. */
    val sileroVoice: String? = null,
    val engineState: EngineState = EngineState.INITIALIZING,
    val error: String? = null,
    /** Индекс текущего произносимого слова во фрагменте (для подсветки). */
    val currentWord: Int? = null,
) {
    val chapter: Chapter? get() = chapters.getOrNull(chapterIndex)
    val segmentCount: Int get() = chapter?.segments?.size ?: 0
}

/**
 * Единый источник правды о воспроизведении. Живёт в Application (AppContainer),
 * поэтому переживает пересоздание Activity и используется foreground-сервисом.
 */
class PlaybackCoordinator(
    private val context: Context,
    private val progressStore: ProgressStore,
    private val settings: PlaybackSettingsStore,
    private val engine: SpeechEngine,
    private val bookSettings: ru.vtext.app.data.BookSettingsStore = ru.vtext.app.data.BookSettingsStore(context),
    private val bookmarkStore: ru.vtext.app.data.BookmarkStore = ru.vtext.app.data.BookmarkStore(context),
    private val tts: ru.vtext.app.tts.TtsManager? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        PlayerState(
            engineState = engine.state.value,
            speed = settings.speed,
            fragmentPauseMs = settings.fragmentPauseMs,
        ),
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var sleepJob: Job? = null
    private var advanceJob: Job? = null

    init {
        engine.setRate(settings.speed)
        scope.launch {
            engine.state.collect { engineState ->
                _state.update { it.copy(engineState = engineState) }
            }
        }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is SpeechEvent.Done -> if (_state.value.isPlaying) advance()
                    is SpeechEvent.Error -> _state.update {
                        it.copy(isPlaying = false, error = event.message ?: "Ошибка синтеза речи")
                    }
                    is SpeechEvent.Word -> {
                        val state = _state.value
                        val currentId = "${state.chapterIndex}:${state.segmentIndex}"
                        if (event.utteranceId == currentId && state.currentWord != event.index) {
                            _state.update { it.copy(currentWord = event.index) }
                        }
                    }
                    is SpeechEvent.Started, is SpeechEvent.Stopped -> Unit
                }
            }
        }
        scope.launch {
            // Смена движка (системный ↔ нейросетевой): перечитываем текущий фрагмент.
            engine.engineChanges.collect {
                val current = _state.value
                if (current.isPlaying) {
                    speakAt(current.chapterIndex, current.segmentIndex)
                }
            }
        }
    }

    fun load(book: Book, chapterIndex: Int, segmentIndex: Int, autoPlay: Boolean) {
        if (book.chapters.isEmpty()) return
        val safeChapter = chapterIndex.coerceIn(0, book.chapters.lastIndex)
        val segmentCount = book.chapters[safeChapter].segments.size
        val safeSegment = segmentIndex.coerceIn(0, (segmentCount - 1).coerceAtLeast(0))
        advanceJob?.cancel()
        engine.stop()
        val bookSpeed = bookSettings.speed(book.id) ?: settings.speed
        val bookVoice = bookSettings.voice(book.id)
        if (bookVoice != null) tts?.applySileroVoice(bookVoice)
        engine.setRate(bookSpeed)
        _state.update {
            it.copy(
                bookId = book.id,
                speed = bookSpeed,
                sileroVoice = tts?.selectedVoice?.value,
                bookmarks = bookmarkStore.list(book.id),
                bookTitle = book.title,
                author = book.author,
                chapters = book.chapters,
                chapterIndex = safeChapter,
                segmentIndex = safeSegment,
                isPlaying = false,
                isFinished = false,
                error = null,
                currentWord = null,
            )
        }
        saveProgress()
        if (autoPlay) {
            play()
        } else {
            book.chapters[safeChapter].segments.getOrNull(safeSegment)?.let { text ->
                engine.prefetch(text, "$safeChapter:$safeSegment")
            }
        }
    }

    fun play() {
        advanceJob?.cancel()
        var current = _state.value
        if (current.bookId == null) return
        if (current.isFinished) {
            // Книга дочитана: начинаем заново с первого фрагмента.
            _state.update {
                it.copy(chapterIndex = 0, segmentIndex = 0, isFinished = false)
            }
            saveProgress()
            current = _state.value
        }
        var chapterIndex = current.chapterIndex
        var segmentIndex = current.segmentIndex
        while (chapterIndex < current.chapters.size) {
            val segments = current.chapters[chapterIndex].segments
            if (segments.isNotEmpty()) {
                speakAt(chapterIndex, segmentIndex.coerceIn(0, segments.lastIndex))
                PlaybackService.start(context)
                return
            }
            chapterIndex += 1
            segmentIndex = 0
        }
    }

    fun pause() {
        advanceJob?.cancel()
        engine.stop()
        _state.update { it.copy(isPlaying = false) }
        saveProgress()
    }

    fun togglePlay() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun next() {
        val current = _state.value
        val chapter = current.chapter
        if (chapter != null && current.segmentIndex + 1 < chapter.segments.size) {
            goTo(current.chapterIndex, current.segmentIndex + 1)
        } else if (current.chapterIndex + 1 < current.chapters.size) {
            goTo(current.chapterIndex + 1, 0)
        }
    }

    fun previous() {
        val current = _state.value
        if (current.segmentIndex > 0) {
            goTo(current.chapterIndex, current.segmentIndex - 1)
        } else if (current.chapterIndex > 0) {
            val previousChapter = current.chapters[current.chapterIndex - 1]
            goTo(current.chapterIndex - 1, previousChapter.segments.lastIndex.coerceAtLeast(0))
        }
    }

    fun selectSegment(index: Int) {
        goTo(_state.value.chapterIndex, index)
    }

    fun selectChapter(index: Int) {
        goTo(index, 0)
    }

    fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(PlaybackSettingsStore.MIN_SPEED, PlaybackSettingsStore.MAX_SPEED)
        val bookId = _state.value.bookId
        if (bookId != null) bookSettings.setSpeed(bookId, safeSpeed) else settings.speed = safeSpeed
        engine.setRate(safeSpeed)
        _state.update { it.copy(speed = safeSpeed) }
    }

    /** Пауза между фрагментами книги, мс. */
    fun setFragmentPause(pauseMs: Int) {
        val safePause = pauseMs.coerceIn(
            PlaybackSettingsStore.MIN_FRAGMENT_PAUSE_MS,
            PlaybackSettingsStore.MAX_FRAGMENT_PAUSE_MS,
        )
        settings.fragmentPauseMs = safePause
        _state.update { it.copy(fragmentPauseMs = safePause) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _state.update { it.copy(sleepTimerMinutes = null, sleepUntilChapterEnd = false) }
            return
        }
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepUntilChapterEnd = false) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepTimerMinutes = null) }
        }
    }

    /** Остановиться, когда закончится текущая глава. */
    /** Голос Silero для текущей книги. */
    fun setSileroVoice(voiceId: String) {
        val bookId = _state.value.bookId
        if (bookId != null) bookSettings.setVoice(bookId, voiceId)
        tts?.applySileroVoice(voiceId)
        _state.update { it.copy(sileroVoice = voiceId) }
    }

    fun addBookmark() {
        val current = _state.value
        val bookId = current.bookId ?: return
        val text = current.chapters
            .getOrNull(current.chapterIndex)
            ?.segments
            ?.getOrNull(current.segmentIndex)
            ?: return
        val label = text.replace(Regex("\\s+"), " ").trim().take(80)
        val bookmark = ru.vtext.app.data.Bookmark(
            id = java.util.UUID.randomUUID().toString(),
            chapterIndex = current.chapterIndex,
            segmentIndex = current.segmentIndex,
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        val updated = bookmarkStore.add(bookId, bookmark)
        _state.update { it.copy(bookmarks = updated) }
    }

    fun removeBookmark(id: String) {
        val bookId = _state.value.bookId ?: return
        val updated = bookmarkStore.remove(bookId, id)
        _state.update { it.copy(bookmarks = updated) }
    }

    fun openBookmark(bookmark: ru.vtext.app.data.Bookmark) {
        goTo(bookmark.chapterIndex, bookmark.segmentIndex)
    }

    fun setSleepUntilChapterEnd() {
        sleepJob?.cancel()
        sleepJob = null
        _state.update { it.copy(sleepTimerMinutes = null, sleepUntilChapterEnd = true) }
    }

    fun stop() {
        advanceJob?.cancel()
        engine.stop()
        sleepJob?.cancel()
        sleepJob = null
        val speed = _state.value.speed
        val fragmentPause = _state.value.fragmentPauseMs
        _state.value = PlayerState(
            engineState = engine.state.value,
            speed = speed,
            fragmentPauseMs = fragmentPause,
        )
    }

    fun clearIfBook(bookId: String) {
        if (_state.value.bookId == bookId) stop()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun goTo(chapterIndex: Int, segmentIndex: Int) {
        advanceJob?.cancel()
        val current = _state.value
        val chapter = current.chapters.getOrNull(chapterIndex) ?: return
        if (chapter.segments.isEmpty()) return
        val safeSegment = segmentIndex.coerceIn(0, chapter.segments.lastIndex)
        // Случайный тап по уже звучащему фрагменту не должен начинать его заново.
        if (current.isPlaying && chapterIndex == current.chapterIndex && safeSegment == current.segmentIndex) {
            return
        }
        if (current.isPlaying) {
            speakAt(chapterIndex, safeSegment)
        } else {
            _state.update {
                it.copy(chapterIndex = chapterIndex, segmentIndex = safeSegment, isFinished = false)
            }
            saveProgress()
            chapter.segments.getOrNull(safeSegment)?.let { text ->
                engine.prefetch(text, "$chapterIndex:$safeSegment")
            }
        }
    }

    private fun speakAt(chapterIndex: Int, segmentIndex: Int) {
        advanceJob?.cancel()
        val text = _state.value.chapters
            .getOrNull(chapterIndex)
            ?.segments
            ?.getOrNull(segmentIndex)
            ?: return
        _state.update {
            it.copy(
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                isPlaying = true,
                isFinished = false,
                currentWord = null,
            )
        }
        saveProgress()
        engine.speak(text, "$chapterIndex:$segmentIndex")
        prefetchNext(chapterIndex, segmentIndex)
    }

    /** Заранее синтезируем следующий фрагмент, чтобы не было паузы после текущего. */
    private fun prefetchNext(chapterIndex: Int, segmentIndex: Int) {
        val chapters = _state.value.chapters
        var chapter = chapterIndex
        var segment = segmentIndex + 1
        while (chapter < chapters.size) {
            val segments = chapters[chapter].segments
            if (segment < segments.size) {
                engine.prefetch(segments[segment], "$chapter:$segment")
                return
            }
            chapter += 1
            segment = 0
        }
    }

    private fun advance() {
        val pauseMs = _state.value.fragmentPauseMs
        if (pauseMs <= 0) {
            advanceNow()
            return
        }
        advanceJob?.cancel()
        advanceJob = scope.launch {
            delay(pauseMs.toLong())
            advanceJob = null
            if (_state.value.isPlaying) {
                advanceNow()
            }
        }
    }

    private fun advanceNow() {
        val current = _state.value
        var chapterIndex = current.chapterIndex
        var segmentIndex = current.segmentIndex + 1
        while (chapterIndex < current.chapters.size) {
            val chapter = current.chapters[chapterIndex]
            if (segmentIndex < chapter.segments.size) {
                // Таймер сна «до конца главы»: дальше не переходим.
                if (current.sleepUntilChapterEnd && chapterIndex != current.chapterIndex) {
                    sleepJob?.cancel()
                    sleepJob = null
                    engine.stop()
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            sleepUntilChapterEnd = false,
                            sleepTimerMinutes = null,
                        )
                    }
                    return
                }
                speakAt(chapterIndex, segmentIndex)
                return
            }
            chapterIndex += 1
            segmentIndex = 0
        }
        finishBook()
    }

    /** Книга дочитана до конца: останавливаемся и предлагаем начать заново. */
    private fun finishBook() {
        sleepJob?.cancel()
        sleepJob = null
        pause()
        _state.update { it.copy(isFinished = true, sleepTimerMinutes = null) }
    }

    private fun saveProgress() {
        val current = _state.value
        val bookId = current.bookId ?: return
        progressStore.save(bookId, current.chapterIndex, current.segmentIndex)
    }
}
