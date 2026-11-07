package ru.vtext.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.TextPreset
import ru.vtext.app.player.PlaybackCoordinator

/** Позиция чтения для карточки в библиотеке: глава и фрагмент. */
data class ReadingPosition(val chapter: Int, val segment: Int) {
    val started: Boolean get() = chapter > 0 || segment > 0
}

enum class LibrarySort {
    RECENT,
    TITLE,
    AUTHOR,
}

data class LibraryUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val books: List<Book> = emptyList(),
    val positions: Map<String, ReadingPosition> = emptyMap(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val error: String? = null,
) {
    /** Книги после поиска и сортировки — то, что видно в сетке. */
    val visibleBooks: List<Book>
        get() = books
            .filter { book ->
                if (query.isBlank()) {
                    true
                } else {
                    book.title.contains(query.trim(), ignoreCase = true) ||
                        book.author?.contains(query.trim(), ignoreCase = true) == true
                }
            }
            .sortedWith(sort.comparator())

    val hasQuery: Boolean get() = query.isNotBlank()
}

private fun LibrarySort.comparator(): Comparator<Book> = when (this) {
    LibrarySort.RECENT -> compareByDescending { it.addedAt }
    LibrarySort.TITLE -> Comparator { first, second -> collator.compare(first.title, second.title) }
    LibrarySort.AUTHOR -> Comparator { first, second ->
        collator.compare(first.author.orEmpty(), second.author.orEmpty())
    }
}

private val collator: Collator = Collator.getInstance(Locale("ru", "RU"))

class LibraryViewModel(
    private val repository: BookRepository,
    private val progressStore: ProgressStore,
    private val playback: PlaybackCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val books = repository.listBooks()
            _state.update {
                it.copy(loading = false, books = books, positions = readPositions(books))
            }
        }
    }

    fun import(uris: List<android.net.Uri>, preset: TextPreset = TextPreset.AUTO) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, error = null) }
            var lastError: String? = null
            for (uri in uris) {
                try {
                    repository.import(uri, preset)
                } catch (error: Exception) {
                    lastError = error.message ?: "Не удалось импортировать книгу"
                }
            }
            val books = repository.listBooks()
            _state.update {
                it.copy(
                    importing = false,
                    loading = false,
                    books = books,
                    positions = readPositions(books),
                    error = lastError,
                )
            }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            repository.delete(book.id)
            progressStore.clear(book.id)
            playback.clearIfBook(book.id)
            val books = repository.listBooks()
            _state.update { it.copy(books = books, positions = readPositions(books)) }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun setSort(value: LibrarySort) {
        _state.update { it.copy(sort = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun readPositions(books: List<Book>): Map<String, ReadingPosition> =
        books.associate { book ->
            val (chapter, segment) = progressStore.load(book.id)
            book.id to ReadingPosition(chapter, segment)
        }

    companion object {
        fun factory(
            repository: BookRepository,
            progressStore: ProgressStore,
            playback: PlaybackCoordinator,
        ) = viewModelFactory {
            initializer { LibraryViewModel(repository, progressStore, playback) }
        }
    }
}
