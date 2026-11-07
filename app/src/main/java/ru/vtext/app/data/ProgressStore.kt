package ru.vtext.app.data

import android.content.Context

/** Позиция чтения: глава и фрагмент внутри главы. */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun save(bookId: String, chapter: Int, segment: Int) {
        prefs.edit().putString(bookId, "$chapter:$segment").apply()
    }

    fun load(bookId: String): Pair<Int, Int> {
        val value = prefs.getString(bookId, null) ?: return 0 to 0
        val chapter = value.substringBefore(':').toIntOrNull() ?: 0
        val segment = value.substringAfter(':', "0").toIntOrNull() ?: 0
        return chapter to segment
    }

    fun clear(bookId: String) {
        prefs.edit().remove(bookId).apply()
    }
}
