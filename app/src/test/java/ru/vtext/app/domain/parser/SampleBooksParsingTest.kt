package ru.vtext.app.domain.parser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.domain.model.TextPreset

/**
 * Проверка парсеров на реальных тестовых книгах из общедоступной классики:
 * FB2 и EPUB — «Духовные оды» Державина, DOCX — историческая проза.
 */
class SampleBooksParsingTest {

    @Test
    fun parsesFb2PoetryAndSkipsNotesBody() {
        val file = copyResource("books/derzhavin-test.fb2")
        try {
            val parsed = Fb2Parser().parse(file, "Державин", TextPreset.BOOK)
            assertTrue("нет глав", parsed.chapters.isNotEmpty())
            assertTrue("автор не разобран", parsed.author?.contains("Державин") == true)
            val text = parsed.chapters.joinToString("\n") { it.segments.joinToString("\n") }
            assertTrue("слишком короткий текст: ${text.length}", text.length > 200)
            assertTrue("нет разметки эмфазы", text.contains("*"))
            assertFalse("номер сноски <sup> не должен читаться", text.contains("оде1"))
            assertFalse(
                "тело сносок не должно читаться",
                text.contains("не должен читаться вместе с одой"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesDocxWithEmphasis() {
        val file = copyResource("books/moskovia-test.docx")
        try {
            val parsed = DocxParser().parse(file, "Московия", TextPreset.BOOK)
            assertTrue("нет глав", parsed.chapters.isNotEmpty())
            val text = parsed.chapters.joinToString("\n") { it.segments.joinToString("\n") }
            assertTrue("курсив не размечен", text.contains("*Курсивный фрагмент*"))
            assertTrue("жирный не размечен", text.contains("**жирного текста**"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesRealEpubBook() {
        val file = copyResource("books/derzhavin.epub")
        try {
            val parsed = EpubParser().parse(file, "Державин", TextPreset.BOOK)
            assertTrue("нет глав", parsed.chapters.isNotEmpty())
            val text = parsed.chapters.joinToString("\n") { it.segments.joinToString("\n") }
            assertTrue("нет кириллицы в тексте", text.any { it in 'А'..'я' })
        } finally {
            file.delete()
        }
    }

    private fun copyResource(path: String): File {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "нет ресурса $path" }
        val file = File.createTempFile("sample", ".bin")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}
