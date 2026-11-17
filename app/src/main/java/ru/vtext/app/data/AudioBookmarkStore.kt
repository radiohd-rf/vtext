package ru.vtext.app.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Закладка в аудиокниге: глава + время. */
data class AudioBookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val label: String,
    val createdAt: Long,
)

class AudioBookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("audio_bookmarks", Context.MODE_PRIVATE)

    fun bookmarks(bookId: String): List<AudioBookmark> {
        val raw = prefs.getString(key(bookId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AudioBookmark(
                    id = item.optString("id"),
                    bookId = bookId,
                    chapterIndex = item.optInt("chapterIndex"),
                    positionMs = item.optLong("positionMs"),
                    label = item.optString("label"),
                    createdAt = item.optLong("createdAt"),
                )
            }.sortedBy { it.positionMs }
        }.getOrDefault(emptyList())
    }

    fun add(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        label: String,
    ): AudioBookmark {
        val bookmark = AudioBookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            label = label.trim().ifBlank { formatTime(positionMs) },
            createdAt = System.currentTimeMillis(),
        )
        save(bookId, bookmarks(bookId) + bookmark)
        return bookmark
    }

    fun remove(bookId: String, bookmarkId: String) {
        save(bookId, bookmarks(bookId).filterNot { it.id == bookmarkId })
    }

    private fun save(bookId: String, items: List<AudioBookmark>) {
        val array = JSONArray()
        items.forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("id", bookmark.id)
                    .put("chapterIndex", bookmark.chapterIndex)
                    .put("positionMs", bookmark.positionMs)
                    .put("label", bookmark.label)
                    .put("createdAt", bookmark.createdAt),
            )
        }
        prefs.edit().putString(key(bookId), array.toString()).apply()
    }

    private fun key(bookId: String) = "marks_$bookId"

    companion object {
        fun formatTime(milliseconds: Long): String {
            val total = (milliseconds / 1000).coerceAtLeast(0)
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
