package ru.vtext.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.tts.SileroText

class NoteMarkupTest {

    @Test
    fun prepareKeepsPageMarkerThroughDisplayCleanup() {
        val source = "…все просахалинено“»⟦p10-1⟧. Далее текст.21"
        val prepared = NoteMarkup.prepare(source) { SileroText.displayText(it) }
        println("PREPARED: $prepared")
        assertTrue(prepared, prepared.contains("⟦p10-1⟧"))
        assertTrue("glued number must be removed", !prepared.contains("текст.21"))
    }

    @Test
    fun renderShowsOnlyNoteNumber() {
        val (text, spans) = NoteMarkup.render("Слово⟦p10-1⟧ и ⟦29⟧ конец")
        println("TEXT: $text SPANS: $spans")
        assertEquals("Слово[1] и [29] конец", text)
        assertEquals(2, spans.size)
        assertEquals("p10-1", spans[0].label)
        assertEquals("29", spans[1].label)
    }

    @Test
    fun prepareKeepsDocxFootnoteMarker() {
        val source = "не был никогда напечатан⟦fn1-1⟧. Далее"
        val prepared = NoteMarkup.prepare(source) { SileroText.displayText(it) }
        println("DOCX PREPARED: $prepared")
        assertTrue(prepared, prepared.contains("⟦fn1-1⟧"))
        val (text, spans) = NoteMarkup.render(prepared)
        assertEquals("не был никогда напечатан[1]. Далее", text)
        assertEquals("fn1-1", spans.single().label)
    }
}
