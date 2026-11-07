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
    val sleepTimerMinutes: Int? = null,
    val engineState: EngineState = EngineState.INITIALIZING,
    val error: String? = null,
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        PlayerState(engineState = engine.state.value, speed = settings.speed),
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var sleepJob: Job? = null

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
                    is SpeechEvent.Started, is SpeechEvent.Stopped -> Unit
                }
            }
        }
    }

    fun load(book: Book, chapterIndex: Int, segmentIndex: Int, autoPlay: Boolean) {
        if (book.chapters.isEmpty()) return
        val safeChapter = chapterIndex.coerceIn(0, book.chapters.lastIndex)
        val segmentCount = book.chapters[safeChapter].segments.size
        val safeSegment = segmentIndex.coerceIn(0, (segmentCount - 1).coerceAtLeast(0))
        engine.stop()
        _state.update {
            it.copy(
                bookId = book.id,
                bookTitle = book.title,
                author = book.author,
                chapters = book.chapters,
                chapterIndex = safeChapter,
                segmentIndex = safeSegment,
                isPlaying = false,
                isFinished = false,
                error = null,
            )
        }
        saveProgress()
        if (autoPlay) play()
    }

    fun play() {
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
        settings.speed = safeSpeed
        engine.setRate(safeSpeed)
        _state.update { it.copy(speed = safeSpeed) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _state.update { it.copy(sleepTimerMinutes = null) }
            return
        }
        _state.update { it.copy(sleepTimerMinutes = minutes) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepTimerMinutes = null) }
        }
    }

    fun stop() {
        engine.stop()
        sleepJob?.cancel()
        sleepJob = null
        val speed = _state.value.speed
        _state.value = PlayerState(engineState = engine.state.value, speed = speed)
    }

    fun clearIfBook(bookId: String) {
        if (_state.value.bookId == bookId) stop()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun goTo(chapterIndex: Int, segmentIndex: Int) {
        val current = _state.value
        val chapter = current.chapters.getOrNull(chapterIndex) ?: return
        if (chapter.segments.isEmpty()) return
        val safeSegment = segmentIndex.coerceIn(0, chapter.segments.lastIndex)
        if (current.isPlaying) {
            speakAt(chapterIndex, safeSegment)
        } else {
            _state.update {
                it.copy(chapterIndex = chapterIndex, segmentIndex = safeSegment, isFinished = false)
            }
            saveProgress()
        }
    }

    private fun speakAt(chapterIndex: Int, segmentIndex: Int) {
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
            )
        }
        saveProgress()
        engine.speak(text, "$chapterIndex:$segmentIndex")
    }

    private fun advance() {
        val current = _state.value
        var chapterIndex = current.chapterIndex
        var segmentIndex = current.segmentIndex + 1
        while (chapterIndex < current.chapters.size) {
            val chapter = current.chapters[chapterIndex]
            if (segmentIndex < chapter.segments.size) {
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
