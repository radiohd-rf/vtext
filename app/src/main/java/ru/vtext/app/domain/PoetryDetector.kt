package ru.vtext.app.domain

/**
 * Отличает стихи от прозы по длине строк. В книгах стихотворная строка
 * занимает 20–50 символов, тогда как выключенная по формату проза даёт
 * строки по 80–100 символов.
 */
object PoetryDetector {

    fun isPoetry(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size < MIN_LINES) return false
        val lengths = lines.map { it.length }.sorted()
        val median = lengths[lengths.size / 2]
        val p90 = lengths[(lengths.size * 9 / 10).coerceAtMost(lengths.lastIndex)]
        if (median > MEDIAN_MAX || p90 > P90_MAX) return false
        // В прозе перенос строки почти всегда продолжает предложение со строчной
        // буквы; в стихах строки начинаются с заглавной. Это отличает книгу
        // с узкой колонкой (короткие строки) от настоящих стихов.
        val lowerStarts = lines.count { it.firstOrNull()?.isLowerCase() == true }
        return lowerStarts.toFloat() / lines.size <= MAX_LOWERCASE_START_RATIO
    }

    private const val MIN_LINES = 30
    private const val MEDIAN_MAX = 55
    private const val P90_MAX = 80
    private const val MAX_LOWERCASE_START_RATIO = 0.2f
}
