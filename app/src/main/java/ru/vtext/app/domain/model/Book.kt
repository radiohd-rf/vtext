package ru.vtext.app.domain.model

/** Чем книга является для приложения: текстом для озвучки или готовым аудио. */
enum class BookKind { TEXT, AUDIO }

enum class BookFormat { TXT, FB2, EPUB, PDF, DOCX }

/** Глава книги, разбитая на короткие фрагменты — единицы озвучивания. */
data class Chapter(
    val title: String,
    val segments: List<String>,
    /** 0 — глава/раздел верхнего уровня, 1+ — подглава из оглавления PDF. */
    val depth: Int = 0,
    /** Номер страницы PDF (с нуля), если глава пришла из PDF; -1 для остальных форматов. */
    val page: Int = -1,
)

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    /** Для аудиокниг формат текста отсутствует: у них есть дорожки. */
    val format: BookFormat? = null,
    val addedAt: Long,
    val chapters: List<Chapter> = emptyList(),
    val preset: TextPreset = TextPreset.AUTO,
    val coverPath: String? = null,
    /** Число глав из meta.json: нужно библиотеке, где сами главы не загружаются. */
    val chapterCount: Int = 0,
    /** Вид книги. У книг, добавленных до появления поля, читается как TEXT. */
    val kind: BookKind = BookKind.TEXT,
    /** Для онлайн-аудиокниг — публичная ссылка Яндекс.Диска, откуда берутся дорожки. */
    val audioPublicKey: String? = null,
    /** Публичная папка подключённой облачной библиотеки (null — обычная книга). */
    val cloudKey: String? = null,
    /** Путь книги внутри облачной библиотеки: папка аудиокниги или файл текста. */
    val cloudPath: String? = null,
    /** Книга была в облачной библиотеке, но сейчас её там нет. */
    val cloudMissing: Boolean = false,
    /** Размер и дата изменения файла в облаке — чтобы не скачивать текст повторно. */
    val cloudSize: Long = 0L,
    val cloudModified: String? = null,
)

/** Результат разбора файла до сохранения в библиотеку. */
data class ParsedBook(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
)
