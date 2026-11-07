package ru.vtext.app.domain.parser

import java.io.File
import ru.vtext.app.domain.model.ParsedBook

interface BookParser {
    fun parse(file: File, fallbackTitle: String): ParsedBook
}
