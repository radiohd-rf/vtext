package ru.vtext.app.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter

class BookNotesTest {

    private fun book(vararg chapters: Pair<String, List<String>>): Book = Book(
        id = "test",
        title = "Test",
        author = null,
        addedAt = 0L,
        chapters = chapters.map { Chapter(it.first, it.second) },
    )

    @Test
    fun twoNoteBlocksAreAssignedToPrecedingChapters() {
        val book = book(
            "Предисловие" to listOf("Текст предисловия⟦1⟧."),
            "Примечания" to listOf("Примечания ⟦1⟧Сноска к предисловию."),
            "Из Сибири" to listOf("Текст главы⟦1⟧ и ещё⟦5⟧."),
            "Примечания" to listOf(
                "Примечания ⟦1⟧Сноска к главе. ⟦2⟧Вторая. ⟦3⟧Третья. ⟦4⟧Четвёртая. ⟦5⟧Пятая.",
            ),
            "Источники иллюстраций" to listOf("Источники"),
        )
        val notes = BookNotes.parse(book)
        assertNotNull(notes)
        assertEquals("Сноска к предисловию", notes!!.noteFor(0, "1")?.text)
        assertEquals("Сноска к главе", notes.noteFor(2, "1")?.text)
        assertEquals("Пятая", notes.noteFor(2, "5")?.text)
        // Глава после второго блока примечаний своих примечаний не имеет.
        assertEquals(null, notes.noteFor(4, "1"))
    }

    @Test
    fun singleNotesChapterAppliesToAllPrecedingChapters() {
        val book = book(
            "Глава 1" to listOf("Текст⟦2⟧."),
            "Глава 2" to listOf("Текст⟦2⟧."),
            "Примечания" to listOf("Примечания ⟦1⟧Первая. ⟦2⟧Вторая."),
        )
        val notes = BookNotes.parse(book)!!
        assertEquals("Вторая", notes.noteFor(0, "2")?.text)
        assertEquals("Вторая", notes.noteFor(1, "2")?.text)
    }
}
