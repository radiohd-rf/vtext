package ru.vtext.app.domain.parser

/** Извлекает читаемый текст из XHTML/HTML-главы EPUB. */
object HtmlText {

    private val ignoredBlocks = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
    private val imageTag = Regex("(?is)<img\\b[^>]*>")
    private val altAttribute = Regex("""(?is)\balt\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val lineBreak = Regex("(?i)<br\\s*/?>")
    private val noteref = Regex("(?is)<a\\b[^>]*epub:type\\s*=\\s*[\"']noteref[\"'][^>]*>.*?</a>")
    private val boldTag = Regex("(?is)<(b|strong)\\b[^>]*>(.*?)</\\1>")
    private val italicTag = Regex("(?is)<(i|em|cite)\\b[^>]*>(.*?)</\\1>")
    private val blockEnd = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>")
    private val anyTag = Regex("(?s)<[^>]+>")

    fun extract(html: String): String {
        var text = ignoredBlocks.replace(html, " ")
        // Alt-текст изображений полезен для озвучки схем и рисунков.
        text = imageTag.replace(text) { match ->
            val alt = altAttribute.find(match.value)?.let { attr ->
                attr.groupValues[1].ifBlank { attr.groupValues[2] }
            }.orEmpty().trim()
            if (alt.isEmpty()) " " else "\n${decodeEntities(alt)}\n"
        }
        // Выделения автора сохраняем разметкой: Silero читает их с нажимом.
        text = noteref.replace(text, " ")
        text = boldTag.replace(text) { match -> "**${match.groupValues[2]}**" }
        text = italicTag.replace(text) { match -> "*${match.groupValues[2]}*" }
        text = lineBreak.replace(text, "\n")
        text = blockEnd.replace(text, "\n\n")
        text = anyTag.replace(text, "")
        text = decodeEntities(text)

        val lines = text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.replace(Regex("[ \\t\\u000B\\f]+"), " ").trim() }
        return lines.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun htmlTitle(html: String): String? {
        val heading = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
        return heading?.let { decodeEntities(anyTag.replace(it.groupValues[1], " ")).trim() }
            ?.ifBlank { null }
    }

    private val numericEntity = Regex("&#(\\d+);")
    private val hexEntity = Regex("&#x([0-9a-fA-F]+);")

    fun decodeEntities(text: String): String {
        var result = text
        result = numericEntity.replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
        }
        result = hexEntity.replace(result) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: " "
        }
        result = result
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&hellip;", "…")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return result
    }
}
