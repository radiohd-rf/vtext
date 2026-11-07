package ru.vtext.app.tts

/**
 * Текстовая подготовка Silero v5: нормализация, числа, типы предложений,
 * разметка ударений и построение символьной последовательности для акустической модели.
 *
 * Порт `multi_acc_v3_package.py` и `accentor.py` из состава модели Silero.
 */
internal object SileroText {

    private val VOWELS = "аоуыэиеяёю"
    private val WORD_RE = Regex("[а-яёa-z]+", RegexOption.IGNORE_CASE)
    private val SENT_SPLIT_RE = Regex("(?<=[.!?])\\s+")
    private val TOKEN_SPLIT_RE = Regex("([\\s.,!?;:<>=()/\\\\]+)")
    private val LATIN_OR_DIGIT = Regex("[^А-Яа-яёЁ]")

    private val WH_FORMS = setOf(
        "кто", "кого", "кому", "кем", "ком",
        "что", "чего", "чему", "чем", "чём", "чё", "чо",
        "где", "куда", "откуда", "когда",
        "почему", "зачем", "как", "почём", "отчего", "насколько",
        "сколько", "скольких", "скольким", "сколькими",
        "какой", "какая", "какое", "какие",
        "какого", "каких", "какому", "каким",
        "какими", "каком", "какую",
        "чей", "чья", "чьё", "чье", "чьи",
        "чьего", "чьей", "чьих",
        "чьему", "чьим", "чьими",
        "чьём", "чьем", "чью",
        "который", "которая", "которое", "которые",
        "которого", "которой", "которых",
        "которому", "которым", "которыми",
        "котором", "которую",
        "каков", "какова", "каково", "каковы",
    )

    private val LEADING_FILLERS = setOf(
        "а", "ну", "и", "так", "вот", "слышь", "слушай",
        "скажите", "скажи", "пожалуйста",
        "вобще", "вообще", "типа", "короче",
    )

    private val TAG_RE = Regex(
        "(?:[,\\s]+(правда|верно|да)\\s*\\?$)" +
            "|(?:[,\\s]+(не\\s+так\\s+ли|не\\s+правда\\s+ли|разве\\s+не\\s+так|ведь\\s+так)\\s*\\?$)" +
            "|(?:[,\\s]+ведь\\s*\\?$)" +
            "|(?:[,\\s]+а\\s*\\?$)",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTE_OPEN = setOf('"', '«', '\u201c', '\u201e')
    private val QUOTE_CLOSE = setOf('"', '»', '\u201d', '\u2019')

    private val BOLD_RE = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val ITALIC_RE = Regex("\\*([^*\\n]+)\\*")
    private val CAPS_RE = Regex("(?<![\\p{L}])([А-ЯЁ]{3,})(?![\\p{L}])")
    private val FOCUS_SKIP_CHARS = setOf('.', ',', '!', '?', ';', ':', '…', '–')
    const val FOCUS_INTENSITY = 3
    const val EMPHASIS_TYPE_SHIFT = 6L

    private val BREAK_TAG = Regex("(?is)<break\\b[^>]*>")
    private val ANY_TAG = Regex("(?s)<[^>]+>")
    private val TIME_ATTR = Regex("(?i)time\\s*=\\s*\"([0-9.]+)\\s*(ms|s)?\"")
    private val STRENGTH_ATTR = Regex("(?i)strength\\s*=\\s*\"([a-z-]+)\"")
    private val STRENGTH_MS = mapOf(
        "x-weak" to 75,
        "weak" to 75,
        "medium" to 150,
        "strong" to 300,
        "x-strong" to 1000,
    )
    private const val LINE_PAUSE_MS = 220

    // ----- Нормализация текста -----

    /** Числа записываем словами: Silero выбрасывает все цифры из входа. */
    fun spellNumbers(text: String): String = DECIMAL.replace(text) { match ->
        "${spellNumber(match.groupValues[1].toLong())} запятая ${spellNumber(match.groupValues[2].toLong())}"
    }.let { withDecimals ->
        Regex("\\d+").replace(withDecimals) { match ->
            spellNumber(match.value.toLongOrNull() ?: 0L)
        }
    }

    private val DECIMAL = Regex("(\\d+)[,.](\\d+)")

    /** Полная подготовка текста: словарь, сноски, сокращения, римские числа, годы, латиница, цифры. */
    fun normalizeForSpeech(text: String, userTerms: Map<String, String> = emptyMap()): String {
        var value = stripFootnotes(text)
        value = dropStandaloneRomanLines(value)
        value = expandAbbreviations(value)
        value = romanNumeralsToWords(value)
        value = yearsWithCase(value)
        value = applyUserDictionary(value, userTerms)
        value = transliterateLatin(value)
        return spellNumbers(value)
    }

    // ----- Сокращения и числа -----

    private val STANDALONE_ROMAN_LINE = Regex("(?m)^\\s*[IVXLCDM]{1,6}\\s*$")

    /** Номер строфы на отдельной строке («II», «XVII») не читаем. */
    fun dropStandaloneRomanLines(text: String): String =
        STANDALONE_ROMAN_LINE.replace(text, "").replace(Regex("\\n{3,}"), "\n\n")

    private fun abbreviation(pattern: String) = Regex(
        "(?i)(?<![а-яёa-z])(?:$pattern)",
    )

    private val ABBREVIATIONS = listOf(
        abbreviation("т\\.\\s*д\\.") to "так далее",
        abbreviation("т\\.\\s*п\\.") to "тому подобное",
        abbreviation("т\\.\\s*е\\.") to "то есть",
        abbreviation("см\\.") to "смотри",
        abbreviation("стр\\.") to "страница",
        abbreviation("рис\\.") to "рисунок",
        abbreviation("напр\\.") to "например",
        abbreviation("др\\.") to "другие",
        abbreviation("проф\\.") to "профессор",
        abbreviation("гр\\.") to "гражданин",
    )

    fun expandAbbreviations(text: String): String {
        var value = text
        for ((pattern, replacement) in ABBREVIATIONS) {
            value = pattern.replace(value, replacement)
        }
        return value
    }

    private val ROMAN_TOKEN = Regex("\\b[IVXLCDM]{1,6}\\b")
    private val ROMAN_VALUES = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000,
    )

