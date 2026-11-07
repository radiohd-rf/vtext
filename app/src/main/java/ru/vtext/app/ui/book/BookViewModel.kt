package ru.vtext.app.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.player.PlayerState
import ru.vtext.app.player.PlaybackCoordinator

data class BookUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val error: String? = null,
    val player: PlayerState = PlayerState(),
) {
    val chapter: Chapter? get() = book?.chapters?.getOrNull(player.chapterIndex)
}

class BookViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val progressStore: ProgressStore,
    private val playback: PlaybackCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            playback.state.collect { player ->
                _state.update { it.copy(player = player) }
            }
        }

        viewModelScope.launch {
            val book = repository.loadBook(bookId)
            if (book == null) {
                _state.update { it.copy(loading = false, error = "Книга не найдена") }
                return@launch
            }
            if (playback.state.value.bookId != bookId) {
                val (savedChapter, _) = progressStore.load(bookId)
                val chapter = savedChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
                // Возобновляем с начала главы, а не с середины фрагмента.
                playback.load(book, chapter, 0, autoPlay = false)
            }
            _state.update { it.copy(loading = false, book = book) }
        }
    }

    fun togglePlay() = playback.togglePlay()
    fun pause() = playback.pause()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun selectSegment(index: Int) = playback.selectSegment(index)
    fun selectChapter(index: Int) = playback.selectChapter(index)
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun setSleepTimer(minutes: Int?) = playback.setSleepTimer(minutes)

    fun setSleepUntilChapterEnd() = playback.setSleepUntilChapterEnd()

    fun setVoice(voiceId: String) {
        playback.setSileroVoice(voiceId)
    }

    fun addBookmark() = playback.addBookmark()

    fun removeBookmark(id: String) = playback.removeBookmark(id)

    fun openBookmark(bookmark: ru.vtext.app.data.Bookmark) = playback.openBookmark(bookmark)

    fun dismissError() {
        _state.update { it.copy(error = null) }
        playback.dismissError()
    }

    companion object {
        fun factory(
            bookId: String,
            repository: BookRepository,
            progressStore: ProgressStore,
            playback: PlaybackCoordinator,
        ) = viewModelFactory {
            initializer { BookViewModel(bookId, repository, progressStore, playback) }
        }
    }
}
