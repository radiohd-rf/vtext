package ru.vtext.app.domain.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook

/**
 * Разбор PDF через PDFBox: текст извлекается постранично.
 * Страница — естественная единица навигации для PDF-документа.
 */
class PdfParser : BookParser {

    override fun parse(file: File, fallbackTitle: String): ParsedBook {
        return try {
            PDDocument.load(file).use { document ->
                val info = document.documentInformation
                val title = cleanMetadata(info?.title, TITLE_PLACEHOLDERS) ?: fallbackTitle
                val author = cleanMetadata(info?.author, AUTHOR_PLACEHOLDERS)

                val chapters = mutableListOf<Chapter>()
                val pageCount = document.numberOfPages
                for (page in 1..pageCount) {
                    val stripper = PDFTextStripper().apply {
                        startPage = page
                        endPage = page
                        sortByPosition = true
                    }
                    val text = runCatching { stripper.getText(document) }.getOrDefault("")
                    val segments = TextSegmenter.segment(text)
                    if (segments.isNotEmpty()) {
                        chapters += Chapter("Страница $page", segments)
                    }
                }
                ParsedBook(title, author, chapters)
            }
        } catch (_: Exception) {
            // Повреждённый, зашифрованный или недоступный PDF — вернём пустую книгу,
            // репозиторий подставит заглушку.
            ParsedBook(fallbackTitle, null, emptyList())
        }
    }

    /** PDF-генераторы часто оставляют заглушки вроде «untitled»/«anonymous». */
    private fun cleanMetadata(value: String?, placeholders: Set<String>): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.takeIf { it.lowercase() !in placeholders }
    }

    private companion object {
        val TITLE_PLACEHOLDERS = setOf("untitled", "без названия", "unknown")
        val AUTHOR_PLACEHOLDERS = setOf("anonymous", "unknown", "user", "admin")
    }
}