    /** Римские числа читаем словами: «XIX» → «девятнадцать», «III» → «три». */
    fun romanNumeralsToWords(text: String): String = ROMAN_TOKEN.replace(text) { match ->
        val value = romanToInt(match.value)
        if (value in 1..3999) spellNumber(value.toLong()) else match.value
    }

    internal fun romanToInt(value: String): Int {
        var result = 0
        var previous = 0
        for (char in value.reversed()) {
            val current = ROMAN_VALUES[char] ?: return 0
            if (current < previous) result -= current else {
                result += current
                previous = current
            }
        }
        return result
    }

    private val YEAR_WITH_WORD = Regex(
        "(?<![\\d])([12]\\d{3})\\s*(году|года|годом|год|г\\.)(?![а-яё])",
        RegexOption.IGNORE_CASE,
    )

    /** «1805 году» → «тысяча восемьсот пятом году», «1805 года» → «…пятого года». */
    fun yearsWithCase(text: String): String = YEAR_WITH_WORD.replace(text) { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        val word = match.groupValues[2]
        val case = when (word.lowercase()) {
            "году" -> NumberCase.PREPOSITIONAL
            "года" -> NumberCase.GENITIVE
            else -> NumberCase.NOMINATIVE
        }
        val suffix = if (word.lowercase() == "г.") "году" else word
        "${yearWords(year, case)} $suffix"
    }

    enum class NumberCase { NOMINATIVE, GENITIVE, PREPOSITIONAL }

    private val ORDINAL_UNITS = arrayOf(
        triple("", "", ""),
        triple("первый", "первого", "первом"),
        triple("второй", "второго", "втором"),
        triple("третий", "третьего", "третьем"),
        triple("четвёртый", "четвёртого", "четвёртом"),
        triple("пятый", "пятого", "пятом"),
        triple("шестой", "шестого", "шестом"),
        triple("седьмой", "седьмого", "седьмом"),
        triple("восьмой", "восьмого", "восьмом"),
        triple("девятый", "девятого", "девятом"),
        triple("десятый", "десятого", "десятом"),
        triple("одиннадцатый", "одиннадцатого", "одиннадцатом"),
        triple("двенадцатый", "двенадцатого", "двенадцатом"),
        triple("тринадцатый", "тринадцатого", "тринадцатом"),
        triple("четырнадцатый", "четырнадцатого", "четырнадцатом"),
        triple("пятнадцатый", "пятнадцатого", "пятнадцатом"),
        triple("шестнадцатый", "шестнадцатого", "шестнадцатом"),
        triple("семнадцатый", "семнадцатого", "семнадцатом"),
        triple("восемнадцатый", "восемнадцатого", "восемнадцатом"),
        triple("девятнадцатый", "девятнадцатого", "девятнадцатом"),
    )

    private val ORDINAL_TENS = arrayOf(
        triple("", "", ""),
        triple("", "", ""),
        triple("двадцатый", "двадцатого", "двадцатом"),
        triple("тридцатый", "тридцатого", "тридцатом"),
        triple("сороковой", "сорокового", "сороковом"),
        triple("пятидесятый", "пятидесятого", "пятидесятом"),
        triple("шестидесятый", "шестидесятого", "шестидесятом"),
        triple("семидесятый", "семидесятого", "семидесятом"),
        triple("восьмидесятый", "восьмидесятого", "восьмидесятом"),
        triple("девяностый", "девяностого", "девяностом"),
    )

    private val ORDINAL_HUNDREDS = arrayOf(
        triple("", "", ""),
        triple("сотый", "сотого", "сотом"),
        triple("двухсотый", "двухсотого", "двухсотом"),
        triple("трёхсотый", "трёхсотого", "трёхсотом"),
        triple("четырёхсотый", "четырёхсотого", "четырёхсотом"),
        triple("пятисотый", "пятисотого", "пятисотом"),
        triple("шестисотый", "шестисотого", "шестисотом"),
        triple("семисотый", "семисотого", "семисотом"),
        triple("восьмисотый", "восьмисотого", "восьмисотом"),
        triple("девятисотый", "девятисотого", "девятисотом"),
    )

    private fun triple(nominative: String, genitive: String, prepositional: String) =
        arrayOf(nominative, genitive, prepositional)

    private fun Array<String>.form(case: NumberCase) = when (case) {
        NumberCase.NOMINATIVE -> this[0]
        NumberCase.GENITIVE -> this[1]
        NumberCase.PREPOSITIONAL -> this[2]
    }

