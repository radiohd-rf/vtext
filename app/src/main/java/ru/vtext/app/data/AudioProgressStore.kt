package ru.vtext.app.data

import android.content.Context

/** Позиция в аудиокниге: глава, время внутри главы и процент книги целиком. */
data class AudioPosition(
    val chapterIndex: Int,
    val positionMs: Long,
    /** 0…100 или -1, если процент ещё не записан (старые версии). */
    val percent: Int = -1,
)

/**
 * Прогресс аудиокниги. Формат ключей прежний: глава и время в главе.
 * Процент книги хранится отдельным ключом, чтобы библиотека показывала
 * «сколько прослушано» без загрузки длительностей всех дорожек.
 */
class AudioProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("audio_progress", Context.MODE_PRIVATE)

    fun get(bookId: String): AudioPosition? {
        val chapter = prefs.getInt(key(bookId, "ch"), -1)
        val position = prefs.getLong(key(bookId, "pos"), -1L)
        if (chapter < 0 || position < 0) return null
        val percent = prefs.getInt(key(bookId, "pct"), -1).takeIf { it in 0..100 } ?: -1
        return AudioPosition(chapter, position, percent)
    }

    fun save(bookId: String, chapterIndex: Int, positionMs: Long, percent: Int = -1) {
        prefs.edit()
            .putInt(key(bookId, "ch"), chapterIndex)
            .putLong(key(bookId, "pos"), positionMs.coerceAtLeast(0))
            .putInt(key(bookId, "pct"), percent)
            .apply()
    }

    fun clear(bookId: String) {
        prefs.edit()
            .remove(key(bookId, "ch"))
            .remove(key(bookId, "pos"))
            .remove(key(bookId, "pct"))
            .apply()
    }

    private fun key(bookId: String, suffix: String) = "${suffix}_$bookId"
}
