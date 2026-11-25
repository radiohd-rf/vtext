package ru.vtext.app.domain.parser

import java.io.File
import java.nio.charset.Charset
import ru.vtext.app.domain.TextSegmenter
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

/**
 * Разбор RTF: вытаскиваем текст, отбрасываем служебные группы (шрифты, стили,
 * картинки, колонтитулы), раскодируем `\'hh` в кодовой странице документа
 * (обычно windows-1251), `\uN` — в Unicode.
 *
 * Главы: сперва по стилям заголовков из таблицы стилей (`\s1` = «heading 1»),
 * затем по разрывам разделов `\sect`, затем по `\page`; если ничего нет —
 * одна глава. Абзацы — `\par`, строки внутри абзаца — `\line`.
 */
class RtfParser : BookParser {

    override fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset,
        assetsDir: File?,
    ): ParsedBook {
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        val codepage = ANSI_CODEPAGE.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 1251
        val charset = runCatching { Charset.forName("windows-$codepage") }
            .getOrDefault(Charsets.ISO_8859_1)

        val headingStyles = readHeadingStyles(raw)
        val paragraphs = extractParagraphs(raw, charset)
        val chapters = chaptersFromHeadings(paragraphs, headingStyles, preset)
            ?: chaptersFromSections(paragraphs, preset)
            ?: chaptersFromPages(paragraphs, preset)
            ?: singleChapter(paragraphs, fallbackTitle, preset)
        return ParsedBook(fallbackTitle, null, chapters)
    }

    // ----- Структура документа -----

    /** Абзац или служебный разрыв (страница/раздел). */
    private data class Block(val text: String, val style: Int, val breakType: BreakType)

    private enum class BreakType { NONE, PAGE, SECTION }

    /** Стили-заголовки: по имени в таблице стилей или по `\outlinelevel`. */
    private fun readHeadingStyles(source: String): Set<Int> {
        val result = mutableSetOf<Int>()
        val sheet = source.substringAfter("{\\stylesheet", "")
        STYLE_DEF.findAll(sheet).forEach { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@forEach
            val body = match.groupValues[2]
            // Имя стиля — последний фрагмент перед «;», без управляющих слов.
            val name = body.substringBeforeLast(';')
                .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val headingName = HEADING_NAME.containsMatchIn(name)
            val outline = body.contains("\\outlinelevel")
            if (headingName || outline) result += id
        }
        // В некоторых генераторах имя стиля лежит прямо в определении: «heading 1».
        if (result.isEmpty()) {
            STYLE_NAMED.findAll(sheet).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let { result += it }
            }
        }
        return result
    }

    private fun extractParagraphs(source: String, charset: Charset): List<Block> {
        val blocks = mutableListOf<Block>()
        val current = StringBuilder()
        val stack = ArrayDeque<Boolean>()
        var skip = false
        var style = 0
        var i = 0

        fun flush(type: BreakType = BreakType.NONE) {
            val text = current.toString()
                .replace(Regex("[ \\t]+"), " ")
                .trim()
            if (text.isNotEmpty()) blocks += Block(text, style, BreakType.NONE)
            current.setLength(0)
            if (type != BreakType.NONE) blocks += Block("", style, type)
        }

        while (i < source.length) {
            val char = source[i]
            when (char) {
                '{' -> {
                    stack.addLast(skip)
                    i += 1
                }
                '}' -> {
                    skip = stack.removeLastOrNull() ?: false
                    i += 1
                }
                '\\' -> {
                    i += 1
                    if (i >= source.length) break
                    val next = source[i]
                    if (next.isLetter()) {
                        val wordStart = i
                        while (i < source.length && source[i].isLetter()) i += 1
                        val word = source.substring(wordStart, i)
                        var param = ""
                        if (i < source.length && (source[i] == '-' || source[i].isDigit())) {
                            val paramStart = i
                            if (source[i] == '-') i += 1
                            while (i < source.length && source[i].isDigit()) i += 1
                            param = source.substring(paramStart, i)
                        }
                        if (i < source.length && source[i] == ' ') i += 1
                        if (!skip) {
                            when (word) {
                                "par" -> flush()
                                "line" -> current.append(' ')
                                "page" -> flush(BreakType.PAGE)
                                "sect" -> flush(BreakType.SECTION)
                                "s" -> style = param.toIntOrNull() ?: style
                                "u" -> {
                                    val code = param.toIntOrNull() ?: 0
                                    if (code > 0) current.append(code.toChar())
                                    // После \uN идёт запасной символ — пропускаем.
                                    if (i < source.length) i += 1
                                }
                                "bin" -> i = (i + (param.toIntOrNull() ?: 0))
                                    .coerceAtMost(source.length)
                                in SKIP_DESTINATIONS -> skip = true
                                else -> Unit
                            }
                        }
                    } else if (next == '\'') {
                        val hex = source.substring(i + 1, minOf(i + 3, source.length))
                        i += 3
                        if (!skip) {
                            val byte = hex.toIntOrNull(16) ?: 0
                            current.append(String(byteArrayOf(byte.toByte()), charset))
                        }
                    } else {
                        if (next == '*') {
                            skip = true
                        } else if (!skip && (next == '\\' || next == '{' || next == '}')) {
                            current.append(next)
                        } else if (!skip && next == '~') {
                            // Неразрывный пробел — обычный пробел, иначе слова склеиваются.
                            current.append(' ')
                        } else if (!skip && next == '_') {
                            current.append('-')
                        }
                        i += 1
                    }
                }
                '\r' -> i += 1
                '\n' -> i += 1
                else -> {
                    if (!skip) {
                        // Сырые байты >127 (встречаются вместо \'hh) — в кодировке документа.
                        if (char.code > 127) {
                            current.append(String(byteArrayOf(char.code.toByte()), charset))
                        } else {
                            current.append(char)
                        }
                    }
                    i += 1
                }
            }
        }
        flush()
        return blocks
    }

    // ----- Главы -----

    private fun chaptersFromHeadings(
        blocks: List<Block>,
        headingStyles: Set<Int>,
        preset: TextPreset,
    ): List<Chapter>? {
        if (headingStyles.isEmpty()) return null
        val headingIndexes = blocks.indices.filter { index ->
            val block = blocks[index]
            block.breakType == BreakType.NONE &&
                block.style in headingStyles &&
                block.text.isNotBlank()
        }
        if (headingIndexes.size < 2) return null
        val chapters = mutableListOf<Chapter>()
        headingIndexes.forEachIndexed { position, start ->
            val end = headingIndexes.getOrNull(position + 1) ?: blocks.size
            val body = bodyText(blocks, start + 1, end)
            if (body.isBlank()) return@forEachIndexed
            val segments = TextSegmenter.segmentFor(body, preset)
            if (segments.isEmpty()) return@forEachIndexed
            chapters += Chapter(blocks[start].text, segments)
        }
        return chapters.ifEmpty { null }
    }

    private fun chaptersFromSections(blocks: List<Block>, preset: TextPreset): List<Chapter>? {
        if (blocks.count { it.breakType == BreakType.SECTION } < 1) return null
        val sections = splitByBreak(blocks, BreakType.SECTION)
        if (sections.size < 2) return null
        return sections.mapIndexedNotNull { index, body ->
            val segments = TextSegmenter.segmentFor(body, preset)
            if (segments.isEmpty()) null else Chapter("Раздел ${index + 1}", segments)
        }.ifEmpty { null }
    }

    private fun chaptersFromPages(blocks: List<Block>, preset: TextPreset): List<Chapter>? {
        if (blocks.none { it.breakType == BreakType.PAGE }) return null
        val pages = splitByBreak(blocks, BreakType.PAGE)
        if (pages.size < 2) return null
        return pages.mapIndexedNotNull { index, body ->
            val segments = TextSegmenter.segmentFor(body, preset)
            if (segments.isEmpty()) null else Chapter("Страница ${index + 1}", segments)
        }.ifEmpty { null }
    }

    private fun singleChapter(
        blocks: List<Block>,
        fallbackTitle: String,
        preset: TextPreset,
    ): List<Chapter> {
        val text = bodyText(blocks, 0, blocks.size)
        val segments = TextSegmenter.segmentFor(text, preset)
        return if (segments.isEmpty()) emptyList() else listOf(Chapter(fallbackTitle, segments))
    }

    private fun splitByBreak(blocks: List<Block>, type: BreakType): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        blocks.forEachIndexed { index, block ->
            if (block.breakType == type) {
                parts += bodyText(blocks, start, index)
                start = index + 1
            }
        }
        parts += bodyText(blocks, start, blocks.size)
        return parts.filter { it.isNotBlank() }
    }

    private fun bodyText(blocks: List<Block>, from: Int, to: Int): String =
        blocks.subList(from.coerceAtLeast(0), to.coerceAtMost(blocks.size))
            .filter { it.breakType == BreakType.NONE && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private companion object {
        val ANSI_CODEPAGE = Regex("\\\\ansicpg(\\d+)")
        val STYLE_DEF = Regex("\\{\\\\s(\\d+)([^{}]*)\\}")
        val STYLE_NAMED = Regex("\\{\\\\s(\\d+)[^{}]*?([Hh]eading|[Зз]аголовок)")
        val HEADING_NAME = Regex(
            "(?i)heading|заголовок|название|title|section",
        )
        val SKIP_DESTINATIONS = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
            "header", "footer", "headerl", "headerr", "footerl", "footerr",
            "listtable", "listoverridetable", "rsidtbl", "generator",
            "xmlnstbl", "themedata", "colorschememapping", "latentstyles",
            "datastore", "mmathPr", "filetbl", "revtbl", "pnseclvl",
            "footnote", "annotation", "fldinst",
        )
    }
}
