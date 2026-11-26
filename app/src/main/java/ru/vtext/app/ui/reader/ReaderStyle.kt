package ru.vtext.app.ui.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ru.vtext.app.R
import ru.vtext.app.data.ReaderSettingsStore

/** Тема фона читалки: пять пресетов, последний следует системной. */
enum class ReaderTheme(val id: String) {
    WHITE("white"),
    SEPIA("sepia"),
    GRAY("gray"),
    BLACK("black"),
    SYSTEM("system"),
}

/** Межстрочный интервал: три режима (как в макете — три иконки). */
enum class ReaderLineSpacing(val id: String, val multiplier: Float) {
    COMPACT("compact", 1.3f),
    NORMAL("normal", 1.55f),
    WIDE("wide", 1.85f),
    ;

    companion object {
        fun fromId(id: String?): ReaderLineSpacing =
            entries.firstOrNull { it.id == id } ?: NORMAL

        fun nearest(value: Float): ReaderLineSpacing =
            entries.minByOrNull { kotlin.math.abs(it.multiplier - value) } ?: NORMAL
    }
}

/** Поля страницы: три режима. */
enum class ReaderMargins(val id: String, val dp: Int) {
    NARROW("narrow", 12),
    MEDIUM("medium", 20),
    WIDE("wide", 32),
    ;

    companion object {
        fun fromId(id: String?): ReaderMargins =
            entries.firstOrNull { it.id == id } ?: MEDIUM

        fun nearest(dp: Int): ReaderMargins =
            entries.minByOrNull { kotlin.math.abs(it.dp - dp) } ?: MEDIUM
    }
}

/** Шесть шрифтов чтения: три с засечками и три без. */
enum class ReaderFont(
    val id: String,
    val title: String,
    val resource: Int,
    val serif: Boolean,
) {
    PT_SERIF("pt_serif", "PT Serif", R.font.pt_serif, true),
    LORA("lora", "Lora", R.font.lora, true),
    GARAMOND("garamond", "EB Garamond", R.font.eb_garamond, true),
    PT_SANS("pt_sans", "PT Sans", R.font.pt_sans, false),
    OPEN_SANS("open_sans", "Open Sans", R.font.open_sans, false),
    INTER("inter", "Inter", R.font.inter, false),
    ;

    companion object {
        fun fromId(id: String?): ReaderFont = when (id) {
            "serif" -> PT_SERIF
            "sans" -> PT_SANS
            else -> entries.firstOrNull { it.id == id } ?: PT_SERIF
        }
    }
}

private val fontFamilies: Map<ReaderFont, FontFamily> by lazy {
    ReaderFont.entries.associateWith { FontFamily(Font(it.resource)) }
}

val ReaderFont.family: FontFamily get() = fontFamilies.getValue(this)

data class ReaderStyle(
    val theme: ReaderTheme = ReaderTheme.WHITE,
    val font: ReaderFont = ReaderFont.PT_SERIF,
    val fontSizeSp: Int = ReaderSettingsStore.DEFAULT_SIZE,
    val lineSpacing: ReaderLineSpacing = ReaderLineSpacing.NORMAL,
    val margins: ReaderMargins = ReaderMargins.MEDIUM,
    /** true — яркость как в системе, false — своя (style.brightness). */
    val followSystemBrightness: Boolean = true,
    val brightness: Float = 0.6f,
    /** true — текст прокручивается, false — листается страницами. */
    val scrollMode: Boolean = false,
)

data class ReaderColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val line: Color,
)

private val WHITE_COLORS = ReaderColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF1B1B1F),
    muted = Color(0xFF6B6B70),
    accent = Color(0xFF1F5A54),
    line = Color(0xFFECECEC),
)

private val SEPIA_COLORS = ReaderColors(
    background = Color(0xFFF2E6CF),
    surface = Color(0xFFF8EEDB),
    text = Color(0xFF3B2E1E),
    muted = Color(0xFF7A6A54),
    accent = Color(0xFF8A5F24),
    line = Color(0xFFDDCDAF),
)

private val GRAY_COLORS = ReaderColors(
    background = Color(0xFF3C3F44),
    surface = Color(0xFF45484E),
    text = Color(0xFFE8E6E1),
    muted = Color(0xFFA9A6A0),
    accent = Color(0xFF8FCFC4),
    line = Color(0xFF52565C),
)

private val BLACK_COLORS = ReaderColors(
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    text = Color(0xFFE6E6E6),
    muted = Color(0xFF9A9A9A),
    accent = Color(0xFF8FCFC4),
    line = Color(0xFF262626),
)

fun ReaderTheme.colors(systemDark: Boolean): ReaderColors = when (this) {
    ReaderTheme.WHITE -> WHITE_COLORS
    ReaderTheme.SEPIA -> SEPIA_COLORS
    ReaderTheme.GRAY -> GRAY_COLORS
    ReaderTheme.BLACK -> BLACK_COLORS
    ReaderTheme.SYSTEM -> if (systemDark) BLACK_COLORS else WHITE_COLORS
}

fun ReaderTheme.isDark(systemDark: Boolean): Boolean = when (this) {
    ReaderTheme.GRAY, ReaderTheme.BLACK -> true
    ReaderTheme.SYSTEM -> systemDark
    ReaderTheme.WHITE, ReaderTheme.SEPIA -> false
}

@Composable
fun ReaderStyle.colors(): ReaderColors = theme.colors(isSystemInDarkTheme())

@Composable
fun ReaderStyle.toScheme(): ColorScheme {
    val systemDark = isSystemInDarkTheme()
    val palette = theme.colors(systemDark)
    return if (theme.isDark(systemDark)) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color(0xFF06201D),
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.line,
            onSurfaceVariant = palette.muted,
            secondary = palette.accent,
            onSecondary = Color(0xFF06201D),
            secondaryContainer = palette.line,
            onSecondaryContainer = palette.text,
            outline = palette.muted,
            outlineVariant = palette.line,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.line,
            onSurfaceVariant = palette.muted,
            secondary = palette.accent,
            onSecondary = Color.White,
            secondaryContainer = palette.line,
            onSecondaryContainer = palette.text,
            outline = palette.muted,
            outlineVariant = palette.line,
        )
    }
}

fun ReaderSettingsStore.toStyle(): ReaderStyle = ReaderStyle(
    theme = ReaderTheme.entries.firstOrNull { it.id == themeId } ?: ReaderTheme.WHITE,
    font = ReaderFont.fromId(fontId),
    fontSizeSp = fontSizeSp,
    lineSpacing = ReaderLineSpacing.fromId(lineSpacingId),
    margins = ReaderMargins.fromId(marginsId),
    followSystemBrightness = followSystemBrightness,
    brightness = brightness,
    scrollMode = scrollMode,
)

fun ReaderSettingsStore.save(style: ReaderStyle) {
    themeId = style.theme.id
    fontId = style.font.id
    fontSizeSp = style.fontSizeSp
    lineSpacingId = style.lineSpacing.id
    marginsId = style.margins.id
    followSystemBrightness = style.followSystemBrightness
    brightness = style.brightness
    scrollMode = style.scrollMode
}

@Composable
fun ReaderThemeStyle(style: ReaderStyle, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = style.toScheme(), content = content)
}
