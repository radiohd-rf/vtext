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

    /** Тон системного TTS (0.5 — ниже, 2.0 — выше). Для нейродвижков не применяется. */
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, DEFAULT_PITCH).coerceIn(MIN_PITCH, MAX_PITCH)
        set(value) {
            prefs.edit().putFloat(KEY_PITCH, value.coerceIn(MIN_PITCH, MAX_PITCH)).apply()
        }

    /** Громкость синтезированной речи: 0…1. */
    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(MIN_VOLUME, MAX_VOLUME)
        set(value) {
            prefs.edit().putFloat(KEY_VOLUME, value.coerceIn(MIN_VOLUME, MAX_VOLUME)).apply()
        }

    /** Пауза между фрагментами книги, мс. */
    var fragmentPauseMs: Int
        get() = prefs.getInt(KEY_FRAGMENT_PAUSE, DEFAULT_FRAGMENT_PAUSE_MS)
            .coerceIn(MIN_FRAGMENT_PAUSE_MS, MAX_FRAGMENT_PAUSE_MS)
        set(value) {
            prefs.edit()
                .putInt(KEY_FRAGMENT_PAUSE, value.coerceIn(MIN_FRAGMENT_PAUSE_MS, MAX_FRAGMENT_PAUSE_MS))
                .apply()
        }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val DEFAULT_SPEED = 1f

        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2f
        const val DEFAULT_PITCH = 1f

        const val MIN_VOLUME = 0f
        const val MAX_VOLUME = 1f
        const val DEFAULT_VOLUME = 1f

        const val MIN_FRAGMENT_PAUSE_MS = 0
        const val MAX_FRAGMENT_PAUSE_MS = 2_000
        const val DEFAULT_FRAGMENT_PAUSE_MS = 0


        private const val KEY_SPEED = "speed"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOLUME = "volume"
        private const val KEY_FRAGMENT_PAUSE = "fragment_pause_ms"
    }
}
