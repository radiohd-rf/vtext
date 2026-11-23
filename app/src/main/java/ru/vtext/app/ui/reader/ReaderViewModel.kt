package ru.vtext.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.Bookmark
import ru.vtext.app.data.BookmarkStore
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.data.ReaderSettingsStore
import ru.vtext.app.domain.TextMarkup
import ru.vtext.app.domain.model.Book
import ru.vtext.app.tts.SileroText

/** Элемент ленты читалки: заголовок главы или абзац. */
sealed interface ReaderItem {
    val chapterIndex: Int

    data class Header(override val chapterIndex: Int, val title: String) : ReaderItem

    data class Paragraph(
        override val chapterIndex: Int,
        val segmentIndex: Int,
        val text: String,
    ) : ReaderItem
}

data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val items: List<ReaderItem> = emptyList(),
    /** Индекс элемента, с которого начинать после загрузки. */
    val startIndex: Int = 0,
    val chapterIndex: Int = 0,
    val segmentIndex: Int = 0,
    val style: ReaderStyle = ReaderStyle(),
    val bookmarks: List<Bookmark> = emptyList(),
    /** Индекс видимого элемента — по нему считаем прогресс. */
    val positionIndex: Int = 0,
    /** Стихи: строки идут отдельными абзацами, между ними нужен меньший отступ. */
    val verse: Boolean = false,
    val error: String? = null,
) {
    val chapterCount: Int get() = book?.chapters?.size ?: 0
    val chapterTitle: String? get() = book?.chapters?.getOrNull(chapterIndex)?.title
    val progress: Float
        get() = if (items.isEmpty()) 0f else (positionIndex.toFloat() / items.size).coerceIn(0f, 1f)
}

class ReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val progressStore: ProgressStore,
    private val bookmarks: BookmarkStore,
    private val settings: ReaderSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val book = repository.loadBook(bookId)
            if (book == null) {
                _state.update { it.copy(loading = false, error = "Книга не найдена") }
                return@launch
            }
            val items = buildItems(book)
            // Стихи собираем в отдельные строки; в читалке между ними нужен меньший отступ.
            // Стихи определяем по строкам только для PDF: у остальных форматов
            // абзацы уже размечены, и пресет задаёт пользователь.
            val verse = book.preset == ru.vtext.app.domain.model.TextPreset.POETRY ||
                (book.format == ru.vtext.app.domain.model.BookFormat.PDF &&
                    ru.vtext.app.domain.PoetryDetector.isPoetry(
                        book.chapters.take(3).joinToString("\n") { chapter ->
                            chapter.segments.joinToString("\n")
                        },
                    ))
            val (chapter, segment) = progressStore.load(bookId)
            val safeChapter = chapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
            val startIndex = items.indexOfFirst {
                it is ReaderItem.Paragraph &&
                    it.chapterIndex == safeChapter &&
                    it.segmentIndex == segment
            }.takeIf { it >= 0 } ?: chapterStartIndex(items, safeChapter)

            _state.value = ReaderUiState(
                loading = false,
                book = book,
                items = items,
                startIndex = startIndex,
                chapterIndex = safeChapter,
                segmentIndex = segment,
                style = settings.toStyle(),
                bookmarks = bookmarks.list(bookId),
                positionIndex = startIndex,
                verse = verse,
            )
        }
    }

    /** Вызывается при прокрутке: сохраняет позицию на текущем абзаце. */
    fun onPosition(item: ReaderItem?, index: Int) {
        val current = _state.value
        val resolved = when (item) {
            is ReaderItem.Paragraph -> item.chapterIndex to item.segmentIndex
            is ReaderItem.Header -> item.chapterIndex to 0
            null -> return
        }
        val samePlace = resolved.first == current.chapterIndex && resolved.second == current.segmentIndex
        if (samePlace && index == current.positionIndex) return
        if (!samePlace) progressStore.save(bookId, resolved.first, resolved.second)
        _state.update {
            it.copy(
                chapterIndex = resolved.first,
                segmentIndex = resolved.second,
                positionIndex = index,
            )
        }
    }

    fun itemIndexFor(chapterIndex: Int, segmentIndex: Int): Int {
        val items = _state.value.items
        val exact = items.indexOfFirst {
            it is ReaderItem.Paragraph && it.chapterIndex == chapterIndex && it.segmentIndex == segmentIndex
        }
        return if (exact >= 0) exact else chapterStartIndex(items, chapterIndex)
    }

    fun itemIndexForChapter(chapterIndex: Int): Int =
        chapterStartIndex(_state.value.items, chapterIndex)

    /** Явный переход (оглавление, закладка): фиксируем главу/абзац без опоры на прокрутку. */
    fun selectPosition(chapterIndex: Int, segmentIndex: Int) {
        val index = itemIndexFor(chapterIndex, segmentIndex)
        progressStore.save(bookId, chapterIndex, segmentIndex)
        _state.update {
            it.copy(
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                positionIndex = index,
            )
        }
    }

    fun setStyle(style: ReaderStyle) {
        settings.save(style)
        _state.update { it.copy(style = style) }
    }

    /** Закладка на текущем видимом абзаце. */
    fun addBookmark(item: ReaderItem?) {
        val current = _state.value
        val text = when (item) {
            is ReaderItem.Paragraph -> item.text
            else -> current.book?.chapters?.getOrNull(current.chapterIndex)
                ?.segments?.getOrNull(current.segmentIndex)
        } ?: return
        val label = text.trim().replace(Regex("\\s+"), " ").take(80)
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            chapterIndex = current.chapterIndex,
            segmentIndex = current.segmentIndex,
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        val updated = bookmarks.add(bookId, bookmark)
        _state.update { it.copy(bookmarks = updated) }
    }

    fun removeBookmark(id: String) {
        val updated = bookmarks.remove(bookId, id)
        _state.update { it.copy(bookmarks = updated) }
    }

    private fun buildItems(book: Book): List<ReaderItem> {
        val items = ArrayList<ReaderItem>()
        book.chapters.forEachIndexed { chapterIndex, chapter ->
            if (chapter.title.isNotBlank()) {
                items += ReaderItem.Header(chapterIndex, chapter.title)
            }
            chapter.segments.forEachIndexed { segmentIndex, segment ->
                val text = TextMarkup.strip(SileroText.displayText(segment)).trim()
                if (text.isNotEmpty()) {
                    items += ReaderItem.Paragraph(chapterIndex, segmentIndex, text)
                }
            }
        }
        return items
    }

    private fun chapterStartIndex(items: List<ReaderItem>, chapterIndex: Int): Int =
        items.indexOfFirst { it.chapterIndex == chapterIndex }.coerceAtLeast(0)

    companion object {
        fun factory(
            bookId: String,
            repository: BookRepository,
            progressStore: ProgressStore,
            bookmarks: BookmarkStore,
            settings: ReaderSettingsStore,
        ) = viewModelFactory {
            initializer { ReaderViewModel(bookId, repository, progressStore, bookmarks, settings) }
        }
    }
}
