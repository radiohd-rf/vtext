package ru.vtext.app.domain.cleanup

import kotlin.math.ceil

/**
 * Настройки очистки текста, извлечённого из документа.
 * Эвристики намеренно консервативные: лучше оставить лишнюю строку,
 * чем удалить часть книги.
 */
data class CleanupOptions(
    val removePageNumbers: Boolean = true,
    val removeRunningHeaders: Boolean = true,
    val joinHyphenation: Boolean = true,
    /** Минимум страниц, чтобы искать повторяющиеся колонтитулы. */
    val minPagesForRunningHeaders: Int = 3,
    /** Сколько первых и последних непустых строк страницы считать краем. */
    val edgeLineWindow: Int = 2,
    /** Доля страниц, на которых строка должна встретиться, чтобы считаться колонтитулом. */
    val repeatRatio: Double = 0.5,
    /** Сколько страниц подряд с одинаковой краевой строкой считаем колонтитулом. */
    val minLocalRun: Int = 3,
    /** Не трогаем колонтитулы на коротких страницах (титул, вклейки). */
    val minBodyLinesToClean: Int = 5,
)

data class CleanupResult(
    val pages: List<String>,
    val removedPageNumbers: Int = 0,
    val removedRunningLines: Int = 0,
    val joinedHyphenations: Int = 0,
) {
    fun text(): String = pages.joinToString("\n\n").trim()
}

/**
 * Постобработка текста после извлечения из PDF/TXT:
 * номера страниц, повторяющиеся колонтитулы и переносы слов.
 *
 * Для PDF страницы передаются отдельно, чтобы частотный анализ работал
 * по всему документу. Для TXT страницы можно разделить символом form feed.
 */
object ImportTextCleaner {

    fun cleanText(text: String, options: CleanupOptions = CleanupOptions()): CleanupResult {
        val pages = if (PAGE_BREAK in text) text.split(PAGE_BREAK) else listOf(text)
        return cleanPages(pages, options)
    }

    fun cleanPages(pages: List<String>, options: CleanupOptions = CleanupOptions()): CleanupResult {
        if (pages.isEmpty()) return CleanupResult(emptyList())

        val linesPerPage = pages.map { it.lines() }
        val nonEmptyIndices = linesPerPage.map { lines ->
            lines.indices.filter { lines[it].isNotBlank() }
        }
        val topIndices = nonEmptyIndices.map { it.take(options.edgeLineWindow).toSet() }
        val bottomIndices = nonEmptyIndices.map { indices ->
            if (indices.isEmpty()) emptySet() else indices.takeLast(options.edgeLineWindow).toSet()
        }

        val topKeys = linesPerPage.mapIndexed { page, lines ->
            topIndices[page].associateWith { normalize(lines[it]) }
        }
        val bottomKeys = linesPerPage.mapIndexed { page, lines ->
            bottomIndices[page].associateWith { normalize(lines[it]) }
        }
        val topKeySets = topKeys.map { it.values.toSet() }
        val bottomKeySets = bottomKeys.map { it.values.toSet() }

        // Сколько страниц содержат строку в верхней или нижней зоне.
        val pageCounts = mutableMapOf<String, Int>()
        val firstPageForKey = mutableMapOf<String, Int>()
        for (page in pages.indices) {
            for (key in topKeySets[page] + bottomKeySets[page]) {
                pageCounts[key] = (pageCounts[key] ?: 0) + 1
                firstPageForKey.putIfAbsent(key, page)
            }
        }
        val globalThreshold = maxOf(
            options.minPagesForRunningHeaders,
            ceil(pages.size * options.repeatRatio).toInt(),
        )

        // Номера страниц без подписи удаляем только если они встречаются
        // на большинстве страниц: иначе можно принять за номер страницы
        // номер главы («1», «2», «III») на первой странице главы.
        val topNumberLines = linesPerPage.mapIndexed { page, lines ->
            topIndices[page].filter { index ->
                STANDALONE_PAGE_NUMBER.matches(lines[index].trim())
            }.toSet()
        }
        val bottomNumberLines = linesPerPage.mapIndexed { page, lines ->
            bottomIndices[page].filter { index ->
                STANDALONE_PAGE_NUMBER.matches(lines[index].trim())
            }.toSet()
        }
        val numberThreshold = maxOf(3, ceil(pages.size * options.repeatRatio).toInt())
        val removeTopNumbers = pages.size >= 3 &&
            topNumberLines.count { it.isNotEmpty() } >= numberThreshold
        val removeBottomNumbers = pages.size >= 3 &&
            bottomNumberLines.count { it.isNotEmpty() } >= numberThreshold

        var removedPageNumbers = 0
        var removedRunningLines = 0
        var joinedHyphenations = 0

        val cleanedPages = pages.indices.map { page ->
            val lines = linesPerPage[page]
            val toRemove = mutableSetOf<Int>()
            val edgeIndices = topIndices[page] + bottomIndices[page]

            if (options.removePageNumbers) {
                for (index in edgeIndices) {
                    val isLabeled = LABELED_PAGE_NUMBER.matches(lines[index].trim())
                    val isTopNumber = removeTopNumbers && index in topNumberLines[page]
                    val isBottomNumber = removeBottomNumbers && index in bottomNumberLines[page]
                    if (isLabeled || isTopNumber || isBottomNumber) {
                        toRemove += index
                        removedPageNumbers += 1
                    }
                }
            }

            if (
                options.removeRunningHeaders &&
                pages.size >= options.minPagesForRunningHeaders &&
                nonEmptyIndices[page].size >= options.minBodyLinesToClean
            ) {
                for ((index, key) in topKeys[page]) {
                    if (index in toRemove) continue
                    if (isRunningLine(
                            key = key,
                            page = page,
                            pageCounts = pageCounts,
                            globalThreshold = globalThreshold,
                            keySets = topKeySets,
                            minLocalRun = options.minLocalRun,
                            firstPage = firstPageForKey[key] ?: page,
                        )
                    ) {
                        toRemove += index
                        removedRunningLines += 1
                    }
                }
                for ((index, key) in bottomKeys[page]) {
                    if (index in toRemove) continue
                    if (isRunningLine(
                            key = key,
                            page = page,
                            pageCounts = pageCounts,
                            globalThreshold = globalThreshold,
                            keySets = bottomKeySets,
                            minLocalRun = options.minLocalRun,
                            firstPage = firstPageForKey[key] ?: page,
                        )
                    ) {
                        toRemove += index
                        removedRunningLines += 1
                    }
                }
            }

            val kept = lines.filterIndexed { index, _ -> index !in toRemove }.joinToString("\n")
            val tidy = tidy(kept)
            if (options.joinHyphenation) {
                val joined = joinHyphenatedWords(tidy)
                joinedHyphenations += joined.second
                joined.first
            } else {
                tidy
            }
        }

        return CleanupResult(
            pages = cleanedPages,
            removedPageNumbers = removedPageNumbers,
            removedRunningLines = removedRunningLines,
            joinedHyphenations = joinedHyphenations,
        )
    }

