package ru.vtext.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Тёплая бумага» — визуальный язык приложения.
 *
 * Кремовая основа, чернильный текст, глубокий бирюзовый акцент и латунь
 * для выделений. Заголовки — антиква (serif), основной текст — гротеск:
 * так интерфейс читается как книга, а не как утилита.
 */
private val Paper = Color(0xFFFAF6EE)
private val PaperCard = Color(0xFFFFFDF8)
private val Ink = Color(0xFF231F1A)
private val InkSoft = Color(0xFF6B6154)
private val Teal = Color(0xFF1F5A54)
private val TealSoft = Color(0xFFDCE8E4)
private val TealDeep = Color(0xFF12332F)
private val Sand = Color(0xFFEFE6D6)
private val Line = Color(0xFFDFD3BE)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = TealSoft,
    onPrimaryContainer = TealDeep,
    // Латунь для текста берём глубокую: #B0803A на бумаге даёт лишь 3.2:1 (мало для AA).
    secondary = Color(0xFF8A5F24),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E4C8),
    onSecondaryContainer = Color(0xFF3D2A0B),
    tertiary = Color(0xFF7A4B3A),
    onTertiary = Color(0xFFFFFFFF),
    background = Paper,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = InkSoft,
    surfaceContainer = Color(0xFFFBF7EF),
    surfaceContainerHigh = Color(0xFFF3ECDF),
    surfaceContainerHighest = Color(0xFFEDE4D4),
    surfaceDim = Color(0xFFE7DDCB),
    outline = Color(0xFFB9AB94),
    outlineVariant = Color(0xFFE8DDCB),
    error = Color(0xFF9C3A2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7DAD4),
    onErrorContainer = Color(0xFF3D0D07),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCFC4),
    onPrimary = Color(0xFF06201D),
    primaryContainer = Color(0xFF17403A),
    onPrimaryContainer = Color(0xFFD6EFE9),
    secondary = Color(0xFFDCB871),
    onSecondary = Color(0xFF2A1E08),
    secondaryContainer = Color(0xFF40320F),
    onSecondaryContainer = Color(0xFFF6E3BC),
    tertiary = Color(0xFFD6A791),
    onTertiary = Color(0xFF2E1309),
    background = Color(0xFF14120E),
    onBackground = Color(0xFFEDE6D9),
    surface = Color(0xFF1C1914),
    onSurface = Color(0xFFEDE6D9),
    surfaceVariant = Color(0xFF2A251C),
    onSurfaceVariant = Color(0xFFC6BAA6),
    surfaceContainer = Color(0xFF221E17),
    surfaceContainerHigh = Color(0xFF2B261D),
    surfaceContainerHighest = Color(0xFF352F24),
    surfaceDim = Color(0xFF14120E),
    outline = Color(0xFF6E6452),
    outlineVariant = Color(0xFF3A3327),
    error = Color(0xFFE39A8D),
    onError = Color(0xFF2A0906),
    errorContainer = Color(0xFF5C2118),
    onErrorContainer = Color(0xFFFFDAD3),
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val VtextTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp),
)

/** Скругления: от мягких плашек до «книжной» обложки. */
private val VtextShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Шаги отступов. Используем сетку 4 dp, чтобы экраны дышали одинаково.
 * 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp
}

/**
 * @param dynamicColor Material You. По умолчанию выключен: палитра «тёплая бумага»
 * часть бренда, но параметр оставлен, чтобы можно было включить тумблером в настройках.
 */
@Composable
fun VtextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = VtextTypography,
        shapes = VtextShapes,
        content = content,
    )
}
