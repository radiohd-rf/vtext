package ru.vtext.app.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Что сохранил пользователь: просто цитату или заметку к выделенному тексту. */
enum class AnnotationKind {
    QUOTE,
    NOTE;

    companion object {
        fun fromId(id: String): AnnotationKind =
            entries.firstOrNull { it.name == id } ?: QUOTE
    }
}

/**
 * Цитата или заметка. Позиция задана парой «фрагмент + смещение» в тексте
 * главы: так подсветка находится и после пересборки страниц.
 */
data class Annotation(
    val id: String,
    val kind: AnnotationKind,
    val chapterIndex: Int,
    val startSegment: Int,
    val startOffset: Int,
    val endSegment: Int,
    val endOffset: Int,
    val text: String,
    val note: String = "",
    val createdAt: Long,
)

/** Цитаты и заметки по книгам. */
class AnnotationStore(context: Context) {

    private val prefs = context.getSharedPreferences("annotations", Context.MODE_PRIVATE)

    fun list(bookId: String): List<Annotation> {
        val raw = prefs.getString(bookId, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Annotation(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    kind = AnnotationKind.fromId(item.optString("kind", "QUOTE")),
                    chapterIndex = item.optInt("chapter", 0),
                    startSegment = item.optInt("startSegment", 0),
                    startOffset = item.optInt("startOffset", 0),
                    endSegment = item.optInt("endSegment", 0),
                    endOffset = item.optInt("endOffset", 0),
                    text = item.optString("text", ""),
                    note = item.optString("note", ""),
                    createdAt = item.optLong("createdAt", 0L),
                )
            }.sortedWith(compareBy({ it.chapterIndex }, { it.startSegment }, { it.startOffset }))
        }.getOrDefault(emptyList())
    }

    fun add(bookId: String, annotation: Annotation): List<Annotation> {
        val updated = (list(bookId) + annotation).sortedWith(
            compareBy({ it.chapterIndex }, { it.startSegment }, { it.startOffset }),
        )
        save(bookId, updated)
        return updated
    }

    fun updateNote(bookId: String, id: String, note: String): List<Annotation> {
        val updated = list(bookId).map { annotation ->
            if (annotation.id == id) annotation.copy(note = note) else annotation
        }
        save(bookId, updated)
        return updated
    }

    fun remove(bookId: String, id: String): List<Annotation> {
        val updated = list(bookId).filterNot { it.id == id }
        save(bookId, updated)
        return updated
    }

    fun clear(bookId: String) {
        prefs.edit().remove(bookId).apply()
    }

    private fun save(bookId: String, annotations: List<Annotation>) {
        val array = JSONArray()
        annotations.forEach { annotation ->
            array.put(
                JSONObject().apply {
                    put("id", annotation.id)
                    put("kind", annotation.kind.name)
                    put("chapter", annotation.chapterIndex)
                    put("startSegment", annotation.startSegment)
                    put("startOffset", annotation.startOffset)
                    put("endSegment", annotation.endSegment)
                    put("endOffset", annotation.endOffset)
                    put("text", annotation.text)
                    put("note", annotation.note)
                    put("createdAt", annotation.createdAt)
                },
            )
        }
        prefs.edit().putString(bookId, array.toString()).apply()
    }
}
