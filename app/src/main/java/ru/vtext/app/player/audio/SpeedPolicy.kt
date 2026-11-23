package ru.vtext.app.player.audio

import kotlin.math.roundToInt

/**
 * Правила скорости воспроизведения аудиокниг.
 *
 * Диапазон 0,50–2,00. Шаг 0,10, но в зоне 0,85–1,15 — 0,01: именно там подбирается
 * комфортный темп, а за пределами ±15% разница в 0,01 неслышна и мешает пальцу.
 *
 * Между 0,80 и 0,85 промежуточных значений нет — это осознанный переход между сетками.
 */
object SpeedPolicy {

    const val MIN = 0.50f
    const val MAX = 2.00f

    const val FINE_FROM = 0.85f
    const val FINE_TO = 1.15f

    const val FINE_STEP = 0.01f
    const val STEP = 0.10f

    /** Значение попадает в зону точной настройки. */
    fun isFine(value: Float): Boolean = value >= FINE_FROM && value <= FINE_TO

    /** Шаг для текущего значения: от него считают кнопки «−» и «+». */
    fun stepFor(value: Float): Float = if (isFine(value)) FINE_STEP else STEP

    /** Округление к сетке и ограничение диапазоном. */
    fun snap(value: Float): Float {
        val clamped = value.coerceIn(MIN, MAX)
        val step = if (clamped < FINE_FROM || clamped > FINE_TO) STEP else FINE_STEP
        val snapped = (clamped / step).roundToInt() * step
        return roundToHundredths(snapped.coerceIn(MIN, MAX))
    }

    /** Следующее значение в указанном направлении (+1 / −1). */
    fun next(value: Float, direction: Int): Float =
        snap(value + direction * stepFor(value))

    /** «1.15×», «0.7×», «2×» — лишние нули не выводим. */
    fun format(value: Float): String {
        val hundredths = (snap(value) * 100).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return when {
            fraction == 0 -> "$whole×"
            fraction % 10 == 0 -> "$whole.${fraction / 10}×"
            else -> "$whole.${fraction / 10}${fraction % 10}×"
        }
    }

    /** Короткая подпись кнопки скорости: «x0.9», «x1», «x1.25». */
    fun chipLabel(value: Float): String {
        val hundredths = (snap(value) * 100).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return when {
            fraction == 0 -> "x$whole.0"
            fraction % 10 == 0 -> "x$whole.${fraction / 10}"
            else -> "x$whole.${fraction / 10}${fraction % 10}"
        }
    }

    private fun roundToHundredths(value: Float): Float =
        (value * 100).roundToInt() / 100f
}
