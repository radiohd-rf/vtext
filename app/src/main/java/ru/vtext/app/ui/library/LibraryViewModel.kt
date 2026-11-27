package ru.vtext.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.AnnotationStore
import ru.vtext.app.data.AudioProgressStore
import ru.vtext.app.data.AudioRepository
import ru.vtext.app.data.BookFlags
import ru.vtext.app.data.BookFlagsStore
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.BookStatus
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookKind
import ru.vtext.app.domain.model.TextPreset
import ru.vtext.app.player.PlaybackCoordinator

/** Позиция чтения для карточки в библиотеке: глава и фрагмент. */
data class ReadingPosition(val chapter: Int, val segment: Int, val percent: Int = 0) {
    val started: Boolean get() = percent > 0 || chapter > 0 || segment > 0
}

enum class LibrarySort {
    RECENT,
    TITLE,
    AUTHOR,
}

/** Фильтр полки под поиском. */
enum class LibraryFilter {
    ALL,
    FAVORITE,
    WANT,
    READ,
}

data class LibraryUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val books: List<Book> = emptyList(),
    val positions: Map<String, ReadingPosition> = emptyMap(),
    val flags: Map<String, BookFlags> = emptyMap(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val selected: Set<String> = emptySet(),
    val error: String? = null,
) {
    /** Книги после поиска, фильтра и сортировки — то, что видно в списке. */
    val visibleBooks: List<Book>
        get() = books
            .filter { book ->
                matchesQuery(book) && filter.matches(flags[book.id] ?: BookFlags())
            }
            .sortedWith(sort.comparator(flags))

    val hasQuery: Boolean get() = query.isNotBlank()

    /** Режим выбора: хотя бы одна книга отмечена. */
    val selecting: Boolean get() = selected.isNotEmpty()

    fun flagsOf(book: Book): BookFlags = flags[book.id] ?: BookFlags()

    private fun matchesQuery(book: Book): Boolean {
        if (query.isBlank()) return true
        val value = query.trim()
        return book.title.contains(value, ignoreCase = true) ||
            book.author?.contains(value, ignoreCase = true) == true
    }
}

private fun LibraryFilter.matches(flags: BookFlags): Boolean = when (this) {
    LibraryFilter.ALL -> true
    LibraryFilter.FAVORITE -> flags.favorite
    LibraryFilter.WANT -> flags.status == BookStatus.WANT
    LibraryFilter.READ -> flags.status == BookStatus.READ
}

private fun LibrarySort.comparator(flags: Map<String, BookFlags>): Comparator<Book> = when (this) {
    // «Недавние» — по времени последнего открытия; если книгу ещё не
    // открывали, берём дату добавления.
    LibrarySort.RECENT -> compareByDescending { book ->
        flags[book.id]?.lastOpenedAt?.takeIf { it > 0L } ?: book.addedAt
    }
    LibrarySort.TITLE -> Comparator { first, second -> collator.compare(first.title, second.title) }
    LibrarySort.AUTHOR -> Comparator { first, second ->
        collator.compare(first.author.orEmpty(), second.author.orEmpty())
    }
}

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("ru-RU"))

