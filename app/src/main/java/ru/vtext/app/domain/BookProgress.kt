package ru.vtext.app.domain

import ru.vtext.app.domain.model.Chapter
import kotlin.math.roundToInt

/**
 * Процент книги по позиции «глава + фрагмент»: отношение числа фрагментов
 * до текущего к общему числу фрагментов. Нужен библиотеке, чтобы показать
 * «сколько прочитано» без загрузки всех глав книги.
 */
fun bookPercent(chapters: List<Chapter>, chapterIndex: Int, segmentIndex: Int): Int {
    val total = chapters.sumOf { it.segments.size }
    if (total <= 0) return 0
    var done = 0
    val safeChapter = chapterIndex.coerceIn(0, chapters.size)
    for (index in 0 until safeChapter) {
        done += chapters[index].segments.size
    }
    done += segmentIndex.coerceAtLeast(0)
    return (done.coerceIn(0, total) * 100.0 / total).roundToInt().coerceIn(0, 100)
}
