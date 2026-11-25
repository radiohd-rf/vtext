package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class DocxRealBookTest {

    @Test
    fun resolvesHeadingStylesAndSkipsToc() {
        val file = File("G:\\silero-tmp\\krukh_device.docx")
        if (!file.isFile) return
        val parsed = DocxParser().parse(file, "Krukh", TextPreset.BOOK, null)
        val titles = parsed.chapters.map { it.title }
        val text = parsed.chapters.flatMap { it.segments }.joinToString(" ")
        val dump = StringBuilder()
        dump.append("chapters: ").append(titles.size).append('\n')
        titles.take(25).forEach { dump.append("  ").append(it).append('\n') }
        dump.append("chars: ").append(text.length).append('\n')
        val notes = parsed.chapters.flatMap { it.footnotes }
        dump.append("notes: ").append(notes.size).append('\n')
        File("G:\\silero-tmp\\docx_out.txt").writeText(dump.toString())
        assertTrue("too few chapters: ${titles.size}", titles.size >= 100)
        assertTrue("TOC leaked into text", !text.contains("Оглавление"))
        assertTrue("notes missing", notes.isNotEmpty())
    }
}
