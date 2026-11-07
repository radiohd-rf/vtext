package ru.vtext.app.tts

import android.util.Log
import java.io.File
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor

/**
 * Разрешение омографов Silero по контексту.
 *
 * Сначала проверяются фразовые правила («старинный замок» → `з+амок`), затем
 * BERT-подобная модель: вокруг слова собирается окно ±150 символов с маркерами
 * `[HOMO] … [/HOMO]`, и она выбирает вариант ударения из словаря омографов.
 */
internal class SileroHomosolver(
    private val module: Module,
    private val tokenizer: SileroBertTokenizer,
    private val homodict: Map<String, List<String>>,
    private val phrases: Map<String, PhraseRule>,
) {

    private val compiledPatterns = HashMap<String, Regex?>()

    /** Возвращает текст, в котором омографы уже размечены знаком «+». */
    fun solve(text: String): String {
        val matches = WORD_RE.findAll(text).toList()
        val resolved = mutableListOf<Resolved>()
        val neural = mutableListOf<Homograph>()

        for (match in matches) {
            val word = match.value
            if ('+' in word) continue
            val lower = word.lowercase()
            val hasPhrase = phrases.containsKey(lower)
            val variants = homodict[lower]
            if (!hasPhrase && (variants == null || variants.size < 2)) continue

            val marked = buildMarked(text, match)
            val phraseVariant = phrases[lower]?.let { matchPhrase(lower, it, marked) }
            if (phraseVariant != null) {
                resolved += Resolved(match.range.first, match.range.last + 1, word, phraseVariant)
                continue
            }
            if (variants == null || variants.size < 2) continue

            val ids = tokenizer.encode(marked)
            val (start, end) = tokenizer.homoPositions(ids)
            if (start < 0 || end < start) continue
            neural += Homograph(
                start = match.range.first,
                end = match.range.last + 1,
                word = word,
                variants = variants,
                ids = ids,
                homoStart = start,
                homoEnd = end,
            )
        }

        if (neural.isNotEmpty()) {
            val indexes = predict(neural)
            neural.forEachIndexed { position, target ->
                val variant = target.variants.sorted()[indexes[position]]
                resolved += Resolved(target.start, target.end, target.word, variant)
            }
        }
        if (resolved.isEmpty()) return text

        resolved.sortBy { it.start }
        val builder = StringBuilder(text)
        var offset = 0
        for (item in resolved) {
            val applied = applyVariantToWord(item.word, item.variant)
            builder.replace(item.start + offset, item.end + offset, applied)
            offset += applied.length - (item.end - item.start)
        }
        return builder.toString()
    }

    private fun buildMarked(text: String, match: MatchResult): String {
        val before = cleanText(text.substring(0, match.range.first), atStart = true).takeLast(WINDOW)
        val after = cleanText(text.substring(match.range.last + 1), atStart = false).take(WINDOW)
        return "$before [HOMO] ${match.value.lowercase()} [/HOMO] $after".trim()
    }

    private fun matchPhrase(word: String, rule: PhraseRule, marked: String): String? {
        val pattern = compiledPatterns.getOrPut(word) {
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        } ?: return null
        val match = pattern.find(marked) ?: return null
        for (index in rule.variants.indices) {
            val groupIndex = index + 1
            if (groupIndex >= match.groups.size) continue
            val group = match.groups[groupIndex] ?: continue
            if (group.value != null) return rule.variants[index]
        }
        return null
    }

    private fun predict(targets: List<Homograph>): List<Int> {
        val batch = targets.size
        val maxLength = targets.maxOf { it.ids.size }
        val pad = tokenizer.padId()
        val ids = LongArray(batch * maxLength) { pad }
        val starts = LongArray(batch)
        val ends = LongArray(batch)
        targets.forEachIndexed { row, target ->
            target.ids.copyInto(ids, row * maxLength)
            starts[row] = target.homoStart.toLong()
            ends[row] = target.homoEnd.toLong()
        }
        val idTensor = Tensor.fromBlob(ids, longArrayOf(batch.toLong(), maxLength.toLong()))
        val startTensor = Tensor.fromBlob(starts, longArrayOf(batch.toLong()))
        val endTensor = Tensor.fromBlob(ends, longArrayOf(batch.toLong()))
        val output = module.forward(
            IValue.from(idTensor),
            IValue.from(startTensor),
            IValue.from(endTensor),
        ).toTensor()
        val data = output.dataAsFloatArray
        return List(batch) { row ->
            // sigmoid + округление: 0 — первый вариант, 1 — второй.
            val probability = 1.0 / (1.0 + Math.exp(-data[row].toDouble()))
            if (probability >= 0.5) 1 else 0
        }
    }

    private fun cleanText(text: String, atStart: Boolean): String {
        var value = text.replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s.!?,\\-]"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("-{2,}"), " - ")
            .replace(Regex("([.!?])\\1+"), "$1")
            .replace(Regex(",{2,}"), ",")
            .replace(Regex("\\s+([.,!?])"), "$1")
            .replace(Regex("([.,!?])(?=\\S)"), "$1 ")
            .replace(Regex("\\s+"), " ")
            .trim()
        value = if (atStart) {
            value.trimStart(' ', '.', ',', '!', '?', '-')
            if (value.isNotEmpty()) value[0].uppercase() + value.substring(1).lowercase() else value
        } else {
            var result = if (value.isNotEmpty()) value[0] + value.substring(1).lowercase() else value
            if (result.isNotEmpty() && result.last() !in ".!?") result += "."
            result
        }
        return value
    }

    private data class Homograph(
        val start: Int,
        val end: Int,
        val word: String,
        val variants: List<String>,
        val ids: LongArray,
        val homoStart: Int,
        val homoEnd: Int,
    )

    private data class Resolved(val start: Int, val end: Int, val word: String, val variant: String)

    data class PhraseRule(val variants: List<String>, val pattern: String)

    companion object {
        private const val TAG = "vtext-tts"
        private const val WINDOW = 150
        private val WORD_RE = Regex("(?=.*[а-яё])[а-яё+]+", RegexOption.IGNORE_CASE)

        /** Переносит ударение из варианта на исходное слово, сохраняя регистр. */
        internal fun applyVariantToWord(word: String, variant: String): String {
            val stressIndex = variant.indexOf('+')
            val clean = variant.replace("+", "")
            val merged = buildString {
                for (index in word.indices) {
                    val original = word[index]
                    val replacement = clean.getOrNull(index) ?: break
                    append(if (original.isLowerCase()) replacement.lowercaseChar() else replacement.uppercaseChar())
                }
            }
            if (stressIndex < 0 || stressIndex > merged.length) return merged
            return merged.substring(0, stressIndex) + "+" + merged.substring(stressIndex)
        }

        fun parseHomodict(file: File): Map<String, List<String>> {
            val json = JSONObject(file.readText())
            val result = HashMap<String, List<String>>(json.length() * 2)
            for (key in json.keys()) {
                val array = json.optJSONArray(key) ?: continue
                val variants = ArrayList<String>(array.length())
                for (index in 0 until array.length()) variants += array.optString(index)
                if (variants.isNotEmpty()) result[key] = variants
            }
            return result
        }

        fun parsePhrases(file: File): Map<String, PhraseRule> {
            val json = JSONObject(file.readText())
            val result = HashMap<String, PhraseRule>(json.length() * 2)
            for (key in json.keys()) {
                val item = json.optJSONObject(key) ?: continue
                val array = item.optJSONArray("variants") ?: continue
                val variants = ArrayList<String>(array.length())
                for (index in 0 until array.length()) variants += array.optString(index)
                val pattern = item.optString("pattern")
                if (variants.isNotEmpty() && pattern.isNotEmpty()) {
                    result[key] = PhraseRule(variants, pattern)
                }
            }
            return result
        }
    }
}