    /** Год словами: 1805 → «тысяча восемьсот пятый», в предложном — «…пятом». */
    fun yearWords(year: Int, case: NumberCase): String {
        val thousand = when {
            year in 1000..1999 -> "тысяча"
            year in 2000..2999 -> "две тысячи"
            year in 3000..3999 -> "три тысячи"
            else -> return spellNumber(year.toLong())
        }
        val rest = year % 1000
        if (rest == 0) {
            return if (year == 1000) "тысячный" else "$thousand"
        }
        return "$thousand ${ordinalWords(rest, case)}"
    }

    /** Последний ненулевой разряд — порядковый, предыдущие — количественные. */
    private fun ordinalWords(value: Int, case: NumberCase): String {
        val hundreds = value / 100
        val tens = (value % 100) / 10
        val units = value % 10
        val teen = value % 100
        val parts = mutableListOf<String>()
        when {
            teen in 10..19 -> {
                if (hundreds > 0) parts += HUNDREDS[hundreds]
                parts += ORDINAL_UNITS[teen].form(case)
            }
            tens == 0 && units == 0 -> {
                if (hundreds > 0) parts += ORDINAL_HUNDREDS[hundreds].form(case)
            }
            tens > 0 && units == 0 -> {
                if (hundreds > 0) parts += HUNDREDS[hundreds]
                parts += ORDINAL_TENS[tens].form(case)
            }
            else -> {
                if (hundreds > 0) parts += HUNDREDS[hundreds]
                if (tens > 0) parts += TENS[tens]
                if (units > 0) parts += ORDINAL_UNITS[units].form(case)
            }
        }
        return parts.joinToString(" ")
    }

    /** Сноски вида «[12]» не читаем: в книгах это ссылки, а не текст. */
    fun stripFootnotes(text: String): String =
        GLUED_NUMBER_RE.replace(FOOTNOTE_RE.replace(text, ""), "")

    // ----- Пользовательский словарь -----

    /**
     * Заменяет слова по словарю пользователя: `python=пайтон`, `замок=з+амок`.
     * Значение с `+` accentor не трогает, поэтому так можно исправить ударение.
     */
    fun applyUserDictionary(text: String, terms: Map<String, String>): String {
        if (terms.isEmpty()) return text
        var result = text
        for ((word, pronunciation) in terms) {
            if (word.isBlank() || pronunciation.isBlank()) continue
            val pattern = Regex(
                "(?<![\\p{L}\\d])" + Regex.escape(word) + "(?![\\p{L}\\d])",
                RegexOption.IGNORE_CASE,
            )
            result = pattern.replace(result, pronunciation)
        }
        return result
    }

    /**
     * Читает словарь из текстового файла: по строке `слово=произношение`,
     * `#` — комментарий, пустые строки игнорируются.
     */
    fun parseDictionary(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { line ->
            val trimmed = line.removePrefix("\uFEFF").substringBefore('#').trim().trim('\uFEFF')
            if (trimmed.isEmpty()) return@forEach
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@forEach
            val word = trimmed.substring(0, separator).trim().trim('\uFEFF')
            val value = trimmed.substring(separator + 1).trim().trim('\uFEFF')
            if (word.isNotEmpty() && value.isNotEmpty()) put(word, value)
        }
    }


    // ----- Латиница -----

    private val LATIN_WORD = Regex("[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ'’]*")
    private val FOOTNOTE_RE = Regex("\\[\\d{1,3}]")

    /** Сноски, приклеенные к слову: «mariages.21», «sincipide.6». Годы (4 цифры) не трогаем. */
    private val GLUED_NUMBER_RE = Regex("(?<=[а-яёa-z][.])\\d{1,2}(?!\\d)|(?<=[а-яёa-z])\\d{1,2}(?!\\d)")

    private val LETTER_NAMES = mapOf(
        'a' to "эй", 'b' to "би", 'c' to "си", 'd' to "ди", 'e' to "и", 'f' to "эф",
        'g' to "джи", 'h' to "эйч", 'i' to "ай", 'j' to "джей", 'k' to "кей", 'l' to "эл",
        'm' to "эм", 'n' to "эн", 'o' to "оу", 'p' to "пи", 'q' to "кью", 'r' to "ар",
        's' to "эс", 't' to "ти", 'u' to "ю", 'v' to "ви", 'w' to "дабл-ю", 'x' to "экс",
        'y' to "уай", 'z' to "зет",
    )

    /** Частые термины: транслитерация «по правилам» их только испортит. */
    private val TERMS = mapOf(
        "python" to "пайтон", "java" to "джава", "javascript" to "джаваскрипт",
        "kotlin" to "котлин", "linux" to "линукс", "windows" to "виндоус",
        "android" to "андроид", "google" to "гугл", "microsoft" to "майкрософт",
        "apple" to "эппл", "github" to "гитхаб", "git" to "гит", "sql" to "сиквел",
        "mysql" to "май-эс-кью-эл", "postgres" to "постгрес", "json" to "джейсон",
        "xml" to "икс-эм-эл", "html" to "эйч-ти-эм-эл", "css" to "си-эс-эс",
        "api" to "апи", "http" to "эйч-ти-ти-пи", "https" to "эйч-ти-ти-пи-эс",
        "url" to "ю-ар-эл", "uri" to "ю-ар-ай", "usb" to "ю-эс-би",
        "cpu" to "си-пи-ю", "gpu" to "джи-пи-ю", "ram" to "рэм", "ssd" to "эс-эс-ди",
        "pdf" to "пэ-дэ-эф", "docx" to "докс", "epub" to "эпаб", "fb2" to "эф-бэ-два",
        "buffer" to "баффер", "cache" to "кэш", "memory" to "мемори", "stack" to "стек",
        "queue" to "кью", "array" to "эррей", "list" to "лист", "heap" to "хип",
        "server" to "сервер", "client" to "клиент", "byte" to "байт", "bit" to "бит",
        "big" to "биг", "data" to "дата", "file" to "файл", "code" to "код",
        "email" to "имейл", "online" to "онлайн", "offline" to "офлайн",
        "wifi" to "вай-фай", "wi" to "вай", "fi" to "фай", "ok" to "окей",
        "ui" to "ю-ай", "ux" to "ю-экс", "sdk" to "эс-дэ-кей", "ide" to "ай-ди-и",
    )

