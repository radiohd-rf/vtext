package ru.vtext.app.ui.theme

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
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

/** Цвет из обоев рабочего стола; null, если система его не отдала. */
@Composable
private fun rememberWallpaperColor(): Color? {
    val context = LocalContext.current
    val read = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            null
        } else {
            runCatching {
                val manager = context.getSystemService(WallpaperManager::class.java)
                val colors = manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                val color = colors?.primaryColor ?: colors?.secondaryColor
                color?.let { Color(it.toArgb()) }
            }.getOrNull()
        }
    }
    var seed by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                seed = read()
            }
        }
        // Обои сменились — пересобираем палитру.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("android.intent.action.WALLPAPER_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return seed
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = (((hue % 360f) + 360f) % 360f) / 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}

private fun Color.hueAndSaturation(): Pair<Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    if (max == min) return 0f to 0f
    val lightness = (max + min) / 2f
    val delta = max - min
    val saturation = if (lightness > 0.5f) {
        delta / (2f - max - min)
    } else {
        delta / (max + min)
    }
    val hue = when (max) {
        red -> (green - blue) / delta + if (green < blue) 6f else 0f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } / 6f
    return hue * 360f to saturation
}

/**
 * Палитра из цвета обоев. Системный Material You есть не на всех прошивках
 * (на HyperOS цвета статичные), поэтому тона строим сами: тот же оттенок,
 * приглушённая насыщенность и контрастные контейнеры.
 */
private fun wallpaperScheme(seed: Color, dark: Boolean): ColorScheme {
    val (hue, saturation) = seed.hueAndSaturation()
    val chroma = saturation.coerceIn(0.3f, 0.75f)
    return if (dark) {
        darkColorScheme(
            primary = hsl(hue, chroma * 0.7f, 0.76f),
            onPrimary = hsl(hue, chroma * 0.6f, 0.12f),
            primaryContainer = hsl(hue, chroma * 0.5f, 0.30f),
            onPrimaryContainer = hsl(hue, chroma * 0.4f, 0.92f),
            secondary = hsl(hue + 28f, chroma * 0.5f, 0.74f),
            onSecondary = hsl(hue + 28f, chroma * 0.5f, 0.14f),
            secondaryContainer = hsl(hue + 28f, chroma * 0.4f, 0.30f),
            onSecondaryContainer = hsl(hue + 28f, chroma * 0.3f, 0.92f),
            tertiary = hsl(hue - 42f, chroma * 0.45f, 0.74f),
            onTertiary = hsl(hue - 42f, chroma * 0.5f, 0.14f),
            background = hsl(hue, 0.12f, 0.07f),
            onBackground = hsl(hue, 0.06f, 0.91f),
            surface = hsl(hue, 0.10f, 0.10f),
            onSurface = hsl(hue, 0.06f, 0.91f),
            surfaceVariant = hsl(hue, 0.10f, 0.20f),
            onSurfaceVariant = hsl(hue, 0.08f, 0.76f),
            surfaceContainer = hsl(hue, 0.10f, 0.12f),
            surfaceContainerHigh = hsl(hue, 0.10f, 0.16f),
            surfaceContainerHighest = hsl(hue, 0.10f, 0.20f),
            surfaceDim = hsl(hue, 0.10f, 0.07f),
            outline = hsl(hue, 0.08f, 0.46f),
            outlineVariant = hsl(hue, 0.10f, 0.24f),
            error = Color(0xFFE39A8D),
            onError = Color(0xFF2A0906),
            errorContainer = Color(0xFF5C2118),
            onErrorContainer = Color(0xFFFFDAD3),
        )
    } else {
        lightColorScheme(
            primary = hsl(hue, chroma * 0.95f, 0.36f),
            onPrimary = Color.White,
            primaryContainer = hsl(hue, chroma * 0.55f, 0.90f),
            onPrimaryContainer = hsl(hue, chroma * 0.7f, 0.18f),
            secondary = hsl(hue + 28f, chroma * 0.55f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(hue + 28f, chroma * 0.35f, 0.90f),
            onSecondaryContainer = hsl(hue + 28f, chroma * 0.5f, 0.20f),
            tertiary = hsl(hue - 42f, chroma * 0.5f, 0.40f),
            onTertiary = Color.White,
            background = hsl(hue, 0.20f, 0.975f),
            onBackground = hsl(hue, 0.12f, 0.12f),
            surface = hsl(hue, 0.18f, 0.99f),
            onSurface = hsl(hue, 0.12f, 0.12f),
            surfaceVariant = hsl(hue, 0.20f, 0.90f),
            onSurfaceVariant = hsl(hue, 0.10f, 0.38f),
            surfaceContainer = hsl(hue, 0.18f, 0.96f),
            surfaceContainerHigh = hsl(hue, 0.18f, 0.93f),
            surfaceContainerHighest = hsl(hue, 0.18f, 0.90f),
            surfaceDim = hsl(hue, 0.16f, 0.86f),
            outline = hsl(hue, 0.08f, 0.60f),
            outlineVariant = hsl(hue, 0.12f, 0.84f),
            error = Color(0xFF9C3A2E),
            onError = Color.White,
            errorContainer = Color(0xFFF7DAD4),
            onErrorContainer = Color(0xFF3D0D07),
        )
    }
}

/**
 * @param dynamicColor Material You. По умолчанию выключен: палитра «тёплая бумага»
 * часть бренда, но параметр оставлен, чтобы можно было включить тумблером в настройках.
 * Цвета берём из обоев сами — системная схема на части прошивок статичная.
 */
@Composable
fun VtextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val wallpaper = if (dynamicColor) rememberWallpaperColor() else null
    val colors = when {
        dynamicColor && wallpaper != null -> wallpaperScheme(wallpaper, darkTheme)
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
