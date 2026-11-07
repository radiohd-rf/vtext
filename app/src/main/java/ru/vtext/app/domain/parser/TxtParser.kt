package ru.vtext.app.domain.parser

import java.io.File
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook

/** Плоский текст. Главы выделяются по строкам-заголовкам вида «Глава 3», «Часть II». */
class TxtParser : BookParser {

    private val heading = Regex(
        """^\s*(глава|часть|chapter|part)\s+[\dIVXLС]+.*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun parse(file: File, fallbackTitle: String): ParsedBook {
        val text = TextDecoder.decode(file.readBytes())
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val blocks = mutableListOf<Pair<String, StringBuilder>>()
        var currentTitle = "Текст"
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
            Chapter(title, TextSegmenter.segment(body.toString()))
        }.filter { it.segments.isNotEmpty() }

        val safeChapters = if (chapters.isEmpty()) {
            listOf(Chapter("Текст", TextSegmenter.segment(text)))
        } else {
            chapters
        }
        return ParsedBook(fallbackTitle, null, safeChapters)
    }
}
