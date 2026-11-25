package ru.vtext.app.domain.parser

import java.io.File
import ru.vtext.app.domain.model.ParsedBook
import ru.vtext.app.domain.model.TextPreset

interface BookParser {
    /** [assetsDir] — куда складывать картинки (если формат их содержит). */
    fun parse(
        file: File,
        fallbackTitle: String,
        preset: TextPreset = TextPreset.AUTO,
        assetsDir: File? = null,
    ): ParsedBook
}
