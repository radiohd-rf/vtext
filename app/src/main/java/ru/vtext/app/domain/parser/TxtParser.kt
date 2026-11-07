package ru.vtext.app.domain.parser

import java.io.File
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.cleanup.CleanupOptions
import ru.vtext.app.domain.cleanup.ImportTextCleaner
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/** Плоский текст. Главы выделяются по строкам-заголовкам вида «Глава 3», «Часть II». */
class TxtParser : BookParser {

    private val heading = Regex(
        """^\s*(глава|часть|chapter|part)\s+[\dIVXLС]+.*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
    ): ParsedBook {
        val raw = TextDecoder.decode(file.readBytes())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        // Для TXT страницы определяются по form feed; если их нет,
        // очистка сводится к номерам страниц и переносам слов.
        val text = ImportTextCleaner.cleanText(
            raw,
            CleanupOptions(joinHyphenation = preset != TextPreset.POETRY),
        ).text()

        val blocks = mutableListOf<Pair<String, StringBuilder>>()
        var currentTitle = if (preset == TextPreset.POETRY) "Стихи" else "Текст"
        var buffer = StringBuilder()

        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= 100 && heading.matches(trimmed)) {
                if (buffer.isNotBlank()) blocks += currentTitle to buffer
                currentTitle = trimmed
                buffer = StringBuilder()
            } else {
                buffer.appendLine(line)
            }
        }
        if (buffer.isNotBlank()) blocks += currentTitle to buffer

        val chapters = blocks.map { (title, body) ->
            Chapter(title, TextSegmenter.segmentFor(body.toString(), preset))
        }.filter { it.segments.isNotEmpty() }

        val safeChapters = if (chapters.isEmpty()) {
            listOf(Chapter(currentTitle, TextSegmenter.segmentFor(text, preset)))
        } else {
            chapters
        }
        return ParsedBook(fallbackTitle, null, safeChapters)
    }
}
