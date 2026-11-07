package ru.vtext.app.domain.model

/** Пресет обработки текста при импорте. */
enum class TextPreset(val id: String) {
    AUTO("auto"),
    BOOK("book"),
    POETRY("poetry"),
    ;

    companion object {
        fun fromId(id: String?): TextPreset = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
