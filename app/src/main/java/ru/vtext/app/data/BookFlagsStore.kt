package ru.vtext.app.data

import android.content.Context

/** Статус книги в библиотеке: обычная, «хочу прочитать» или прочитанная. */
enum class BookStatus {
    NONE,
    WANT,
    READ;

    companion object {
        fun fromId(id: String): BookStatus =
            entries.firstOrNull { it.name == id } ?: NONE
    }
}

/** Пометки книги: избранное, статус и время последнего открытия. */
data class BookFlags(
    val favorite: Boolean = false,
    val status: BookStatus = BookStatus.NONE,
    val lastOpenedAt: Long = 0L,
)

/**
 * Пометки библиотеки (избранное, «хочу прочитать», «прочитано») и время
 * последнего открытия. Хранятся отдельно от meta.json: так их можно менять,
 * не перезаписывая разобранную книгу и не сбрасывая кэш глав.
 */
class BookFlagsStore(context: Context) {

    private val prefs = context.getSharedPreferences("book_flags", Context.MODE_PRIVATE)

    fun all(): Map<String, BookFlags> {
        val result = HashMap<String, BookFlags>()
        val ids = HashSet<String>()
        prefs.all.keys.forEach { key ->
            when {
                key.startsWith(FAVORITE) -> ids += key.removePrefix(FAVORITE)
                key.startsWith(STATUS) -> ids += key.removePrefix(STATUS)
                key.startsWith(OPENED) -> ids += key.removePrefix(OPENED)
            }
        }
        ids.forEach { id -> get(id).let { flags -> result[id] = flags } }
        return result
    }

    fun get(bookId: String): BookFlags = BookFlags(
        favorite = prefs.getBoolean(FAVORITE + bookId, false),
        status = BookStatus.fromId(prefs.getString(STATUS + bookId, null).orEmpty()),
        lastOpenedAt = prefs.getLong(OPENED + bookId, 0L),
    )

    fun setFavorite(bookId: String, value: Boolean) {
        prefs.edit().putBoolean(FAVORITE + bookId, value).apply()
    }

    fun setStatus(bookId: String, status: BookStatus) {
        if (status == BookStatus.NONE) {
            prefs.edit().remove(STATUS + bookId).apply()
        } else {
            prefs.edit().putString(STATUS + bookId, status.name).apply()
        }
    }

    fun markOpened(bookId: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(OPENED + bookId, at).apply()
    }

    fun clear(bookId: String) {
        prefs.edit()
            .remove(FAVORITE + bookId)
            .remove(STATUS + bookId)
            .remove(OPENED + bookId)
            .apply()
    }

    private companion object {
        const val FAVORITE = "fav_"
        const val STATUS = "status_"
        const val OPENED = "opened_"
    }
}
