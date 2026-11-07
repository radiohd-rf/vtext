package ru.vtext.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookFormat
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.TextPreset
import ru.vtext.app.domain.parser.BookParser
import ru.vtext.app.domain.parser.DocxParser
import ru.vtext.app.domain.parser.EpubParser
import ru.vtext.app.domain.parser.Fb2Parser
import ru.vtext.app.domain.parser.PdfParser
import ru.vtext.app.domain.parser.TxtParser

/**
 * Библиотека книг в приватном хранилище:
 * filesDir/books/<id>/source.<ext> + meta.json + chapters.json
 */
class BookRepository(private val context: Context) {

    private val root = File(context.filesDir, "books")
    private val parsers: Map<BookFormat, BookParser> = mapOf(
        BookFormat.TXT to TxtParser(),
        BookFormat.FB2 to Fb2Parser(),
        BookFormat.EPUB to EpubParser(),
        BookFormat.PDF to PdfParser(),
        BookFormat.DOCX to DocxParser(),
    )
    private val cache = mutableMapOf<String, Book>()

    suspend fun listBooks(): List<Book> = withContext(Dispatchers.IO) {
        root.listFiles()
            ?.mapNotNull { dir -> readMeta(dir)?.let { withCover(dir, it) } }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    suspend fun import(uri: Uri, preset: TextPreset = TextPreset.AUTO): Book = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "book.txt"
        val format = detectFormat(displayName, context.contentResolver.getType(uri))
            ?: throw IllegalArgumentException("Поддерживаются форматы TXT, FB2, EPUB, PDF и DOCX")

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val source = File(dir, "source.${format.name.lowercase()}")

        context.contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть файл")

        val parsed = parsers.getValue(format).parse(
            source,
            displayName.substringBeforeLast('.'),
            preset,
        )
        val book = Book(
            id = id,
            title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
            author = parsed.author,
            format = format,
            addedAt = System.currentTimeMillis(),
            chapters = parsed.chapters.ifEmpty {
                listOf(Chapter("Текст", listOf("Файл не содержит читаемого текста")))
            },
            preset = preset,
        )
        writeMeta(dir, book)
        writeChapters(dir, book)
        val cover = CoverExtractor.extract(format, source, dir)
        val bookWithCover = book.copy(coverPath = cover?.absolutePath)
        cache[id] = bookWithCover
        bookWithCover
    }

    suspend fun loadBook(id: String): Book? = withContext(Dispatchers.IO) {
        cache[id] ?: run {
            val dir = File(root, id)
            if (!dir.isDirectory) return@run null
            val meta = readMeta(dir) ?: return@run null
            val source = dir.listFiles()?.firstOrNull { it.name.startsWith("source.") }
            if (coverFile(dir) == null && source != null) {
                CoverExtractor.extract(meta.format, source, dir)
            }
            val cachedChapters = readChapters(dir)
            val cachedVersion = readSegmentVersion(dir)
            val useCache = cachedChapters != null && cachedVersion == SEGMENT_VERSION
            val chapters = if (useCache) {
                cachedChapters
            } else {
                source ?: return@run null
                val parsed = parsers.getValue(meta.format).parse(source, meta.title, meta.preset).chapters
                // Если повторный разбор не удался, лучше оставить старый кэш.
                parsed.ifEmpty { cachedChapters ?: emptyList() }
            }
            val book = meta.copy(
                chapters = chapters,
                coverPath = coverFile(dir)?.absolutePath,
            )            // Кэшируем разбор, чтобы следующее открытие не перечитывало PDF.
            writeChapters(dir, book)
            if (!useCache && chapters.isNotEmpty()) {
                writeMeta(dir, book)
            }
            cache[id] = book
            book
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        cache.remove(id)
        Unit
    }

    private fun detectFormat(name: String, mime: String?): BookFormat? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension == "txt" -> BookFormat.TXT
            extension == "fb2" -> BookFormat.FB2
            extension == "epub" -> BookFormat.EPUB
            extension == "pdf" -> BookFormat.PDF
            extension == "docx" -> BookFormat.DOCX
            mime == "text/plain" -> BookFormat.TXT
            mime == "application/epub+zip" -> BookFormat.EPUB
            mime == "application/pdf" -> BookFormat.PDF
            mime == DOCX_MIME -> BookFormat.DOCX
            mime?.contains("fictionbook") == true -> BookFormat.FB2
            else -> null
        }
    }

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

    private fun writeMeta(dir: File, book: Book) {
        val json = JSONObject()
            .put("id", book.id)
            .put("title", book.title)
            .put("author", book.author)
            .put("format", book.format.name)
            .put("addedAt", book.addedAt)
            .put("segmentVersion", SEGMENT_VERSION)
            .put("preset", book.preset.id)
        File(dir, "meta.json").writeText(json.toString())
    }

    private fun readSegmentVersion(dir: File): Int {
        val file = File(dir, "meta.json")
        if (!file.isFile) return 0
        return runCatching { JSONObject(file.readText()).optInt("segmentVersion", 0) }.getOrDefault(0)
    }

    private fun readMeta(dir: File): Book? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Book(
                id = json.optString("id", dir.name),
                title = json.optString("title", "Без названия"),
                author = json.optString("author").ifBlank { null },
                format = BookFormat.valueOf(json.optString("format", BookFormat.TXT.name)),
                addedAt = json.optLong("addedAt", 0L),
                preset = TextPreset.fromId(json.optString("preset", TextPreset.AUTO.id)),
                coverPath = coverFile(dir)?.absolutePath,
            )
        }.getOrNull()
    }

    private fun withCover(dir: File, book: Book): Book {
        coverFile(dir)?.let { return book.copy(coverPath = it.absolutePath) }
        val source = dir.listFiles()?.firstOrNull { it.name.startsWith("source.") } ?: return book
        val cover = CoverExtractor.extract(book.format, source, dir) ?: return book
        return book.copy(coverPath = cover.absolutePath)
    }

    private fun coverFile(dir: File): File? = File(dir, "cover").takeIf { it.isFile }

    private fun writeChapters(dir: File, book: Book) {
        val chapters = JSONArray()
        book.chapters.forEach { chapter ->
            val segments = JSONArray()
            chapter.segments.forEach { segments.put(it) }
            chapters.put(
                JSONObject()
                    .put("title", chapter.title)
                    .put("segments", segments)
                    .put("depth", chapter.depth),
            )
        }
        File(dir, "chapters.json").writeText(chapters.toString())
    }

    private fun readChapters(dir: File): List<Chapter>? {
        val file = File(dir, "chapters.json")
        if (!file.isFile) return null
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val segments = obj.getJSONArray("segments")
                Chapter(
                    title = obj.optString("title", "Глава ${index + 1}"),
                    segments = (0 until segments.length()).map { segments.getString(it) },
                    depth = obj.optInt("depth", 0),
                )
            }
        }.getOrNull()
    }

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val SEGMENT_VERSION = 9
    }
}