    private fun isRunningLine(
        key: String,
        page: Int,
        pageCounts: Map<String, Int>,
        globalThreshold: Int,
        keySets: List<Set<String>>,
        minLocalRun: Int,
        firstPage: Int,
    ): Boolean {
        // Первое вхождение оставляем: это может быть настоящий заголовок главы.
        if (page == firstPage) return false
        if ((pageCounts[key] ?: 0) >= globalThreshold) return true
        // Локальные колонтитулы: одна и та же строка на нескольких страницах подряд.
        // Первые страницы серии не трогаем, чтобы не удалить настоящий заголовок главы.
        if (page < minLocalRun - 1) return false
        for (offset in 1 until minLocalRun) {
            if (key !in keySets[page - offset]) return false
        }
        return true
    }

    private fun normalize(line: String): String = line.trim()
        .replace(WHITESPACE, " ")
        .lowercase()

    private fun tidy(text: String): String = text
        .replace(TRAILING_SPACES, "")
        .replace(MANY_BREAKS, "\n\n")
        .trim()

    private fun joinHyphenatedWords(text: String): Pair<String, Int> {
        var count = 0
        val result = HYPHEN_BREAK.replace(text) { match ->
            count += 1
            match.groupValues[1] + match.groupValues[2]
        }
        return result to count
    }

    private const val PAGE_BREAK = '\u000C'

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_SPACES = Regex("[ \\t]+$", RegexOption.MULTILINE)
    private val MANY_BREAKS = Regex("\\n{3,}")

    private val LABELED_PAGE_NUMBER = Regex(
        """^\s*(?:стр\.?|страница|page|p\.)\s*\d{1,4}(?:\s*(?:из|of|/)\s*\d{1,4})?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val STANDALONE_PAGE_NUMBER = Regex(
        """^\s*[-–—]?\s*(?:\d{1,4}|[ivxlcdm]{1,7})\s*[-–—]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val HYPHEN_BREAK = Regex("([а-яёa-z])-[ \\t]*\\n[ \\t]*([а-яёa-z])")
}
