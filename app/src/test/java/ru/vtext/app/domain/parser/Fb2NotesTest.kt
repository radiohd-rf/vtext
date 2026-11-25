package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class Fb2NotesTest {

    @Test
    fun extractsNoteLinksAndNotesBody() {
        val file = File("G:\\silero-tmp\\notes-test.fb2")
        if (!file.isFile) return
        val parsed = Fb2Parser().parse(file, "Test", TextPreset.BOOK, null)
        val text = parsed.chapters.flatMap { it.segments }.joinToString(" ")
        val notes = parsed.chapters.flatMap { it.footnotes }
        println("TEXT: $text")
        println("NOTES: $notes")
        assertTrue(text, text.contains("⟦n1-1⟧"))
        assertTrue(notes.toString(), notes.any { it.label == "n1-1" && it.text.contains("Note text for FB2") })
        assertTrue("note text must not stay in the body", !text.contains("Note text for FB2"))
    }
}
