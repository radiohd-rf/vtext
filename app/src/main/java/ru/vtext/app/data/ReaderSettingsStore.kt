package ru.vtext.app.data

import android.content.Context

/** Настройки читалки: тема, шрифт, размер, интервал, поля, яркость // прокрутка. */
class ReaderSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var themeId: String
        get() = migrateTheme(prefs.getString(KEY_THEME, THEME_WHITE))
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
        }

    var fontId: String
        get() = prefs.getString(KEY_FONT, FONT_DEFAULT) ?: FONT_DEFAULT
        set(value) {
            prefs.edit().putString(KEY_FONT, value).apply()
        }

    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE)).apply()
        }

    /** Режим межстрочного интервала: compact / normal / wide. */
    var lineSpacingId: String
        get() = prefs.getString(KEY_LINE_SPACING, null)
            ?: when {
                prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT) < 1.45f -> "compact"
                prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT) > 1.7f -> "wide"
                else -> "normal"
            }
        set(value) {
            prefs.edit().putString(KEY_LINE_SPACING, value).apply()
        }

    /** Режим полей: narrow / medium / wide. */
    var marginsId: String
        get() = prefs.getString(KEY_MARGINS, null)
            ?: when {
                prefs.getInt(KEY_MARGIN, DEFAULT_MARGIN) < 16 -> "narrow"
                prefs.getInt(KEY_MARGIN, DEFAULT_MARGIN) > 26 -> "wide"
                else -> "medium"
            }
        set(value) {
            prefs.edit().putString(KEY_MARGINS, value).apply()
        }

    /** true — яркость как в системе, false — своя, из [brightness]. */
    var followSystemBrightness: Boolean
        get() = if (prefs.contains(KEY_FOLLOW_SYSTEM_BRIGHTNESS)) {
            prefs.getBoolean(KEY_FOLLOW_SYSTEM_BRIGHTNESS, true)
        } else {
            // Старые настройки: -1 означало «как в системе».
            prefs.getFloat(KEY_BRIGHTNESS, -1f) < 0f
        }
        set(value) {
            prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM_BRIGHTNESS, value).apply()
        }

    var brightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .let { if (it < 0f) DEFAULT_BRIGHTNESS else it.coerceIn(MIN_BRIGHTNESS, 1f) }
        set(value) {
            prefs.edit().putFloat(KEY_BRIGHTNESS, value.coerceIn(MIN_BRIGHTNESS, 1f)).apply()
        }

    /** true — прокрутка текста, false — пролистывание страницами. */
    var scrollMode: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()
        }

    private fun migrateTheme(value: String?): String = when (value) {
        null, "" -> THEME_WHITE
        "paper" -> THEME_WHITE
        "night" -> THEME_BLACK
        else -> value
    }

    companion object {
        const val MIN_SIZE = 14
        const val MAX_SIZE = 30
        const val DEFAULT_SIZE = 18
        const val DEFAULT_LINE_HEIGHT = 1.55f
        const val DEFAULT_MARGIN = 20
        const val MIN_BRIGHTNESS = 0.05f
        const val DEFAULT_BRIGHTNESS = 0.6f

        private const val THEME_WHITE = "white"
        private const val THEME_BLACK = "black"
        private const val FONT_DEFAULT = "pt_serif"

        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_MARGIN = "margin"
        private const val KEY_MARGINS = "margins"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_FOLLOW_SYSTEM_BRIGHTNESS = "brightness_system"
        private const val KEY_SCROLL_MODE = "scroll_mode"
    }
}
