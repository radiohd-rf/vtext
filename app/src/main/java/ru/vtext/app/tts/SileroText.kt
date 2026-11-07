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

    // ----- Нормализация текста -----

    /** Числа записываем словами: Silero выбрасывает все цифры из входа. */
    fun spellNumbers(text: String): String = Regex("\\d+").replace(text) { match ->
        spellNumber(match.value.toLongOrNull() ?: 0L)
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
     * [predict] возвращает для каждого слова вероятности ударной гласной и «ё».
     */
    fun accent(text: String, predict: (List<String>) -> List<WordPrediction>): String {
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
                    if (prediction == null) part else applyPrediction(part, prediction)
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

    private fun applyPrediction(rawWord: String, prediction: WordPrediction): String {
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
        if (prediction.stressProbability > 0.5f && prediction.stressVowel in vowelPositions.indices) {
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
    fun chunks(text: String, maxChars: Int): List<String> {
        val sentences = SENT_SPLIT_RE.split(text.trim()).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isEmpty()) {
                current.append(sentence.trim())
            } else if (current.length + 1 + sentence.length <= maxChars) {
                current.append(' ').append(sentence.trim())
            } else {
                result += current.toString()
                current.setLength(0)
                current.append(sentence.trim())
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.flatMap { chunk ->
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

    // ----- Токены и типы -----

    data class Sequence(val ids: LongArray, val types: LongArray)

    /**
     * Строит последовательность символов и типы предложений так же, как это делает
     * `preprocess_tacotron` + `build_type_ids_tokens` в оригинальном пакете.
     */
    fun buildSequence(
        accentedText: String,
        sos: String,
        eos: String,
        symbols: String,
        symbolToId: Map<Char, Int>,
        typeIds: Map<String, Int>,
    ): Sequence {
        val prepared = prepare(accentedText.replace('\n', ' '), symbols)
        val chars = sos + prepared + eos
        val ids = LongArray(chars.length) { symbolToId[chars[it]]?.toLong() ?: 0L }

        val types = classifyText(accentedText.replace("+", "")).split("|")
        val spans = sentenceSpans(chars, sos.length)
        val resolvedTypes = if (types.size == spans.size) types else {
            spans.map { (from, to) -> classifySentence(chars.substring(from, to)) }
        }
        val typeArray = LongArray(ids.size)
        val offset = if (sos.isEmpty()) 0 else 1
        val perChar = LongArray(ids.size - 2)
        for ((sentenceIndex, span) in spans.withIndex()) {
            val type = (typeIds[resolvedTypes.getOrNull(sentenceIndex) ?: "st"] ?: 0).toLong()
            for (i in (span.first - offset) until (span.second - offset)) {
                if (i in perChar.indices) perChar[i] = type
            }
        }
        for (i in perChar.indices) typeArray[i + offset] = perChar[i]
        if (perChar.isNotEmpty()) {
            typeArray[0] = perChar[0]
            typeArray[typeArray.size - 1] = perChar.last()
        }
        return Sequence(ids, typeArray)
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