    private val DIGRAPHS = listOf(
        "shch" to "щ", "sch" to "ш", "tion" to "шн", "sion" to "жн", "igh" to "ай",
        "tch" to "ч", "dge" to "дж", "sh" to "ш", "ch" to "ч", "th" to "т",
        "ph" to "ф", "zh" to "ж", "kh" to "х", "ck" to "к", "qu" to "кв",
        "oo" to "у", "ee" to "и", "ea" to "и", "ai" to "эй", "ay" to "эй",
        "ey" to "эй", "oy" to "ой", "oi" to "ой", "ou" to "ау", "ow" to "оу",
        "au" to "ау", "aw" to "о", "ew" to "ью", "ie" to "и", "ur" to "ер",
        "ir" to "ир", "ar" to "ар", "or" to "ор", "er" to "ер", "gh" to "",
    )

    private val LETTERS = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф",
        'g' to "г", 'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л",
        'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс",
        'y' to "и", 'z' to "з",
    )

    /**
     * Переводит латинские слова в кириллицу: Silero не умеет читать латиницу
     * и просто выбрасывает её из входной последовательности.
     * Французские фразы (частые в русской классике) читаются по французским правилам.
     */
    fun transliterateLatin(text: String): String {
        val french = isFrench(text)
        return LATIN_WORD.replace(text) { match ->
            val word = normalizeLatin(word = match.value)
            val lower = word.lowercase()
            TERMS[lower]?.let { return@replace it }
            // Аббревиатуры из заглавных читаем по буквам: API → «эй-пи-ай».
            if (word.length <= 5 && word.none { it.isLowerCase() } && word.length > 1) {
                val letters = lower.mapNotNull { LETTER_NAMES[it] }.joinToString("-")
                if (letters.isNotEmpty()) return@replace letters
            }
            if (word.length == 1) {
                LETTER_NAMES[lower[0]]?.let { return@replace it }
            }
            if (french) transliterateFrench(lower) else transliterateWord(lower)
        }
    }

    private val FRENCH_MARKERS = setOf(
        "le", "la", "les", "de", "des", "du", "je", "tu", "il", "elle", "nous", "vous", "ils",
        "que", "qui", "pas", "est", "et", "un", "une", "mon", "ma", "mes", "votre", "ce", "cette",
        "dans", "pour", "avec", "sur", "au", "aux", "comme", "mais", "tout", "tous", "bien",
    )

    /** Простая эвристика: считаем французские служебные слова в латинском фрагменте. */
    private fun isFrench(text: String): Boolean {
        val words = LATIN_WORD.findAll(text).map { it.value.lowercase() }.toList()
        if (words.isEmpty()) return false
        val score = words.count { it in FRENCH_MARKERS }
        return score >= 2 && score * 4 >= words.size
    }

    private val DIACRITICS = mapOf(
        'à' to 'а', 'á' to 'а', 'â' to 'а', 'ä' to 'а',
        'è' to 'е', 'é' to 'е', 'ê' to 'е', 'ë' to 'е',
        'ì' to 'и', 'í' to 'и', 'î' to 'и', 'ï' to 'и',
        'ò' to 'о', 'ó' to 'о', 'ô' to 'о', 'ö' to 'о',
        'ù' to 'у', 'ú' to 'у', 'û' to 'у', 'ü' to 'у',
        'ç' to 'с', 'ñ' to 'н', 'ß' to 'с',
    )

    /** Снимает диакритику: символы вроде «é» иначе вырезаются словарём модели. */
    private fun normalizeLatin(word: String): String = buildString {
        for (char in word) append(DIACRITICS[char.lowercaseChar()] ?: char)
    }

    private val FRENCH_DIGRAPHS = listOf(
        "eaux" to "о", "eau" to "о", "oeu" to "ё", "ill" to "ий", "aux" to "о",
        "oi" to "уа", "ou" to "у", "ai" to "э", "ei" to "э", "au" to "о", "eu" to "ё",
        "ch" to "ш", "qu" to "к", "gn" to "нь", "ph" to "ф", "th" to "т",
        "an" to "ан", "am" to "ам", "en" to "ан", "em" to "ам", "on" to "он", "om" to "ом",
        "in" to "ен", "im" to "ем", "un" to "ен", "ez" to "е", "er" to "е", "et" to "е",
    )

    private val FRENCH_LETTERS = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф",
        'g' to "г", 'h' to "", 'i' to "и", 'j' to "ж", 'k' to "к", 'l' to "л",
        'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "ю", 'v' to "в", 'w' to "в", 'x' to "кс",
        'y' to "и", 'z' to "з",
    )

    private fun transliterateFrench(word: String): String {
        val trimmed = when {
            word.length >= 3 && word.last() == 'e' -> word.dropLast(1)
            word.length >= 4 && word.last() in setOf('s', 't', 'x', 'd') -> word.dropLast(1)
            else -> word
        }
        val builder = StringBuilder()
        var index = 0
        while (index < trimmed.length) {
            val rest = trimmed.substring(index)
            val digraph = FRENCH_DIGRAPHS.firstOrNull { rest.startsWith(it.first) }
            if (digraph != null) {
                builder.append(digraph.second)
                index += digraph.first.length
                continue
            }
            val char = trimmed[index]
            // Во французском c/g перед e/i читаются мягко.
            if ((char == 'c' || char == 'g') && index + 1 < trimmed.length &&
                trimmed[index + 1] in "ei"
            ) {
                builder.append(if (char == 'c') "с" else "ж")
                index++
                continue
            }
            builder.append(FRENCH_LETTERS[char] ?: "")
            index++
        }
        val result = builder.toString()
        if (result.isEmpty() || result.none { it in "аеёиоуыэюя" }) return transliterateWord(word)
        return result
    }

    private fun transliterateWord(word: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < word.length) {
            val rest = word.substring(index)
            val digraph = DIGRAPHS.firstOrNull { rest.startsWith(it.first) }
            if (digraph != null) {
                builder.append(digraph.second)
                index += digraph.first.length
                continue
            }
            val char = word[index]
            builder.append(LETTERS[char] ?: "")
            index++
        }
        val result = builder.toString()
        // «спл» без гласных произнести нельзя — читаем по буквам.
        if (result.length > 1 && result.none { it in "аеёиоуыэюя" }) {
            return word.mapNotNull { LETTER_NAMES[it] }.joinToString("-")
        }
        return result
    }

    fun spellNumber(value: Long): String {
        if (value == 0L) return "ноль"
        if (value < 0) return "минус " + spellNumber(-value)
        if (value >= 1_000_000_000L) return value.toString()
        val millions = value / 1_000_000L
        val thousands = (value / 1_000L) % 1_000L
        val rest = value % 1_000L
        val parts = mutableListOf<String>()
        if (millions > 0) {
            parts += triplet(millions, MALE)
            parts += plural(millions, "миллион", "миллиона", "миллионов")
        }
        if (thousands > 0) {
            if (thousands > 1) parts += triplet(thousands, FEMALE_THOUSAND)
            parts += plural(thousands, "тысяча", "тысячи", "тысяч")
        }
        if (rest > 0 || parts.isEmpty()) parts += triplet(rest, MALE)
        return parts.joinToString(" ")
    }

    private fun plural(value: Long, one: String, few: String, many: String): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private enum class Gender { MALE, FEMALE }

    private val MALE = Gender.MALE
    private val FEMALE_THOUSAND = Gender.FEMALE
    private val FEMALE_MILLION = Gender.FEMALE

    private val ONES = arrayOf(
        "", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val FEMALE_ONES = arrayOf(
        "", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val TENS = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )
    private val HUNDREDS = arrayOf(
        "", "сто", "двести", "триста", "четыреста", "пятьсот",
        "шестьсот", "семьсот", "восемьсот", "девятьсот",
    )

    private fun triplet(value: Long, gender: Gender): String {
        val parts = mutableListOf<String>()
        val hundreds = (value / 100).toInt()
        val remainder = (value % 100).toInt()
        if (hundreds > 0) parts += HUNDREDS[hundreds]
        if (remainder in 1..19) {
            parts += if (gender == Gender.FEMALE) FEMALE_ONES[remainder] else ONES[remainder]
        } else {
            val tens = remainder / 10
            val ones = remainder % 10
            if (tens > 0) parts += TENS[tens]
            if (ones > 0) parts += if (gender == Gender.FEMALE) FEMALE_ONES[ones] else ONES[ones]
        }
        return parts.joinToString(" ")
    }

    /** Оставляет только символы словаря модели: цифры и латиница не поддерживаются. */
    fun prepare(text: String, symbols: String): String {
        var value = text.lowercase()
            .replace('—', '–')
            .replace('‑', '-')
        val allowed = buildSet {
            for (char in symbols) add(char)
            add('^')
        }
        value = value.filter { it in allowed }
        value = value.replace('^', ' ')
        return value.replace(Regex("\\s+"), " ").trim()
    }

    // ----- Классификация предложений -----

    fun classifyText(text: String): String {
        if (text.isBlank()) return "st"
        return SENT_SPLIT_RE.split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("|") { classifySentence(it) }
    }

    private fun classifySentence(raw: String): String {
        val sentence = raw.trim()
        if (sentence.isEmpty()) return "st"
        val clean = stripOuterQuotes(sentence)
        if (clean.isEmpty()) return "st"
        var tail = clean
        if (tail.endsWith("?!") || tail.endsWith("?..") || tail.endsWith("?...")) {
            tail = tail.trimEnd('.', '!')
        }
        if (tail.endsWith("?")) {
            val normalized = normalizeForClassifier(tail)
            if (TAG_RE.containsMatchIn(normalized)) return "tag_q"
            if (hasWhPrefix(normalized)) return "wh_q"
            if (Regex("(?<![а-яё])или(?![а-яё])", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
                return "alternative_q"
            }
            return "general_q"
        }
        if (clean.endsWith("!")) return "exclam"
        return "st"
    }

    private fun hasWhPrefix(text: String): Boolean {
        val words = WORD_RE.findAll(text.lowercase()).map { it.value }.take(8).toList()
        val content = mutableListOf<String>()
        for (word in words) {
            if (word in LEADING_FILLERS) continue
            content += word
            if (content.size >= 4) break
        }
        return content.any { it in WH_FORMS }
    }

    private fun normalizeForClassifier(text: String): String =
        stripOuterQuotes(text)
            .replace("+", "")
            .replace('ё', 'е')
            .replace('Ё', 'Е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripOuterQuotes(raw: String): String {
        var text = raw.trim()
        while (text.isNotEmpty() && text[0] in QUOTE_OPEN) text = text.substring(1).trim()
        while (text.isNotEmpty() && text[text.length - 1] in QUOTE_CLOSE) {
            text = text.substring(0, text.length - 1).trim()
        }
        return text
    }

    // ----- Ударения -----

    /**
     * Ставит ударения и восстанавливает «ё».
     * [lookupStress] — точное ударение из словаря (индекс символа) или -1.
     * [predict] возвращает для каждого слова вероятности ударной гласной и «ё».
     */
    fun accent(
        text: String,
        lookupStress: ((String) -> Int)? = null,
        predict: (List<String>) -> List<WordPrediction>,
    ): String {
        val tokens = splitTokens(text)
        val words = tokens.map { token ->
            token.split('-').map { part -> LATIN_OR_DIGIT.replace(part.lowercase(), "") }
        }
        val inputs = words.flatten().filter { it.isNotEmpty() }
        val predictions = if (inputs.isEmpty()) emptyList() else predict(inputs)
        var index = 0
        val builder = StringBuilder()
        tokens.forEachIndexed { tokenIndex, token ->
            val parts = token.split('-')
            val accentedParts = parts.mapIndexed { partIndex, part ->
                val input = words[tokenIndex][partIndex]
                if (input.isEmpty()) {
                    part
                } else {
                    val prediction = predictions.getOrNull(index)
                    index++
                    if (prediction == null) {
                        part
                    } else {
                        // Маркеры эмфазы (**…**) не должны сбивать позиции гласных.
                        val prefix = part.takeWhile { it == '*' }
                        val suffix = part.takeLastWhile { it == '*' }
                        val core = part.substring(prefix.length, part.length - suffix.length)
                        if (core.isEmpty()) part else {
                            val stressed = applyPrediction(core, prediction, lookupStress?.invoke(input) ?: -1)
                            prefix + stressed + suffix
                        }
                    }
                }
            }
            builder.append(accentedParts.joinToString("-"))
        }
        return builder.toString()
    }

    data class WordPrediction(val stressVowel: Int, val stressProbability: Float, val yoVowel: Int, val yoProbability: Float)

    private fun splitTokens(sentence: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = 0
        for (match in TOKEN_SPLIT_RE.findAll(sentence)) {
            if (match.range.first > start) tokens += sentence.substring(start, match.range.first)
            tokens += match.value
            start = match.range.last + 1
        }
        if (start < sentence.length) tokens += sentence.substring(start)
        return tokens.filter { it.isNotEmpty() }
    }

    private fun applyPrediction(rawWord: String, prediction: WordPrediction, dictionaryIndex: Int): String {
        val lower = rawWord.lowercase()
        if ('+' in lower) return rawWord
        val vowelPositions = lower.indices.filter { lower[it] in VOWELS }
        if (vowelPositions.isEmpty()) return rawWord

        if ('ё' in lower) {
            // Уже размеченное «ё»: ударение ставим перед каждой «ё».
            val builder = StringBuilder()
            for (char in rawWord) {
                if (char.lowercaseChar() == 'ё') builder.append('+')
                builder.append(char)
            }
            return builder.toString()
        }

        var stressed = -1
        // Словарь ударений точнее нейросети: если слово найдено, берём его вариант.
        if (dictionaryIndex in lower.indices) {
            stressed = dictionaryIndex
        } else if (prediction.stressProbability > 0.5f && prediction.stressVowel in vowelPositions.indices) {
            stressed = vowelPositions[prediction.stressVowel]
        }
        var yoPosition = -1
        if (prediction.yoProbability > 0.5f && prediction.yoVowel > 0) {
            val yePositions = lower.indices.filter { lower[it] == 'е' }
            val candidate = prediction.yoVowel - 1
            if (candidate in yePositions.indices) yoPosition = yePositions[candidate]
        }
        if (vowelPositions.size == 1) stressed = vowelPositions.first()

        val builder = StringBuilder()
        rawWord.indices.forEach { i ->
            if (i == stressed) builder.append('+')
            val char = rawWord[i]
            if (i == yoPosition && stressed == yoPosition) {
                builder.append(if (char.isLowerCase()) 'ё' else 'Ё')
            } else {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    /** Разбивает текст на куски по предложениям, чтобы первый звук появился быстрее. */
    fun chunks(text: String, maxChars: Int): List<String> =
        chunkPlan(text, maxChars).map { it.text }

    /** Кусок синтеза и пауза после него (для многоточий, тире и двоеточий). */
    data class Chunk(val text: String, val pauseMs: Int)

    fun chunkPlan(text: String, maxChars: Int): List<Chunk> {
        val segments = splitBySsml(text)
        val result = mutableListOf<Chunk>()
        for (segment in segments) {
            // В стихах перенос строки — естественная пауза между строками.
            val lines = if (segment.text.contains('\n')) {
                segment.text.split('\n').filter { it.isNotBlank() }
            } else {
                listOf(segment.text)
            }
            for ((lineIndex, line) in lines.withIndex()) {
                val chunks = chunkPlain(line, maxChars)
                chunks.forEachIndexed { index, chunk ->
                    val pause = when {
                        index < chunks.lastIndex -> 0
                        lineIndex < lines.lastIndex -> maxOf(LINE_PAUSE_MS, pauseAfter(chunk))
                        else -> maxOf(pauseAfter(chunk), segment.pauseMs)
                    }
                    result += Chunk(chunk, pause)
                }
            }
        }
        return result
    }

    /** Кусок текста между SSML-паузами и пауза после него. */
    private data class Segment(val text: String, val pauseMs: Int)

    private fun splitBySsml(text: String): List<Segment> {
        // <p>/<s> по документации Silero эквивалентны сильным паузам.
        val normalized = text
            .replace(Regex("(?i)</p\\s*>"), "<break strength=\"x-strong\"/>")
            .replace(Regex("(?i)</s\\s*>"), "<break strength=\"strong\"/>")
        if (!normalized.contains("<break", ignoreCase = true)) {
            return listOf(Segment(ANY_TAG.replace(normalized, " "), 0))
        }
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in BREAK_TAG.findAll(normalized)) {
            val part = ANY_TAG.replace(normalized.substring(cursor, match.range.first), " ").trim()
            if (part.isNotEmpty()) segments += Segment(part, 0)
            val pause = ssmlPauseMs(match.value)
            if (segments.isEmpty()) {
                segments += Segment("", pause)
            } else {
                segments[segments.lastIndex] = segments.last().copy(pauseMs = maxOf(segments.last().pauseMs, pause))
            }
            cursor = match.range.last + 1
        }
        val tail = ANY_TAG.replace(normalized.substring(cursor), " ").trim()
        if (tail.isNotEmpty()) segments += Segment(tail, 0)
        return segments
    }

    /** Длительность паузы из `<break time="2s"/>` или `strength="strong"`. */
    private fun ssmlPauseMs(tag: String): Int {
        TIME_ATTR.find(tag)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let
            val unit = match.groupValues[2].lowercase()
            val millis = if (unit == "s") value * 1000 else value
            return millis.toInt().coerceIn(0, 5000)
        }
        val strength = STRENGTH_ATTR.find(tag)?.groupValues?.get(1)?.lowercase()
        return STRENGTH_MS[strength] ?: 150
    }

    private fun chunkPlain(text: String, maxChars: Int): List<String> {
        val sentences = SENT_SPLIT_RE.split(text.trim()).filter { it.isNotBlank() }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isEmpty()) {
                current.append(sentence.trim())
            } else if (current.length + 1 + sentence.length <= maxChars) {
                current.append(' ').append(sentence.trim())
            } else {
                parts += current.toString()
                current.setLength(0)
                current.append(sentence.trim())
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts.flatMap { chunk ->
            if (chunk.length <= maxChars * 2) {
                listOf(chunk)
            } else {
                chunk.split(Regex("\\s+")).fold(mutableListOf<String>()) { acc, word ->
                    if (acc.isEmpty() || acc.last().length + 1 + word.length > maxChars) acc += word
                    else acc[acc.lastIndex] = acc.last() + " " + word
                    acc
                }
            }
        }
    }

    /** Пауза после куска: многоточие и тире заметно длиннее, точка уже учтена моделью. */
    private fun pauseAfter(text: String): Int {
        val trimmed = text.trimEnd()
        return when {
            trimmed.endsWith("…") || trimmed.endsWith("...") -> 450
            trimmed.endsWith("—") || trimmed.endsWith("–") -> 250
            trimmed.endsWith(":") -> 150
            else -> 0
        }
    }

    // ----- Эмфаза и логическое ударение -----

    /** Разметка: `**слово**` — эмфаза, `*слово*` — логическое ударение, КАПС — эмфаза. */
    data class Markup(
        val text: String,
        val focus: BooleanArray,
        val emphasisSentences: Set<Int>,
    )

    fun parseMarkup(source: String): Markup {
        val emphasisPositions = mutableListOf<Int>()
        var text = source
        var focus = BooleanArray(text.length)
        // Сначала **жирный** (эмфаза), затем *курсив* (логическое ударение).
        val bold = replacePairs(text, focus, BOLD_RE, emphasis = true, emphasisPositions)
        text = bold.first
        focus = bold.second
        val italic = replacePairs(text, focus, ITALIC_RE, emphasis = false, emphasisPositions)
        text = italic.first
        focus = italic.second

        // КАПС (не короче трёх букв) читаем с нажимом, как это делают книги.
        for (match in CAPS_RE.findAll(text)) {
            for (index in match.range) if (index < focus.size) focus[index] = true
            emphasisPositions += match.range.first
        }

        val emphasisSentences = emphasisSentenceIndexes(text, emphasisPositions)
        return Markup(text, focus, emphasisSentences)
    }

    /** Убирает парные маркеры, помечая их содержимое фокусом (и эмфазой для `**`). */
    private fun replacePairs(
        text: String,
        focus: BooleanArray,
        regex: Regex,
        emphasis: Boolean,
        emphasisPositions: MutableList<Int>,
    ): Pair<String, BooleanArray> {
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text to focus
        val out = StringBuilder(text.length)
        val outFocus = ArrayList<Boolean>(text.length)
        var cursor = 0
        for (match in matches) {
            if (match.range.first < cursor) continue
            while (cursor < match.range.first) {
                out.append(text[cursor])
                outFocus += focus.getOrElse(cursor) { false }
                cursor++
            }
            val contentStart = out.length
            for (char in match.groupValues[1]) {
                out.append(char)
                outFocus += true
            }
            if (emphasis && out.length > contentStart) emphasisPositions += contentStart
            cursor = match.range.last + 1
        }
        while (cursor < text.length) {
            out.append(text[cursor])
            outFocus += focus.getOrElse(cursor) { false }
            cursor++
        }
        return out.toString() to BooleanArray(outFocus.size) { outFocus[it] }
    }

    private fun emphasisSentenceIndexes(text: String, positions: List<Int>): Set<Int> {
        if (positions.isEmpty()) return emptySet()
        val bounds = mutableListOf<Int>()
        for (match in SENT_SPLIT_RE.findAll(text)) bounds += match.range.first
        bounds += text.length
        val result = mutableSetOf<Int>()
        for (position in positions) {
            var index = 0
            while (index < bounds.size - 1 && position >= bounds[index]) index++
            result += index
        }
        return result
    }

    // ----- Токены и типы -----

    data class Sequence(val ids: LongArray, val types: LongArray, val focus: LongArray)

    /**
     * Строит последовательность символов, типы предложений и маску эмфазы так же,
     * как `preprocess_tacotron` + `build_type_ids_tokens` + `parse_focus_from_text`.
     */
    fun buildSequence(
        accentedText: String,
        sos: String,
        eos: String,
        symbols: String,
        symbolToId: Map<Char, Int>,
        typeIds: Map<String, Int>,
    ): Sequence {
        val markup = parseMarkup(accentedText.replace('\n', ' '))
        val allowed = buildSet {
            for (char in symbols) add(char)
            add('^')
        }
        val prepared = StringBuilder()
        val preparedFocus = ArrayList<Boolean>()
        for (index in markup.text.indices) {
            var char = markup.text[index].lowercaseChar()
            if (char == '—') char = '–'
            if (char == '‑') char = '-'
            if (char !in allowed || char == '^') continue
            if (char == ' ' && (prepared.isEmpty() || prepared.last() == ' ')) continue
            prepared.append(char)
            preparedFocus += markup.focus.getOrElse(index) { false }
        }
        while (prepared.isNotEmpty() && prepared.last() == ' ') {
            prepared.setLength(prepared.length - 1)
            preparedFocus.removeAt(preparedFocus.size - 1)
        }
        val body = prepared.toString()
        val chars = sos + body + eos
        val ids = LongArray(chars.length) { symbolToId[chars[it]]?.toLong() ?: 0L }

        val types = classifyText(markup.text.replace("+", "")).split("|")
        val spans = sentenceSpans(chars, sos.length)
        val resolvedTypes = if (types.size == spans.size) types else {
            spans.map { (from, to) -> classifySentence(chars.substring(from, to)) }
        }
        val typeArray = LongArray(ids.size)
        val focusArray = LongArray(ids.size)
        val offset = if (sos.isEmpty()) 0 else 1
        val perChar = LongArray(ids.size - 2)
        for ((sentenceIndex, span) in spans.withIndex()) {
            var type = (typeIds[resolvedTypes.getOrNull(sentenceIndex) ?: "st"] ?: 0).toLong()
            if (sentenceIndex in markup.emphasisSentences) type += EMPHASIS_TYPE_SHIFT
            for (i in (span.first - offset) until (span.second - offset)) {
                if (i in perChar.indices) perChar[i] = type
            }
        }
        for (i in perChar.indices) typeArray[i + offset] = perChar[i]
        if (perChar.isNotEmpty()) {
            typeArray[0] = perChar[0]
            typeArray[typeArray.size - 1] = perChar.last()
        }
        for (i in body.indices) {
            if (!preparedFocus.getOrElse(i) { false }) continue
            if (body[i] in FOCUS_SKIP_CHARS) continue
            focusArray[i + offset] = FOCUS_INTENSITY.toLong()
        }
        if (focusArray.isNotEmpty() && focusArray.size > 1) focusArray[0] = focusArray[1]
        return Sequence(ids, typeArray, focusArray)
    }

    /** Границы предложений в строке символов: разделитель относится к предыдущему предложению. */
    private fun sentenceSpans(chars: String, offset: Int): List<Pair<Int, Int>> {
        val body = if (chars.length > offset * 2) chars.substring(offset, chars.length - offset) else ""
        if (body.isEmpty()) return listOf(offset to offset + body.length)
        val sentenceIds = IntArray(body.length)
        var position = 0
        var sentenceIndex = 0
        for (match in SENT_SPLIT_RE.findAll(body)) {
            while (position < match.range.last + 1) {
                sentenceIds[position] = sentenceIndex
                position++
            }
            sentenceIndex++
        }
        while (position < body.length) {
            sentenceIds[position] = sentenceIndex
            position++
        }
        val spans = mutableListOf<Pair<Int, Int>>()
        for (index in 0..sentenceIndex) {
            var from = -1
            var to = -1
            for (i in sentenceIds.indices) {
                if (sentenceIds[i] == index) {
                    if (from < 0) from = i
                    to = i
                }
            }
            if (from < 0) continue
            while (to > from && body[to] == ' ') to--
            spans += (from + offset) to (to + offset + 1)
        }
        return spans
    }
}
