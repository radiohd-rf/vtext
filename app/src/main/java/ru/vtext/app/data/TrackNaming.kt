package ru.vtext.app.data

/**
 * Правила имён дорожек аудиокниги. Отдельный объект — чтобы правила можно было
 * проверить юнит-тестами без Android.
 */
object TrackNaming {

    private val TRACK_PREFIX = Regex(
        """^\s*(?:(?:chapter|part|book|глава|часть|книга)\s*)?\d{1,3}\s*[-–—._)]?\s*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * «03 - Понтий Пилат.mp3» → «Понтий Пилат».
     * Если после чистки ничего не осталось — возвращаем исходное имя без расширения.
     */
    fun titleFromFileName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val withoutPrefix = base.replace(TRACK_PREFIX, "").replace('_', ' ').trim()
        val result = withoutPrefix.ifBlank { base }.trim()
        return result.replace(Regex("\\s+"), " ")
    }

    /** Естественный порядок: «2.mp3» раньше «10.mp3», а не наоборот. */
    fun naturalOrder(names: List<String>): List<String> = names.sortedWith { left, right ->
        compareNatural(left, right)
    }

    private fun compareNatural(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val l = left[i]
            val r = right[j]
            if (l.isDigit() && r.isDigit()) {
                val lEnd = digitEnd(left, i)
                val rEnd = digitEnd(right, j)
                val lNumber = left.substring(i, lEnd).trimStart('0')
                val rNumber = right.substring(j, rEnd).trimStart('0')
                val byLength = lNumber.length.compareTo(rNumber.length)
                if (byLength != 0) return byLength
                val byValue = lNumber.compareTo(rNumber)
                if (byValue != 0) return byValue
                i = lEnd
                j = rEnd
                continue
            }
            if (l.lowercaseChar() != r.lowercaseChar()) {
                return l.lowercaseChar().compareTo(r.lowercaseChar())
            }
            i++
            j++
        }
        return (left.length - i).compareTo(right.length - j)
    }

    private fun digitEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isDigit()) index++
        return index
    }

    /** Имя файла для копирования в хранилище: «01 - Название.mp3». */
    fun storedFileName(index: Int, sourceName: String): String {
        val extension = sourceName.substringAfterLast('.', "").lowercase()
        val title = titleFromFileName(sourceName).ifBlank { "track" }
        val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        val number = index.toString().padStart(2, '0')
        return if (extension.isBlank()) "$number - $safe" else "$number - $safe.$extension"
    }
}
