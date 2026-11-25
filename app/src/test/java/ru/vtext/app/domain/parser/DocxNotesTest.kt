package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class DocxNotesTest {

    @Test
    fun extractsFootnoteReferenceAndText() {
        val file = File("G:\\silero-tmp\\notes-test.docx")
        if (!file.isFile) return
        val parsed = DocxParser().parse(file, "Test", TextPreset.BOOK, null)
        val text = parsed.chapters.flatMap { it.segments }.joinToString(" ")
        val notes = parsed.chapters.flatMap { it.footnotes }
        println("TEXT: $text")
        println("NOTES: $notes")
        assertTrue(text, text.contains("⟦fn1-1⟧"))
        assertTrue(notes.toString(), notes.any { it.label == "fn1-1" && it.text.contains("Note text for DOCX") })
        assertTrue("note text must not stay in the body", !text.contains("Note text for DOCX"))
        assertTrue("separator must be skipped", notes.none { it.text.contains("separator") })
    }
}
