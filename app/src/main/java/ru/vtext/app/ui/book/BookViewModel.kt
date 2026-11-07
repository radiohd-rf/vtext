package ru.vtext.app.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.domain.model.Book
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.SpeechEngine
import ru.vtext.app.tts.SpeechEvent

data class BookUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val segmentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val sleepTimerMinutes: Int? = null,
    val engineState: EngineState = EngineState.INITIALIZING,
    val error: String? = null,
)

class BookViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val progressStore: ProgressStore,
    private val engine: SpeechEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = _state.asStateFlow()

    private var sleepJob: Job? = null

    init {
        viewModelScope.launch {
            val book = repository.loadBook(bookId)
            if (book == null) {
                _state.update { it.copy(loading = false, error = "Книга не найдена") }
                return@launch
            }
            val (savedChapter, savedSegment) = progressStore.load(bookId)
            val chapterIndex = savedChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
            val segmentCount = book.chapters.getOrNull(chapterIndex)?.segments?.size ?: 0
            val segmentIndex = savedSegment.coerceIn(0, (segmentCount - 1).coerceAtLeast(0))
            _state.update {
                it.copy(
                    loading = false,
                    book = book,
                    chapterIndex = chapterIndex,
                    segmentIndex = segmentIndex,
                )
            }
        }

        viewModelScope.launch {
            engine.state.collect { engineState ->
                _state.update {
                    it.copy(
                        engineState = engineState,
                        error = if (engineState == EngineState.ERROR) {
                            "Системный синтез речи недоступен"
                        } else {
                            it.error
                        },
                    )
                }
            }
        }

        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is SpeechEvent.Done -> if (_state.value.isPlaying) advance()
                    is SpeechEvent.Error -> {
                        _state.update { it.copy(isPlaying = false, error = event.message ?: "Ошибка синтеза речи") }
                    }
                    is SpeechEvent.Started, is SpeechEvent.Stopped -> Unit
                }
            }
        }
    }

    fun togglePlay() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun pause() {
        engine.stop()
        _state.update { it.copy(isPlaying = false) }
        saveProgress()
    }

    fun next() {
        val current = _state.value
        val book = current.book ?: return
        val chapter = book.chapters.getOrNull(current.chapterIndex) ?: return
        if (current.segmentIndex + 1 < chapter.segments.size) {
            goTo(current.chapterIndex, current.segmentIndex + 1)
        } else if (current.chapterIndex + 1 < book.chapters.size) {
            goTo(current.chapterIndex + 1, 0)
        }
    }

    fun previous() {
        val current = _state.value
        val book = current.book ?: return
        if (current.segmentIndex > 0) {
            goTo(current.chapterIndex, current.segmentIndex - 1)
        } else if (current.chapterIndex > 0) {
            val previousChapter = book.chapters[current.chapterIndex - 1]
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
        engine.setRate(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _state.update { it.copy(sleepTimerMinutes = null) }
            return
        }
        _state.update { it.copy(sleepTimerMinutes = minutes) }
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepTimerMinutes = null) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun play() {
        val current = _state.value
        val book = current.book ?: return
        var chapterIndex = current.chapterIndex
        var segmentIndex = current.segmentIndex
        while (chapterIndex < book.chapters.size) {
            val chapter = book.chapters[chapterIndex]
            if (chapter.segments.isNotEmpty()) {
                speakAt(chapterIndex, segmentIndex.coerceIn(0, chapter.segments.lastIndex))
                return
            }
            chapterIndex += 1
            segmentIndex = 0
        }
    }

    private fun goTo(chapterIndex: Int, segmentIndex: Int) {
        val current = _state.value
        val chapter = current.book?.chapters?.getOrNull(chapterIndex) ?: return
        if (chapter.segments.isEmpty()) return
        val safeSegment = segmentIndex.coerceIn(0, chapter.segments.lastIndex)
        if (current.isPlaying) {
            speakAt(chapterIndex, safeSegment)
        } else {
            _state.update { it.copy(chapterIndex = chapterIndex, segmentIndex = safeSegment) }
            progressStore.save(bookId, chapterIndex, safeSegment)
        }
    }

    private fun speakAt(chapterIndex: Int, segmentIndex: Int) {
        val book = _state.value.book ?: return
        val text = book.chapters.getOrNull(chapterIndex)?.segments?.getOrNull(segmentIndex) ?: return
        _state.update {
            it.copy(
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                isPlaying = true,
            )
        }
        progressStore.save(bookId, chapterIndex, segmentIndex)
        engine.speak(text, "$chapterIndex:$segmentIndex")
    }

    private fun advance() {
        val current = _state.value
        val book = current.book ?: return
        var chapterIndex = current.chapterIndex
        var segmentIndex = current.segmentIndex + 1
        while (chapterIndex < book.chapters.size) {
            val chapter = book.chapters[chapterIndex]
            if (segmentIndex < chapter.segments.size) {
                speakAt(chapterIndex, segmentIndex)
                return
            }
            chapterIndex += 1
            segmentIndex = 0
        }
        pause()
    }

    private fun saveProgress() {
        val current = _state.value
        progressStore.save(bookId, current.chapterIndex, current.segmentIndex)
    }

    override fun onCleared() {
        engine.stop()
        sleepJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            bookId: String,
            repository: BookRepository,
            progressStore: ProgressStore,
            engine: SpeechEngine,
        ) = viewModelFactory {
            initializer { BookViewModel(bookId, repository, progressStore, engine) }
        }
    }
}
