package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class FootnotePageTest {

    @Test
    fun extractsPageFootnotesAndMarksThem() {
        val file = File("G:\\silero-tmp\\fn-test.pdf")
        if (!file.isFile) return
        val pages = PdfParser().kitePages(file, TextPreset.BOOK)
        val text = pages.joinToString(" ") { it.text }
        val notes = pages.flatMap { it.footnotes }
        println("TEXT: $text")
        println("NOTES: $notes")
        assertTrue(text, text.contains("⟦p1-1⟧"))
        assertTrue(text, text.contains("⟦p2-2⟧"))
        assertTrue(notes.toString(), notes.any { it.label == "p1-1" && it.text.contains("page one") })
        assertTrue(notes.toString(), notes.any { it.label == "p2-2" && it.text.contains("page two") })
        assertTrue("footnote text must not stay in the body", !text.contains("Footnote text"))
    }
}
