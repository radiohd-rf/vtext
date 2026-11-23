package ru.vtext.app.data

import android.content.Context

/** Настройки читалки: тема, шрифт, размер, интервал, поля, яркость. */
class ReaderSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var themeId: String
        get() = prefs.getString(KEY_THEME, "paper") ?: "paper"
        set(value) { prefs.edit().putString(KEY_THEME, value).apply() }

    var fontId: String
        get() = prefs.getString(KEY_FONT, "serif") ?: "serif"
        set(value) { prefs.edit().putString(KEY_FONT, value).apply() }

    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        set(value) { prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE)).apply() }

    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        set(value) {
            prefs.edit()
                .putFloat(KEY_LINE_HEIGHT, value.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT))
                .apply()
        }

    var marginDp: Int
        get() = prefs.getInt(KEY_MARGIN, DEFAULT_MARGIN).coerceIn(MIN_MARGIN, MAX_MARGIN)
        set(value) { prefs.edit().putInt(KEY_MARGIN, value.coerceIn(MIN_MARGIN, MAX_MARGIN)).apply() }

    var brightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, -1f).coerceIn(-1f, 1f)
        set(value) { prefs.edit().putFloat(KEY_BRIGHTNESS, value.coerceIn(-1f, 1f)).apply() }

    companion object {
        const val MIN_SIZE = 14
        const val MAX_SIZE = 30
        const val DEFAULT_SIZE = 18
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.2f
        const val DEFAULT_LINE_HEIGHT = 1.55f
        const val MIN_MARGIN = 8
        const val MAX_MARGIN = 40
        const val DEFAULT_MARGIN = 20

        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_MARGIN = "margin"
        private const val KEY_BRIGHTNESS = "brightness"
    }
}
