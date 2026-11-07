package ru.vtext.app.data

import android.content.Context

/** Скорость и голос, сохранённые для конкретной книги. */
class BookSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("book_settings", Context.MODE_PRIVATE)

    fun speed(bookId: String): Float? =
        prefs.getFloat(KEY_SPEED + bookId, -1f).takeIf { it > 0f }

    fun setSpeed(bookId: String, value: Float) {
        prefs.edit().putFloat(KEY_SPEED + bookId, value).apply()
    }

    fun voice(bookId: String): String? = prefs.getString(KEY_VOICE + bookId, null)

    fun setVoice(bookId: String, value: String) {
        prefs.edit().putString(KEY_VOICE + bookId, value).apply()
    }

    fun forget(bookId: String) {
        prefs.edit()
            .remove(KEY_SPEED + bookId)
            .remove(KEY_VOICE + bookId)
            .apply()
    }

    private companion object {
        const val KEY_SPEED = "speed_"
        const val KEY_VOICE = "voice_"
    }
}
