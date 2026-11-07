package ru.vtext.app.domain.model

enum class BookFormat { TXT, FB2, EPUB }

/** Глава книги, разбитая на короткие фрагменты — единицы озвучивания. */
data class Chapter(
    val title: String,
    val segments: List<String>,
)

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val addedAt: Long,
    val chapters: List<Chapter> = emptyList(),
)

/** Результат разбора файла до сохранения в библиотеку. */
data class ParsedBook(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
)
