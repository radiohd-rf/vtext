package ru.vtext.app.domain.notes

import ru.vtext.app.domain.model.Book

/** Одно примечание: метка («1», «12», «*») и текст. */
data class NoteRef(
    val label: String,
    val text: String,
    val section: String,
)

/**
 * Индекс примечаний книги: разделы по главам, внутри — метки и тексты.
 * Строится по главе «Примечания»/«Комментарии»/«Notes»: номера в ней
 * идут подряд, сбрасываясь на каждом разделе, поэтому режем по ожидаемому
 * номеру, а не по любому числу в тексте.
 */
class BookNotes private constructor(
    private val sections: List<Section>,
    private val chapterSections: Map<Int, Section>,
    val sourceChapter: String,
) {
    data class Section(val key: String, val title: String, val notes: Map<String, NoteRef>)

    /**
     * Примечание для главы: подглавы («Московские архитекторы на войне»)
     * наследуют раздел своей «Главы N».
     */
    fun noteFor(chapterIndex: Int, label: String): NoteRef? =
        chapterSections[chapterIndex]?.notes?.get(label)

    companion object {
        private val NOTES_TITLE = Regex("(примеч|коммент|сноск|notes)", RegexOption.IGNORE_CASE)

        private val SECTION = Regex(
            "(Введение|Предисловие|Заключение|Эпилог|Приложение(?:\\s*\\d+)?|" +
                "Глава\\s+\\d+(?:\\.\\s*[^0-9]{0,60})?)",
        )

        private val WHITESPACE = Regex("\\s+")

        fun parse(book: Book): BookNotes? {
            val chapter = book.chapters.firstOrNull { NOTES_TITLE.containsMatchIn(it.title) }
                ?: return null
            val text = chapter.segments
                .joinToString(" ")
                .replace(WHITESPACE, " ")
                .trim()
            if (text.isBlank()) return null
            val headings = SECTION.findAll(text).filter { match ->
                val title = match.groupValues[1]
                // Настоящий раздел начинается с заглавной и сразу за ним идёт «1 »;
                // строчные колонтитулы («введение») и числа в цитатах отсеиваются.
                val uppercase = title.firstOrNull()?.isUpperCase() == true
                val after = text.substring(match.range.last + 1).trimStart()
                uppercase && after.startsWith("1 ")
            }.toList()
            if (headings.isEmpty()) return null
            val sections = headings.mapIndexed { index, match ->
                val start = match.range.last + 1
                val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                val title = match.groupValues[1].trim()
                Section(normalize(title), title, splitNotes(text.substring(start, end), title))
            }
            return BookNotes(sections, assignSections(book, sections), chapter.title)
        }

        /** Каждой главе — её раздел примечаний; подглавы наследуют предыдущий. */
        private fun assignSections(book: Book, sections: List<Section>): Map<Int, Section> {
            val result = mutableMapOf<Int, Section>()
            var current: Section? = null
            book.chapters.forEachIndexed { index, chapter ->
                val key = normalize(chapter.title)
                val match = sections.firstOrNull { it.key == key }
                    ?: sections.firstOrNull {
                        key.isNotBlank() && (it.key.startsWith(key) || key.startsWith(it.key))
                    }
                if (match != null) current = match
                current?.let { result[index] = it }
            }
            return result
        }

        private fun splitNotes(body: String, section: String): Map<String, NoteRef> {
            val cuts = mutableListOf<Triple<String, Int, Int>>()
            var position = 0
            var expected = 1
            while (position < body.length && cuts.size < MAX_NOTES) {
                var found: Triple<String, Int, Int>? = null
                for (number in expected..(expected + 2)) {
                    val match = Regex("(?<![\\d.,])$number\\s+").find(body, position) ?: continue
                    found = Triple(number.toString(), match.range.first, match.range.last + 1)
                    break
                }
                val value = found ?: break
                cuts += value
                position = value.third
                expected = value.first.toInt() + 1
            }
            if (cuts.isEmpty()) return emptyMap()
            val notes = mutableMapOf<String, NoteRef>()
            cuts.forEachIndexed { index, cut ->
                val stop = cuts.getOrNull(index + 1)?.second ?: body.length
                val text = body.substring(cut.third, stop)
                    .replace(WHITESPACE, " ")
                    .trim()
                    .trimEnd('.', ',', ';', ':')
                if (text.isNotBlank() && cut.first !in notes) {
                    notes[cut.first] = NoteRef(cut.first, text, section)
                }
            }
            return notes
        }

        private fun normalize(title: String): String = title
            .lowercase()
            .replace('·', ' ')
            .replace('.', ' ')
            .replace(WHITESPACE, " ")
            .trim()

        private const val MAX_NOTES = 500
    }
}
