package ru.vtext.app.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.vtext.app.domain.model.AudioTrack
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookKind

/** Подключённая публичная папка Яндекс.Диска с книгами. */
data class CloudConnection(val publicKey: String, val name: String)

/** Состояние синхронизации облачной библиотеки для экрана настроек. */
data class CloudSyncState(
    val syncing: Boolean = false,
    val current: String? = null,
    val added: Int = 0,
    val updated: Int = 0,
    val missing: Int = 0,
    val error: String? = null,
    val lastSyncAt: Long = 0L,
)

private sealed interface CloudBook {
    val path: String
    val title: String
}

private data class CloudAudioBook(
    override val path: String,
    override val title: String,
    val files: List<YandexEntry>,
) : CloudBook

private data class CloudTextBook(
    override val path: String,
    override val title: String,
    val name: String,
    val sizeBytes: Long,
    val modified: String?,
) : CloudBook

/**
 * Облачная библиотека: публичная папка Яндекс.Диска, внутри которой лежат
 * подпапки с аудиокнигами и текстовые файлы. При синхронизации новые книги
 * добавляются в обычную библиотеку, изменённые обновляются, а пропавшие
 * помечаются «нет в облаке». Прогресс и закладки сохраняются по id книги.
 */
class CloudLibraryRepository(
    private val context: Context,
    private val books: BookRepository,
    private val audio: AudioRepository,
    private val yandex: YandexDiskClient,
) {

    private val prefs = context.getSharedPreferences("cloud_library", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _connection = MutableStateFlow(loadConnection())
    val connection: StateFlow<CloudConnection?> = _connection.asStateFlow()

    private val _syncState = MutableStateFlow(CloudSyncState())
    val syncState: StateFlow<CloudSyncState> = _syncState.asStateFlow()

    /** Подключить папку по публичной ссылке. */
    suspend fun connect(link: String): Result<CloudConnection> = withContext(Dispatchers.IO) {
        val publicKey = YandexDiskLinks.normalize(link)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Нужна публичная ссылка вида https://disk.yandex.ru/d/…"),
            )
        runCatching {
            val listing = yandex.listFolder(publicKey)
            val connection = CloudConnection(publicKey, listing.name)
            prefs.edit()
                .putString(KEY_PUBLIC, connection.publicKey)
                .putString(KEY_NAME, connection.name)
                .apply()
            _connection.value = connection
            connection
        }
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        _connection.value = null
        _syncState.value = CloudSyncState()
    }

    /** Синхронизация при запуске: молча, если папка не подключена. */
    suspend fun syncIfConnected() {
        if (_connection.value != null) sync()
    }

    suspend fun sync(): CloudSyncState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val connection = _connection.value ?: return@withLock _syncState.value
            _syncState.value = CloudSyncState(syncing = true, lastSyncAt = _syncState.value.lastSyncAt)
            try {
                val remote = scan(connection.publicKey)
                val local = books.listBooks().filter { it.cloudKey == connection.publicKey }
                val localByPath = local.associateBy { it.cloudPath.orEmpty() }
                var added = 0
                var updated = 0

                for (item in remote) {
                    _syncState.value = _syncState.value.copy(current = item.title)
                    val existing = localByPath[item.path]
                    try {
                        when {
                            existing == null -> {
                                createBook(connection, item)
                                added += 1
                            }
                            item is CloudAudioBook && existing.kind == BookKind.AUDIO -> {
                                if (updateAudio(connection, existing, item)) updated += 1
                            }
                            item is CloudTextBook && existing.kind == BookKind.TEXT -> {
                                if (updateText(connection, existing, item)) updated += 1
                            }
                        }
                    } catch (error: Exception) {
                        // Одна проблемная книга не должна срывать всю синхронизацию.
                        android.util.Log.e("CloudLibrary", "skip ${item.path}", error)
                    }
                }

                val remotePaths = remote.map { it.path }.toSet()
                var missing = 0
                for (book in local) {
                    val isMissing = book.cloudPath.orEmpty() !in remotePaths
                    if (book.cloudMissing != isMissing) {
                        books.saveMeta(book.copy(cloudMissing = isMissing))
                        if (isMissing) missing += 1
                    }
                }

                CloudSyncState(
                    syncing = false,
                    added = added,
                    updated = updated,
                    missing = missing,
                    lastSyncAt = System.currentTimeMillis(),
                ).also { _syncState.value = it }
            } catch (error: Exception) {
                CloudSyncState(
                    syncing = false,
                    error = error.message ?: "Не удалось обновить облачную библиотеку",
                    lastSyncAt = _syncState.value.lastSyncAt,
                ).also { _syncState.value = it }
            }
        }
    }

    // ----- Обход папки -----

    /**
     * Папка с аудиофайлами — одна аудиокнига (вложенные папки не трогаем).
     * В остальных папках каждый текстовый файл — отдельная книга.
     */
    private suspend fun scan(publicKey: String): List<CloudBook> {
        val result = mutableListOf<CloudBook>()
        visit(publicKey, "", 0, result)
        return result
    }

    private suspend fun visit(
        publicKey: String,
        relPath: String,
        depth: Int,
        result: MutableList<CloudBook>,
    ) {
        if (depth > MAX_DEPTH) return
        val listing = yandex.listFolder(publicKey, relPath)
        val path = relPath.ifBlank { ROOT_PATH }
        val files = listing.entries.filter { !it.isDir }
        val audioFiles = files.filter { AudioFormats.isAudio(it.name) }
        if (audioFiles.isNotEmpty()) {
            val ordered = TrackNaming.naturalOrder(audioFiles.map { it.name })
                .map { name -> audioFiles.first { it.name == name } }
            result += CloudAudioBook(path, listing.name, ordered)
            return
        }
        files.filter { isText(it.name) }.forEach { entry ->
            result += CloudTextBook(
                path = entry.relPath,
                title = entry.name.substringBeforeLast('.').ifBlank { entry.name },
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                modified = entry.modified,
            )
        }
        listing.entries.filter { it.isDir }.forEach { dir ->
            try {
                visit(publicKey, dir.relPath, depth + 1, result)
            } catch (error: Exception) {
                // Недоступная подпапка не должна ломать обход остальных.
                android.util.Log.e("CloudLibrary", "skip folder ${dir.relPath}", error)
            }
        }
    }

    private fun isText(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    // ----- Создание и обновление -----

    private suspend fun createBook(connection: CloudConnection, item: CloudBook): Book = when (item) {
        is CloudAudioBook -> createAudio(connection, item)
        is CloudTextBook -> createText(connection, item)
    }

    private fun createAudio(connection: CloudConnection, item: CloudAudioBook): Book {
        val id = UUID.randomUUID().toString()
        val tracks = item.files.map { file ->
            AudioTrack(
                title = TrackNaming.titleFromFileName(file.name),
                path = file.relPath,
                durationMs = 0L,
                remote = true,
                sizeBytes = file.sizeBytes,
            )
        }
        val book = Book(
            id = id,
            title = item.title,
            author = null,
            format = null,
            addedAt = System.currentTimeMillis(),
            chapterCount = tracks.size,
            kind = BookKind.AUDIO,
            audioPublicKey = connection.publicKey,
            cloudKey = connection.publicKey,
            cloudPath = item.path,
        )
        audio.saveTracks(id, tracks)
        books.saveMeta(book)
        return book
    }

    private suspend fun createText(connection: CloudConnection, item: CloudTextBook): Book {
        val format = books.formatOf(item.name)
            ?: throw IllegalArgumentException("Формат ${item.name} не поддерживается")
        val temp = File(context.cacheDir, "cloud-${UUID.randomUUID()}.${format.name.lowercase()}")
        try {
            yandex.downloadTo(connection.publicKey, item.path, temp)
            return temp.inputStream().use { input ->
                val book = books.importStream(item.name, format, input)
                val tagged = book.copy(
                    cloudKey = connection.publicKey,
                    cloudPath = item.path,
                    cloudMissing = false,
                    cloudSize = item.sizeBytes,
                    cloudModified = item.modified,
                )
                books.saveMeta(tagged)
                tagged
            }
        } finally {
            temp.delete()
        }
    }

    /** @return true, если книга действительно обновилась. */
    private suspend fun updateAudio(
        connection: CloudConnection,
        existing: Book,
        item: CloudAudioBook,
    ): Boolean {
        val current = audio.tracks(existing.id)
        val known = current.associateBy { it.path }
        val merged = item.files.map { file ->
            known[file.relPath]?.copy(sizeBytes = file.sizeBytes) ?: AudioTrack(
                title = TrackNaming.titleFromFileName(file.name),
                path = file.relPath,
                durationMs = 0L,
                remote = true,
                sizeBytes = file.sizeBytes,
            )
        }
        val tracksChanged = merged.map { it.path } != current.map { it.path }
        val renamed = existing.title != item.title
        if (tracksChanged) {
            audio.saveTracks(existing.id, merged)
        }
        if (tracksChanged || renamed || existing.cloudMissing) {
            books.saveMeta(
                existing.copy(
                    title = item.title,
                    chapterCount = merged.size,
                    cloudMissing = false,
                    audioPublicKey = connection.publicKey,
                ),
            )
            return true
        }
        return false
    }

    /** @return true, если файл изменился и книга перечитана. */
    private suspend fun updateText(
        connection: CloudConnection,
        existing: Book,
        item: CloudTextBook,
    ): Boolean {
        val unchanged = existing.cloudSize == item.sizeBytes &&
            !item.modified.isNullOrBlank() &&
            existing.cloudModified == item.modified
        if (unchanged) {
            if (existing.cloudMissing) {
                books.saveMeta(existing.copy(cloudMissing = false))
            }
            return false
        }
        val format = books.formatOf(item.name) ?: return false
        val temp = File(context.cacheDir, "cloud-${UUID.randomUUID()}.${format.name.lowercase()}")
        try {
            yandex.downloadTo(connection.publicKey, item.path, temp)
            val updated = temp.inputStream().use { input ->
                books.replaceSource(existing, item.name, format, input)
            }
            books.saveMeta(
                updated.copy(
                    cloudKey = connection.publicKey,
                    cloudPath = item.path,
                    cloudMissing = false,
                    cloudSize = item.sizeBytes,
                    cloudModified = item.modified,
                ),
            )
            return true
        } finally {
            temp.delete()
        }
    }

    private fun loadConnection(): CloudConnection? {
        val publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: "Облачная библиотека"
        return CloudConnection(publicKey, name)
    }

    private companion object {
        const val KEY_PUBLIC = "public_key"
        const val KEY_NAME = "folder_name"
        const val ROOT_PATH = "/"
        const val MAX_DEPTH = 4
        val TEXT_EXTENSIONS = setOf("txt", "fb2", "epub", "pdf", "docx")
    }
}
