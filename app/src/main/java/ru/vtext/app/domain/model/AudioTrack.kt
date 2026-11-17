package ru.vtext.app.domain.model

/** Одна дорожка аудиокниги: файл и время начала внутри него. */
data class AudioTrack(
    val title: String,
    val path: String,
    val durationMs: Long,
    val startMs: Long = 0,
    /** true — path это путь внутри публичной папки Яндекс.Диска, а не файл на устройстве. */
    val remote: Boolean = false,
)
