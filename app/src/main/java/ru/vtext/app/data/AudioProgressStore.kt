package ru.vtext.app.data

import android.content.Context

/** Позиция прослушивания аудиокниги: глава и время внутри неё. */
data class AudioPosition(
    val chapterIndex: Int,
    val positionMs: Long,
)

/**
 * Прогресс аудиокниг. Формат ключа повторяет текстовые книги: одна запись на книгу.
 * Таймер сна, как и в TTS-плеере, между запусками не сохраняется.
 */
class AudioProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("audio_progress", Context.MODE_PRIVATE)

    fun get(bookId: String): AudioPosition? {
        val chapter = prefs.getInt(key(bookId, "ch"), -1)
        val position = prefs.getLong(key(bookId, "pos"), -1L)
        if (chapter < 0 || position < 0) return null
        return AudioPosition(chapter, position)
    }

    fun save(bookId: String, chapterIndex: Int, positionMs: Long) {
        prefs.edit()
            .putInt(key(bookId, "ch"), chapterIndex)
            .putLong(key(bookId, "pos"), positionMs.coerceAtLeast(0))
            .apply()
    }

    fun clear(bookId: String) {
        prefs.edit()
            .remove(key(bookId, "ch"))
            .remove(key(bookId, "pos"))
            .apply()
    }

    private fun key(bookId: String, suffix: String) = "${suffix}_$bookId"
}
