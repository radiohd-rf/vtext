package ru.vtext.app.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.vtext.app.data.ReaderSettingsStore

enum class ReaderTheme(val id: String) {
    PAPER("paper"),
    SEPIA("sepia"),
    NIGHT("night"),
}

enum class ReaderFont(val id: String) {
    SERIF("serif"),
    SANS("sans"),
}

data class ReaderStyle(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizeSp: Int = ReaderSettingsStore.DEFAULT_SIZE,
    val lineHeight: Float = ReaderSettingsStore.DEFAULT_LINE_HEIGHT,
    val marginDp: Int = ReaderSettingsStore.DEFAULT_MARGIN,
    val brightness: Float = -1f,
    /** true — текст прокручивается, false — листается страницами. */
    val scrollMode: Boolean = true,
)

data class ReaderColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val line: Color,
)

val ReaderTheme.colors: ReaderColors
    get() = when (this) {
        ReaderTheme.PAPER -> ReaderColors(
            background = Color(0xFFFAF6EE),
            surface = Color(0xFFFFFDF8),
            text = Color(0xFF231F1A),
            muted = Color(0xFF6B6154),
            accent = Color(0xFF1F5A54),
            line = Color(0xFFE8DDCB),
        )
        ReaderTheme.SEPIA -> ReaderColors(
            background = Color(0xFFF2E6CF),
            surface = Color(0xFFF8EEDB),
            text = Color(0xFF3B2E1E),
            muted = Color(0xFF7A6A54),
            accent = Color(0xFF8A5F24),
            line = Color(0xFFDDCDAF),
        )
        ReaderTheme.NIGHT -> ReaderColors(
            background = Color(0xFF14120E),
            surface = Color(0xFF1C1914),
            text = Color(0xFFC9C0B0),
            muted = Color(0xFF8C8272),
            accent = Color(0xFF8FCFC4),
            line = Color(0xFF332D23),
        )
    }

fun ReaderTheme.toScheme() = when (this) {
    ReaderTheme.NIGHT -> darkColorScheme(
        primary = colors.accent,
        onPrimary = Color(0xFF06201D),
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = Color(0xFF2A251C),
        onSurfaceVariant = colors.muted,
        secondary = colors.accent,
        onSecondary = Color(0xFF06201D),
        secondaryContainer = Color(0xFF2B261D),
        onSecondaryContainer = colors.text,
        outline = Color(0xFF3F382B),
        outlineVariant = colors.line,
    )
    else -> lightColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.line,
        onSurfaceVariant = colors.muted,
        secondary = colors.accent,
        onSecondary = Color.White,
        secondaryContainer = if (this == ReaderTheme.SEPIA) {
            Color(0xFFE7D6B4)
        } else {
            Color(0xFFE6E0D2)
        },
        onSecondaryContainer = colors.text,
        outline = colors.muted,
        outlineVariant = colors.line,
    )
}

fun ReaderSettingsStore.toStyle(): ReaderStyle = ReaderStyle(
    theme = ReaderTheme.entries.firstOrNull { it.id == themeId } ?: ReaderTheme.PAPER,
    font = ReaderFont.entries.firstOrNull { it.id == fontId } ?: ReaderFont.SERIF,
    fontSizeSp = fontSizeSp,
    lineHeight = lineHeight,
    marginDp = marginDp,
    brightness = brightness,
    scrollMode = scrollMode,
)

fun ReaderSettingsStore.save(style: ReaderStyle) {
    themeId = style.theme.id
    fontId = style.font.id
    fontSizeSp = style.fontSizeSp
    lineHeight = style.lineHeight
    marginDp = style.marginDp
    brightness = style.brightness
    scrollMode = style.scrollMode
}

@Composable
fun ReaderThemeStyle(style: ReaderStyle, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = style.theme.toScheme(), content = content)
}
