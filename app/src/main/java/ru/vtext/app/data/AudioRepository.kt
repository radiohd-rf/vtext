package ru.vtext.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vtext.app.domain.model.AudioTrack
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookKind

/**
 * Аудиокниги: импорт файлов, дорожки и метаданные.
 *
 * Раскладка: filesDir/books/<id>/audio/NN - Название.ext + tracks.json + meta.json.
 * Метаданные книг пишет [BookRepository] — здесь только аудио-специфика.
 */
class AudioRepository(
    private val context: Context,
    private val books: BookRepository,
    private val yandex: YandexDiskClient,
) {

    /** Поддерживаемые расширения. */
    private val supported = AudioFormats.extensions

    fun isSupported(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in supported

    /**
     * Импорт выбранных файлов.
     *
     * @param asOneBook true — все файлы становятся дорожками одной книги,
     *   false — каждый файл отдельной книгой.
     */
    suspend fun import(
        uris: List<Uri>,
        asOneBook: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Book> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()

        val named = uris.mapNotNull { uri ->
            val name = queryDisplayName(uri) ?: return@mapNotNull null
            if (!isSupported(name)) return@mapNotNull null
            uri to name
        }
        if (named.isEmpty()) {
            throw IllegalArgumentException("Поддерживаются MP3, M4A, FLAC, OGG/OPUS и WAV")
        }

        val ordered = TrackNaming.naturalOrder(named.map { it.second })
            .map { name -> named.first { it.second == name } }

        if (!asOneBook) {
            return@withContext ordered.mapIndexed { index, (uri, name) ->
                onProgress(index + 1, ordered.size)
                importSingle(listOf(uri to name))
            }
        }
        listOf(importSingle(ordered, onProgress))
    }

    private fun importSingle(
        files: List<Pair<Uri, String>>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Book {
        val id = UUID.randomUUID().toString()
        val dir = books.bookDir(id)
        val audioDir = File(dir, "audio").apply { mkdirs() }

        val tracks = mutableListOf<AudioTrack>()
        var firstMeta: TrackMeta? = null

        files.forEachIndexed { index, (uri, name) ->
            val target = File(audioDir, TrackNaming.storedFileName(index + 1, name))
            copy(uri, target)

            val meta = readMetadata(target)
            if (firstMeta == null) firstMeta = meta
            tracks += AudioTrack(
                title = meta.title ?: TrackNaming.titleFromFileName(name),
                path = target.absolutePath,
                durationMs = meta.durationMs,
                sizeBytes = target.length(),
            )
            onProgress(index + 1, files.size)
        }

        val cover = firstMeta?.picture?.let { writeCover(dir, it) }

        val book = Book(
            id = id,
            title = firstMeta?.title?.takeIf { files.size == 1 }
                ?: TrackNaming.titleFromFileName(files.first().second),
            author = firstMeta?.author,
            format = null,
            addedAt = System.currentTimeMillis(),
            chapterCount = tracks.size,
            coverPath = cover?.absolutePath,
            kind = BookKind.AUDIO,
        )
        writeTracks(dir, tracks)
        books.saveMeta(book)
        return book
    }

    /**
     * Встроенные онлайн-книги из assets: добавляются при запуске, если их ещё нет.
     * Звук не скачивается, поэтому книги доступны сразу; при открытии список
     * сверяется с папкой. Удалённую пользователем книгу повторно не возвращаем.
     */
    /**
     * Удаляет встроенные онлайн-книги, которые раньше добавлялись при первом
     * запуске: в релизе их больше нет. Трогаем только книги без [cloudKey],
     * то есть не добавленные пользователем и не из облачной библиотеки.
     */
    suspend fun removeLegacySeededBooks(onRemoved: (String) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_REMOVED, false)) return@withContext 0
            var removed = 0
            books.listBooks().forEach { book ->
                if (book.cloudKey == null && book.audioPublicKey in LEGACY_SEEDED_KEYS) {
                    books.delete(book.id)
                    onRemoved(book.id)
                    removed += 1
                }
            }
            prefs.edit().putBoolean(KEY_REMOVED, true).apply()
            removed
        }

    private companion object {
        const val KEY_REMOVED = "legacy_seeded_removed"

        /** Публичные папки прежних встроенных книг «Мастер и Маргарита» и «Лучко Клара». */
        val LEGACY_SEEDED_KEYS = setOf(
            "https://disk.yandex.ru/d/yS2Mi2YlLpXnSw",
            "https://disk.yandex.ru/d/eZUH60LicxhdVw",
        )
    }

    suspend fun tracks(bookId: String): List<AudioTrack> = withContext(Dispatchers.IO) {
        val file = File(books.bookDir(bookId), "tracks.json")
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AudioTrack(
                    title = item.optString("title", "Глава ${index + 1}"),
                    path = item.optString("path"),
                    durationMs = item.optLong("durationMs", 0L),
                    startMs = item.optLong("startMs", 0L),
                    remote = item.optBoolean("remote", false),
                    sizeBytes = item.optLong("sizeBytes", 0L),
                )
            }.filter { it.path.isNotBlank() && (it.remote || File(it.path).isFile) }
        }.getOrDefault(emptyList())
    }

    /**
     * Онлайн-аудиокнига из публичной папки Яндекс.Диска: файлы не копируются,
     * в tracks.json хранятся пути внутри папки, а ссылки на файлы запрашиваются
     * плеером по мере воспроизведения.
     */
    suspend fun importFromYandex(publicKey: String): Book = withContext(Dispatchers.IO) {
        val folder = yandex.listAudio(publicKey)
        val id = UUID.randomUUID().toString()
        val dir = books.bookDir(id)

        val tracks = folder.files.map { file ->
            AudioTrack(
                title = TrackNaming.titleFromFileName(file.name),
                path = file.path,
                durationMs = 0L,
                remote = true,
                sizeBytes = file.sizeBytes,
            )
        }

        val book = Book(
            id = id,
            title = folder.name,
            author = null,
            format = null,
            addedAt = System.currentTimeMillis(),
            chapterCount = tracks.size,
            kind = BookKind.AUDIO,
            audioPublicKey = publicKey,
        )
        writeTracks(dir, tracks)
        books.saveMeta(book)
        book
    }

    /**
     * Перечитывает публичную папку перед открытием книги: если в неё добавили
     * файлы (или удалили), список дорожек обновится. Длительности уже известных
     * дорожек сохраняются, порядок — по номерам в именах.
     */
    suspend fun refreshRemoteTracks(bookId: String): List<AudioTrack> = withContext(Dispatchers.IO) {
        val current = tracks(bookId)
        val book = books.loadBook(bookId) ?: return@withContext current
        val publicKey = book.audioPublicKey ?: return@withContext current

        val files = runCatching { remoteAudioFiles(publicKey, book.cloudPath) }.getOrNull()
            ?: return@withContext current

        val known = current.associateBy { it.path }
        val merged = files.map { file ->
            // Название из tracks.json сохраняем: встроенные книги размечены вручную
            // («Часть 001»), а не по имени файла («Luchko 001»).
            known[file.path]?.copy(sizeBytes = file.sizeBytes) ?: AudioTrack(
                title = TrackNaming.titleFromFileName(file.name),
                path = file.path,
                durationMs = 0L,
                remote = true,
                sizeBytes = file.sizeBytes,
            )
        }
        val changed = merged.map { it.path } != current.map { it.path }
        if (changed) {
            writeTracks(books.bookDir(bookId), merged)
            books.saveMeta(book.copy(chapterCount = merged.size))
        }
        merged
    }

    /** Аудиофайлы облачной книги: подпапка библиотеки или вся папка отдельной ссылки. */
    private suspend fun remoteAudioFiles(publicKey: String, cloudPath: String?): List<YandexAudioFile> {
        if (cloudPath == null) return yandex.listAudio(publicKey).files
        val listing = yandex.listFolder(publicKey, cloudPath)
        val files = listing.entries
            .filter { !it.isDir && AudioFormats.isAudio(it.name) }
            .map { YandexAudioFile(it.name, it.relPath, it.sizeBytes) }
        if (files.isEmpty()) throw IllegalStateException("В папке нет аудиофайлов")
        return TrackNaming.naturalOrder(files.map { it.name })
            .map { name -> files.first { it.name == name } }
    }

    /** Сохраняет дорожки (например, после того как узнали их длительности). */
    fun saveTracks(bookId: String, tracks: List<AudioTrack>) {        writeTracks(books.bookDir(bookId), tracks)
    }

    private fun writeTracks(dir: File, tracks: List<AudioTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("title", track.title)
                    .put("path", track.path)
                    .put("durationMs", track.durationMs)
                    .put("startMs", track.startMs)
                    .put("remote", track.remote)
                    .put("sizeBytes", track.sizeBytes),
            )
        }
        File(dir, "tracks.json").writeText(array.toString())
    }

    private fun copy(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть файл: ${target.name}")
    }

    private fun writeCover(dir: File, bytes: ByteArray): File? = runCatching {
        File(dir, "cover").apply { writeBytes(bytes) }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private data class TrackMeta(
        val title: String?,
        val author: String?,
        val durationMs: Long,
        val picture: ByteArray?,
    )

    private fun readMetadata(file: File): TrackMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            TrackMeta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.ifBlank { null },
                author = (
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    )?.trim()?.ifBlank { null },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                picture = retriever.embeddedPicture,
            )
        } catch (error: Exception) {
            TrackMeta(null, null, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
