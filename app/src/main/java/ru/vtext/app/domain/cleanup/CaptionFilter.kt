package ru.vtext.app.domain.cleanup

/** Убирает подписи к рисункам и таблицам из озвучиваемого текста. */
object CaptionFilter {

    private val CAPTION = Regex(
        """^\s*(?:\d+\s*[.)]?\s*)?(рис\.?|рисунок|figure|fig\.?|таблица|table|схема|diagram)\s*[\dIVX]""",
        RegexOption.IGNORE_CASE,
    )

    fun removeCaptions(text: String): String = text.lineSequence()
        .filterNot { CAPTION.containsMatchIn(it.trim()) }
        .joinToString("\n")
}
