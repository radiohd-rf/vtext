package ru.vtext.app.domain.parser

/**
 * XML в FB2 часто содержит DOCTYPE со ссылками на внешние DTD и HTML-сущности,
 * которые стандартный парсер не умеет разрешать без сети. Чистим документ заранее.
 */
object XmlSanitizer {

    private val doctype = Regex("(?s)<!DOCTYPE.*?>")
    private val namedEntity = Regex("&([a-zA-Z][a-zA-Z0-9]+);")

    private val knownEntities = mapOf(
        "nbsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "laquo" to "«",
        "raquo" to "»",
        "hellip" to "…",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "amp" to "&",
        "copy" to "©",
        "reg" to "®",
        "deg" to "°",
        "middot" to "·",
        "bull" to "•",
    )

    fun sanitize(xml: String): String {
        var result = doctype.replace(xml, "")
        result = namedEntity.replace(result) { match ->
            val name = match.groupValues[1]
            when (name) {
                "amp", "lt", "gt", "quot", "apos" -> match.value
                else -> knownEntities[name] ?: " "
            }
        }
        return result
    }
}
