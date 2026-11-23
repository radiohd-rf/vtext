package ru.vtext.app.data

import android.content.Context

/**
 * Прогресс текстовой книги: глава, фрагмент и процент книги целиком.
 *
 * Хранится строкой `chapter:segment:percent`. Старые записи без процента
 * (`chapter:segment`) читаются как обычно, процент в них неизвестен (`-1`).
 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun save(bookId: String, chapter: Int, segment: Int, percent: Int = -1) {
        prefs.edit().putString(bookId, "$chapter:$segment:$percent").apply()
    }

    fun load(bookId: String): Pair<Int, Int> {
        val parts = (prefs.getString(bookId, null) ?: return 0 to 0).split(':')
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val segment = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return chapter to segment
    }

    /** Процент книги (0…100) или null, если он ещё не записан (старый формат). */
    fun percent(bookId: String): Int? {
        val parts = (prefs.getString(bookId, null) ?: return null).split(':')
        return parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    fun clear(bookId: String) {
        prefs.edit().remove(bookId).apply()
    }
}
