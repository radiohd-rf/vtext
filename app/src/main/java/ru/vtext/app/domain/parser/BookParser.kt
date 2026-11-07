package ru.vtext.app.domain.parser

import java.io.File
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

interface BookParser {
    fun parse(file: File, fallbackTitle: String, preset: TextPreset = TextPreset.AUTO): ParsedBook
}
