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
 * Строится по главам «Примечания»/«Комментарии»/«Notes»: раздел примечаний
 * относится к главам, которые стоят перед ним, поэтому в книгах с двумя
 * блоками примечаний (к предисловию и к основному тексту) каждый получает
 * свой. Внутри блока номера идут подряд, сбрасываясь на каждом разделе,
 * поэтому режем по ожидаемому номеру (или по маркерам ⟦N⟧ из PDF).
 */
class BookNotes private constructor(
    private val sections: List<Section>,
    private val chapterSections: Map<Int, Section>,
    private val chapterFootnotes: Map<Int, Map<String, NoteRef>>,
    val sourceChapter: String,
) {
    data class Section(val key: String, val title: String, val notes: Map<String, NoteRef>)

    /**
     * Примечание для главы: подглавы («Московские архитекторы на войне»)
     * наследуют раздел своей «Главы N».
     */
    fun noteFor(chapterIndex: Int, label: String): NoteRef? =
        chapterFootnotes[chapterIndex]?.get(label)
            ?: chapterSections[chapterIndex]?.notes?.get(label)

    companion object {
        private val NOTES_TITLE = Regex("(примеч|коммент|сноск|notes)", RegexOption.IGNORE_CASE)

        private val SECTION = Regex(
            "(Введение|Предисловие|Заключение|Эпилог|Приложение(?:\\s*\\d+)?|" +
                "Глава\\s+\\d+(?:\\.\\s*[^0-9]{0,60})?)",
        )

        private val MARKER = Regex("⟦([^⟧]{1,6})⟧")
        private val WHITESPACE = Regex("\\s+")

        fun parse(book: Book): BookNotes? {
            // Сноски страниц есть и у книг без главы «Примечания».
            val pageNotes = book.chapters.mapIndexed { index, chapter ->
                index to chapter.footnotes.associate { note ->
                    note.label to NoteRef(note.label, note.text, chapter.title)
                }
            }.toMap()
            val hasPageNotes = pageNotes.values.any { it.isNotEmpty() }
            val notesIndexes = book.chapters.indices.filter { index ->
                NOTES_TITLE.containsMatchIn(book.chapters[index].title)
            }
            if (notesIndexes.isEmpty()) {
                return if (hasPageNotes) BookNotes(emptyList(), emptyMap(), pageNotes, "") else null
            }

            val chapterSections = mutableMapOf<Int, Section>()
            var sourceTitle = ""
            notesIndexes.forEach { notesIndex ->
                val chapter = book.chapters[notesIndex]
                if (sourceTitle.isBlank()) sourceTitle = chapter.title
                val text = chapter.segments
                    .joinToString(" ")
                    .replace(WHITESPACE, " ")
                    .trim()
                if (text.isBlank()) return@forEach
                val sections = sectionsOf(text, chapter.title)
                if (sections.isEmpty()) return@forEach
                // Блок примечаний относится к главам после предыдущего блока.
                val previous = notesIndexes.lastOrNull { it < notesIndex } ?: -1
                var current: Section? = sections.firstOrNull()
                for (index in (previous + 1) until notesIndex) {
                    val key = normalize(book.chapters[index].title)
                    val match = sections.firstOrNull { it.key == key }
                        ?: sections.firstOrNull {
                            key.isNotBlank() && (it.key.startsWith(key) || key.startsWith(it.key))
                        }
                    if (match != null) current = match
                    current?.let { chapterSections[index] = it }
                }
            }
            if (chapterSections.isEmpty() && !hasPageNotes) return null
            return BookNotes(emptyList(), chapterSections, pageNotes, sourceTitle)
        }

        /** Разделы внутри блока примечаний; если их нет — весь блок одним разделом. */
        private fun sectionsOf(text: String, title: String): List<Section> {
            val headings = SECTION.findAll(text).filter { match ->
                val heading = match.groupValues[1]
                // Настоящий раздел начинается с заглавной и сразу за ним идёт «1 »;
                // строчные колонтитулы («введение») и числа в цитатах отсеиваются.
                val uppercase = heading.firstOrNull()?.isUpperCase() == true
                val after = text.substring(match.range.last + 1).trimStart()
                uppercase && after.startsWith("1 ")
            }.toList()
            if (headings.isEmpty()) {
                val notes = splitNotes(text, title)
                if (notes.isEmpty()) return emptyList()
                return listOf(Section(normalize(title), title, notes))
            }
            return headings.mapIndexed { index, match ->
                val start = match.range.last + 1
                val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                val heading = match.groupValues[1].trim()
                Section(normalize(heading), heading, splitNotes(text.substring(start, end), heading))
            }
        }

        private fun splitNotes(body: String, section: String): Map<String, NoteRef> {
            val byMarker = splitNotesByMarkers(body, section)
            if (byMarker.isNotEmpty()) return byMarker
            return splitNotesByNumbers(body, section)
        }

        /** PDF: примечания размечены маркерами ⟦1⟧, ⟦2⟧, ⟦*⟧. */
        private fun splitNotesByMarkers(body: String, section: String): Map<String, NoteRef> {
            val cuts = MARKER.findAll(body).take(MAX_NOTES).toList()
            if (cuts.isEmpty()) return emptyMap()
            val notes = mutableMapOf<String, NoteRef>()
            cuts.forEachIndexed { index, cut ->
                val stop = cuts.getOrNull(index + 1)?.range?.first ?: body.length
                val label = cut.groupValues[1].trim()
                val text = body.substring(cut.range.last + 1, stop)
                    .replace(WHITESPACE, " ")
                    .trim()
                    .trimEnd('.', ',', ';', ':')
                if (label.isNotBlank() && text.isNotBlank() && label !in notes) {
                    notes[label] = NoteRef(label, text, section)
                }
            }
            return notes
        }

        /** Текст: примечания разделены номером с пробелом («1 Текст…»). */
        private fun splitNotesByNumbers(body: String, section: String): Map<String, NoteRef> {
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
