package ru.vtext.app.data

import android.content.Context

/** Настройки воспроизведения, которые должны переживать перезапуск приложения. */
class PlaybackSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)

    /** Скорость речи, ограниченная поддерживаемым диапазоном. */
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) {
            prefs.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()
        }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val DEFAULT_SPEED = 1f
        private const val KEY_SPEED = "speed"
    }
}
