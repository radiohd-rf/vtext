package ru.vtext.app.ui.library

import android.net.Uri
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
import ru.vtext.app.domain.model.Book

data class LibraryUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val books: List<Book> = emptyList(),
    val error: String? = null,
)

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val books = repository.listBooks()
            _state.update { it.copy(loading = false, books = books) }
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, error = null) }
            var lastError: String? = null
            for (uri in uris) {
                try {
                    repository.import(uri)
                } catch (error: Exception) {
                    lastError = error.message ?: "Не удалось импортировать книгу"
                }
            }
            val books = repository.listBooks()
            _state.update {
                it.copy(importing = false, loading = false, books = books, error = lastError)
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        fun factory(repository: BookRepository) = viewModelFactory {
            initializer { LibraryViewModel(repository) }
        }
    }
}