class LibraryViewModel(
    private val repository: BookRepository,
    private val progressStore: ProgressStore,
    private val playback: PlaybackCoordinator,
    private val audioRepository: AudioRepository,
    private val audioProgress: AudioProgressStore,
    private val bookFlagsStore: BookFlagsStore,
    private val annotationStore: AnnotationStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Перечитывание идёт; повторные запросы (их много при синке) склеиваем. */
    private var refreshing = false
    private var refreshAgain = false

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing) {
            refreshAgain = true
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                // Спиннер только пока книг нет: при возврате в список он уже на экране.
                _state.update { it.copy(loading = it.books.isEmpty()) }
                val books = repository.listBooks()
                val ids = books.mapTo(HashSet()) { it.id }
                _state.update {
                    it.copy(
                        loading = false,
                        books = books,
                        positions = readPositions(books),
                        flags = bookFlagsStore.all(),
                        selected = it.selected.filterTo(HashSet()) { id -> id in ids },
                    )
                }
                // Обложки, которых ещё нет, достаём в фоне и тихо подставляем.
                if (repository.ensureCovers()) {
                    val updated = repository.listBooks()
                    _state.update {
                        it.copy(books = updated, positions = readPositions(updated))
                    }
                }
            } finally {
                refreshing = false
                if (refreshAgain) {
                    refreshAgain = false
                    refresh()
                }
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
                    flags = bookFlagsStore.all(),
                    error = lastError,
                )
            }
        }
    }

    /**
     * Импорт аудиокниг.
     *
     * @param asOneBook true — все выбранные файлы станут дорожками одной книги.
     */
    fun importAudio(uris: List<android.net.Uri>, asOneBook: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, error = null) }
            val error = try {
                audioRepository.import(uris, asOneBook)
                null
            } catch (exception: Exception) {
                exception.message ?: "Не удалось импортировать аудиокнигу"
            }
            val books = repository.listBooks()
            _state.update {
                it.copy(
                    importing = false,
                    loading = false,
                    books = books,
                    positions = readPositions(books),
                    flags = bookFlagsStore.all(),
                    error = error,
                )
            }
        }
    }

    /** Онлайн-аудиокнига из публичной папки Яндекс.Диска: файлы не скачиваются. */
    fun importYandex(publicKey: String) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, error = null) }
            val error = try {
                audioRepository.importFromYandex(publicKey)
                null
            } catch (exception: Exception) {
                exception.message ?: "Не удалось добавить аудиокнигу по ссылке"
            }
            val books = repository.listBooks()
            _state.update {
                it.copy(
                    importing = false,
                    loading = false,
                    books = books,
                    positions = readPositions(books),
                    flags = bookFlagsStore.all(),
                    error = error,
                )
            }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            deleteInternal(book.id)
            val books = repository.listBooks()
            _state.update {
                it.copy(
                    books = books,
                    positions = readPositions(books),
                    flags = bookFlagsStore.all(),
                    selected = it.selected - book.id,
                )
            }
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> deleteInternal(id) }
            val books = repository.listBooks()
            _state.update {
                it.copy(
                    books = books,
                    positions = readPositions(books),
                    flags = bookFlagsStore.all(),
                    selected = emptySet(),
                )
            }
        }
    }

    private suspend fun deleteInternal(bookId: String) {
        repository.delete(bookId)
        progressStore.clear(bookId)
        audioProgress.clear(bookId)
        playback.clearIfBook(bookId)
        bookFlagsStore.clear(bookId)
        annotationStore.clear(bookId)
    }

    /** Открытие книги: запоминаем время — по нему строится сортировка «недавние». */
    fun markOpened(bookId: String) {
        bookFlagsStore.markOpened(bookId)
        _state.update { state ->
            val flags = state.flags.toMutableMap()
            flags[bookId] = bookFlagsStore.get(bookId)
            state.copy(flags = flags)
        }
    }

    fun toggleFavorite(book: Book) {
        val value = !_state.value.flagsOf(book).favorite
        bookFlagsStore.setFavorite(book.id, value)
        updateFlags(book.id)
    }

    fun setStatus(book: Book, status: BookStatus) {
        bookFlagsStore.setStatus(book.id, status)
        updateFlags(book.id)
    }

    /** Пометка, которая уже стоит, снимается повторным выбором. */
    fun toggleStatus(book: Book, status: BookStatus) {
        val current = _state.value.flagsOf(book).status
        setStatus(book, if (current == status) BookStatus.NONE else status)
    }

    fun updateDetails(book: Book, title: String, author: String?) {
        viewModelScope.launch {
            repository.updateDetails(book.id, title, author)
            val books = repository.listBooks()
            _state.update { it.copy(books = books) }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun setSort(value: LibrarySort) {
        _state.update { it.copy(sort = value) }
    }

    fun setFilter(value: LibraryFilter) {
        _state.update { it.copy(filter = value) }
    }

    fun startSelection(book: Book) {
        _state.update { it.copy(selected = setOf(book.id)) }
    }

    fun toggleSelection(bookId: String) {
        _state.update { state ->
            val selected = if (bookId in state.selected) {
                state.selected - bookId
            } else {
                state.selected + bookId
            }
            state.copy(selected = selected)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun updateFlags(bookId: String) {
        _state.update { state ->
            val flags = state.flags.toMutableMap()
            flags[bookId] = bookFlagsStore.get(bookId)
            state.copy(flags = flags)
        }
    }

    private fun readPositions(books: List<Book>): Map<String, ReadingPosition> =
        books.associate { book ->
            if (book.kind == BookKind.AUDIO) {
                val saved = audioProgress.get(book.id)
                val chapter = saved?.chapterIndex ?: 0
                val percent = saved?.percent?.takeIf { it > 0 }
                    ?: fallbackPercent(chapter, book.chapterCount)
                book.id to ReadingPosition(chapter, 0, percent)
            } else {
                val (chapter, segment) = progressStore.load(book.id)
                val percent = progressStore.percent(book.id)
                    ?: fallbackPercent(chapter, book.chapterCount)
                book.id to ReadingPosition(chapter, segment, percent)
            }
        }

    /**
     * Оценка для старых записей без процента: сколько глав пройдено.
     * Точный процент пишется плеером и читалкой при сохранении позиции.
     */
    private fun fallbackPercent(chapter: Int, chapterCount: Int): Int {
        if (chapter <= 0 || chapterCount <= 0) return 0
        return (chapter * 100.0 / chapterCount).roundToInt().coerceIn(1, 99)
    }

    companion object {
        fun factory(
            repository: BookRepository,
            progressStore: ProgressStore,
            playback: PlaybackCoordinator,
            audioRepository: AudioRepository,
            audioProgress: AudioProgressStore,
            bookFlags: BookFlagsStore,
            annotations: AnnotationStore,
        ) = viewModelFactory {
            initializer {
                LibraryViewModel(
                    repository,
                    progressStore,
                    playback,
                    audioRepository,
                    audioProgress,
                    bookFlags,
                    annotations,
                )
            }
        }
    }
}
