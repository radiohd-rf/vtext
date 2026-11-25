package ru.vtext.app.domain.notes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.Chapter

class BookNotesRealBookTest {

    @Test
    fun a4NotesUseTheCorrectBlock() {
        val file = File("G:\\silero-tmp\\a4_notes_fixture.txt")
        if (!file.isFile) return
        val chapters = mutableListOf<Pair<String, MutableList<String>>>()
        file.forEachLine { line ->
            when {
                line.startsWith("CH|") -> chapters += line.removePrefix("CH|") to mutableListOf()
                line.startsWith("SEG|") && chapters.isNotEmpty() ->
                    chapters.last().second += line.removePrefix("SEG|")
            }
        }
        val book = Book(
            id = "a4",
            title = "A4",
            author = null,
            addedAt = 0L,
            chapters = chapters.map { Chapter(it.first, it.second) },
        )
        val notes = BookNotes.parse(book) ?: error("notes not parsed")

        val preface = notes.noteFor(2, "1")
        println("PREFACE: $preface")
        assertTrue(preface.toString(), preface?.text?.contains("Крестинская") == true)

        val main = notes.noteFor(5, "1")
        println("MAIN: $main")
        assertTrue(main.toString(), main?.text?.contains("Браутон") == true)
    }
}
