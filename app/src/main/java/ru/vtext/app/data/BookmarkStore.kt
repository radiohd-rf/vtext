package ru.vtext.app.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Закладка: позиция во фрагменте книги. */
data class Bookmark(
    val id: String,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val label: String,
    val createdAt: Long,
)

/** Закладки пользователя по книгам. */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun list(bookId: String): List<Bookmark> {
        val raw = prefs.getString(bookId, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Bookmark(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    chapterIndex = item.optInt("chapter", 0),
                    segmentIndex = item.optInt("segment", 0),
                    label = item.optString("label", ""),
                    createdAt = item.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(bookId: String, bookmark: Bookmark): List<Bookmark> {
        val updated = (list(bookId) + bookmark).sortedWith(
            compareBy({ it.chapterIndex }, { it.segmentIndex }),
        )
        save(bookId, updated)
        return updated
    }

    fun remove(bookId: String, id: String): List<Bookmark> {
        val updated = list(bookId).filterNot { it.id == id }
        save(bookId, updated)
        return updated
    }

    fun deleteAll(bookId: String) {
        prefs.edit().remove(bookId).apply()
    }

    private fun save(bookId: String, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(
                JSONObject().apply {
                    put("id", bookmark.id)
                    put("chapter", bookmark.chapterIndex)
                    put("segment", bookmark.segmentIndex)
                    put("label", bookmark.label)
                    put("createdAt", bookmark.createdAt)
                },
            )
        }
        prefs.edit().putString(bookId, array.toString()).apply()
    }
}
