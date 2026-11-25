package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

class RtfChaptersTest {

    @Test
    fun splitsByHeadingStyles() {
        val file = File("G:\\silero-tmp\\chekhov.rtf")
        if (!file.isFile) return
        val parsed = RtfParser().parse(file, "Chekhov", TextPreset.BOOK, null)
        val titles = parsed.chapters.map { it.title }
        val dump = StringBuilder()
        dump.append("CHAPTERS: ").append(titles.size).append('\n')
        titles.forEachIndexed { index, title ->
            dump.append(index + 1).append(": ").append(title).append('\n')
        }
        dump.append("\nFIRST PARAGRAPH: ")
            .append(parsed.chapters.first().segments.first()).append('\n')
        File("G:\\silero-tmp\\rtf_out.txt").writeText(dump.toString())
        assertTrue("too few chapters: ${titles.size}", titles.size >= 20)
        assertTrue("heading looks wrong: ${titles.first()}", titles.first().length < 120)
        assertTrue(parsed.chapters.first().segments.isNotEmpty())
    }
}
