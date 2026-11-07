package ru.vtext.app.domain

/**
 * Разметка эмфазы в тексте книги: `**слово**` — нажим, `*слово*` — логическое
 * ударение. Этой разметкой пользуется Silero, остальные движки и UI её снимают.
 */
object TextMarkup {

    fun strip(text: String): String = if ('*' !in text) text else text.replace("*", "")

    fun bold(text: String): String = "**$text**"

    fun italic(text: String): String = "*$text*"
}
