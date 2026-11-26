package ru.vtext.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookFormat
import ru.vtext.app.domain.model.BookKind
import ru.vtext.app.domain.model.Chapter
import ru.vtext.app.domain.model.ChapterImage
import ru.vtext.app.domain.model.TextPreset
import ru.vtext.app.domain.parser.BookParser
import ru.vtext.app.domain.parser.DocxParser
import ru.vtext.app.domain.epub.EpubCompiler
import ru.vtext.app.domain.parser.EpubParser
import ru.vtext.app.domain.parser.Fb2Parser
import ru.vtext.app.domain.parser.PdfParser
import ru.vtext.app.domain.parser.RtfParser
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
        BookFormat.RTF to RtfParser(),
    )
    private val cache = ConcurrentHashMap<String, Book>()
    private val parseLocks = ConcurrentHashMap<String, Mutex>()

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
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Не удалось открыть файл")
        importStream(displayName, format, input, preset)
    }

    /** Импорт из потока: так добавляются текстовые книги из облачной библиотеки. */
    suspend fun importStream(
        displayName: String,
        format: BookFormat,
        input: InputStream,
        preset: TextPreset = TextPreset.AUTO,
    ): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val source = File(dir, "source.${format.name.lowercase()}")
        input.use { stream ->
            source.outputStream().use { output -> stream.copyTo(output) }
        }
        buildBook(id, dir, displayName, format, source, preset)
    }

    /** Формат по имени файла (без mime): нужен облачной библиотеке. */
    fun formatOf(name: String): BookFormat? = detectFormat(name, null)

    /**
     * Импорт без разбора: файл просто скачивается, а главы появятся при первом
     * открытии книги. Нужен облачной библиотеке, чтобы большие PDF не тормозили
     * синхронизацию на минуты.
     */
    suspend fun importStreamDeferred(
        displayName: String,
        format: BookFormat,
        input: InputStream,
    ): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val source = File(dir, "source.${format.name.lowercase()}")
        input.use { stream ->
            source.outputStream().use { output -> stream.copyTo(output) }
        }
        val book = Book(
            id = id,
            title = displayName.substringBeforeLast('.').ifBlank { displayName },
            author = null,
            format = format,
            addedAt = System.currentTimeMillis(),
            preset = TextPreset.AUTO,
            chapterCount = 0,
        )
        writeMeta(dir, book)
        // В кэш не кладём: иначе loadBook вернёт книгу без глав и не разберёт файл.
        book
    }

    /**
     * Обновление источника без разбора: старый кэш глав удаляется, новые главы
     * появятся при следующем открытии книги.
     */
    suspend fun replaceSourceDeferred(
        book: Book,
        displayName: String,
        format: BookFormat,
        input: InputStream,
    ): Book = withContext(Dispatchers.IO) {
        val dir = File(root, book.id).apply { mkdirs() }
        dir.listFiles()
            ?.filter { it.name.startsWith("source.") || it.name == "chapters.json" }
            ?.forEach { it.delete() }
        File(dir, "cover").delete()
        File(dir, "book.epub").delete()
        File(dir, "epub.version").delete()
        File(dir, "epub-assets").deleteRecursively()
        val source = File(dir, "source.${format.name.lowercase()}")
        input.use { stream ->
            source.outputStream().use { output -> stream.copyTo(output) }
        }
        val updated = book.copy(
            format = format,
            chapters = emptyList(),
            chapterCount = 0,
        )
        writeMeta(dir, updated)
        // Старый кэш с главами больше не актуален — перечитаем при открытии.
        cache.remove(book.id)
        updated
    }

    /**
     * Перечитывает текстовую книгу из нового файла, сохраняя id, прогресс,
     * закладки и настройки книги. Используется, когда файл в облаке изменился.
     */
    suspend fun replaceSource(
        book: Book,
        displayName: String,
        format: BookFormat,
        input: InputStream,
    ): Book = withContext(Dispatchers.IO) {
        val dir = File(root, book.id).apply { mkdirs() }
        // Старый источник и кэш разбора больше не нужны.
        dir.listFiles()
            ?.filter { it.name.startsWith("source.") || it.name == "chapters.json" }
            ?.forEach { it.delete() }
        File(dir, "cover").delete()
        File(dir, "book.epub").delete()
        File(dir, "epub.version").delete()
        File(dir, "epub-assets").deleteRecursively()
        val source = File(dir, "source.${format.name.lowercase()}")
        input.use { stream ->
            source.outputStream().use { output -> stream.copyTo(output) }
        }
        val parsed = parsers.getValue(format).parse(
            source,
            displayName.substringBeforeLast('.'),
            book.preset,
        )
        val updated = book.copy(
            title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
            author = parsed.author,
            format = format,
            chapters = parsed.chapters.ifEmpty {
                listOf(Chapter("Текст", listOf("Файл не содержит читаемого текста")))
            },
            chapterCount = parsed.chapters.size,
        )
        writeMeta(dir, updated)
        writeChapters(dir, updated)
        val cover = CoverExtractor.extract(format, source, dir)
        val result = updated.copy(coverPath = cover?.absolutePath)
        cache[book.id] = result
        result
    }

    private fun buildBook(
        id: String,
        dir: File,
        displayName: String,
        format: BookFormat,
        source: File,
        preset: TextPreset,
    ): Book {
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
            chapterCount = parsed.chapters.size,
        )
        writeMeta(dir, book)
        writeChapters(dir, book)
        val cover = CoverExtractor.extract(format, source, dir)
        val bookWithCover = book.copy(coverPath = cover?.absolutePath)
        cache[id] = bookWithCover
        return bookWithCover
    }

    suspend fun loadBook(id: String): Book? = withContext(Dispatchers.IO) {
        cache[id]?.let { cached ->
            // Книга из облака сначала живёт в кэше без глав — её надо разобрать.
            if (cached.kind == BookKind.AUDIO || cached.chapters.isNotEmpty()) {
                return@withContext cached
            }
        }
        val dir = File(root, id)
        if (!dir.isDirectory) return@withContext null
        val meta = readMeta(dir) ?: return@withContext null
        if (meta.kind == BookKind.AUDIO) {
            // Аудиокнигу разбирает AudioRepository: здесь только метаданные.
            val book = meta.copy(chapterCount = meta.chapterCount)
            cache[id] = book
            return@withContext book
        }
        val format = meta.format ?: return@withContext null
        // Один и тот же PDF может разбираться из читалки и из фонового прогрева —
        // не делаем это дважды.
        val lock = parseLocks.computeIfAbsent(id) { Mutex() }
        lock.withLock {
            cache[id]?.let { cached ->
                if (cached.kind == BookKind.AUDIO || cached.chapters.isNotEmpty()) {
                    return@withLock cached
                }
            }
            val source = dir.listFiles()?.firstOrNull { it.name.startsWith("source.") }
            if (coverFile(dir) == null && source != null) {
                CoverExtractor.extract(format, source, dir)
            }
            val cachedChapters = readChapters(dir)
            val cachedVersion = readSegmentVersion(dir)
            val compiledEpub = File(dir, "book.epub")
            // Не-EPUB книги один раз собираются в EPUB: дальше читалка работает
            // только с ним, а картинки уже лежат в epub-assets.
            val needsCompile = format != BookFormat.EPUB &&
                (!compiledEpub.isFile || readEpubVersion(dir) != EpubCompiler.VERSION)
            val useCache = cachedChapters != null &&
                cachedVersion == SEGMENT_VERSION &&
                !needsCompile
            val chapters = if (useCache) {
                cachedChapters
            } else {
                source ?: return@withLock null
                val assets = File(dir, "epub-assets").apply { mkdirs() }
                val parsed = runCatching {
                    parsers.getValue(format).parse(source, meta.title, meta.preset, assets)
                }.onFailure {
                    android.util.Log.e("VtextParse", "parse failed: $id", it)
                }.getOrThrow()
                android.util.Log.i(
                    "VtextParse",
                    "parsed $id (${format.name}): ${parsed.chapters.size} chapters",
                )
                if (format == BookFormat.EPUB) {
                    parsed.chapters
                } else {
                    // Если повторный разбор не удался, лучше оставить старый кэш.
                    val text = parsed.chapters.ifEmpty { cachedChapters ?: emptyList() }
                    if (text.isEmpty()) {
                        emptyList()
                    } else {
                        runCatching {
                            EpubCompiler.write(
                                target = compiledEpub,
                                title = parsed.title.ifBlank { meta.title },
                                author = parsed.author ?: meta.author,
                                chapters = text,
                                assetsDir = assets,
                            )
                            val roundtrip = EpubParser()
                                .parse(compiledEpub, meta.title, meta.preset, assets)
                                .chapters
                            // EPUB хранит только текст: сноски и номера страниц
                            // возвращаем из исходного разбора.
                            mergeChapterMeta(text, roundtrip)
                        }.getOrDefault(text)
                    }
                }
            }
            // В epub-assets могли остаться картинки от прошлой сборки.
            runCatching {
                val referenced = chapters.flatMap { it.images }.map { it.file }.toSet()
                File(dir, "epub-assets").listFiles()?.forEach { file ->
                    if (file.name !in referenced) file.delete()
                }
            }
            val book = meta.copy(
                chapters = chapters,
                coverPath = coverFile(dir)?.absolutePath,
                chapterCount = chapters.size,
            )
            if (chapters.isEmpty()) {
                // Разбор не дал текста — оставляем книгу «неразобранной»,
                // чтобы следующее открытие попробовало снова.
                return@withLock book
            }
            // Кэшируем разбор, чтобы следующее открытие не перечитывало PDF.
            writeChapters(dir, book)
            // Дописываем meta и для старых книг: библиотеке нужен chapterCount.
            if (!useCache || meta.chapterCount != chapters.size) {
                writeMeta(dir, book)
            }
            cache[id] = book
            book
        }
    }

    /**
     * Фоновый разбор отложенных книг (из облака): к моменту открытия главы
     * уже готовы. Запускается после синхронизации, по одной книге за раз.
     */
    suspend fun warmUpDeferredBooks(limit: Int = 2) {
        listBooks()
            .filter { it.kind == BookKind.TEXT && it.format != null }
            .filter { book ->
                if (book.chapterCount == 0) return@filter true
                if (book.format == BookFormat.EPUB) return@filter false
                // Старые книги без собранного EPUB тоже готовим заранее.
                val dir = File(root, book.id)
                !File(dir, "book.epub").isFile || readEpubVersion(dir) != EpubCompiler.VERSION
            }
            .take(limit)
            .forEach { book ->
                runCatching { loadBook(book.id) }
            }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        cache.remove(id)
        Unit
    }

    /** Папка книги — нужна [AudioRepository] для файлов и tracks.json. */
    fun bookDir(id: String): File = File(root, id).apply { mkdirs() }

    /**
     * Правка названия и автора: переписывает meta.json и сбрасывает кэш книги,
     * чтобы следующее открытие прочитало новые поля.
     */
    suspend fun updateDetails(id: String, title: String, author: String?) =
        withContext(Dispatchers.IO) {
            val file = File(root, id).let { dir -> File(dir, "meta.json") }
            if (!file.isFile) return@withContext
            val json = runCatching { JSONObject(file.readText()) }.getOrNull()
                ?: return@withContext
            json.put("title", title.ifBlank { "Без названия" })
            val trimmedAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmedAuthor == null) json.remove("author") else json.put("author", trimmedAuthor)
            file.writeText(json.toString())
            cache.remove(id)
        }

    /** Запись meta.json для аудиокниг (импорт идёт через [AudioRepository]). */
    fun saveMeta(book: Book) {
        writeMeta(bookDir(book.id), book)
        if (book.kind == BookKind.AUDIO || book.chapters.isNotEmpty()) {
            cache[book.id] = book
        } else {
            // Отложенная текстовая книга: главы появятся при первом открытии.
            cache.remove(book.id)
        }
    }

    private fun detectFormat(name: String, mime: String?): BookFormat? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension == "txt" -> BookFormat.TXT
            extension == "fb2" -> BookFormat.FB2
            extension == "epub" -> BookFormat.EPUB
            extension == "pdf" -> BookFormat.PDF
            extension == "docx" -> BookFormat.DOCX
            extension == "rtf" -> BookFormat.RTF
            mime == "text/plain" -> BookFormat.TXT
            mime == "application/epub+zip" -> BookFormat.EPUB
            mime == "application/pdf" -> BookFormat.PDF
            mime == DOCX_MIME -> BookFormat.DOCX
            mime?.contains("fictionbook") == true -> BookFormat.FB2
            mime == "application/rtf" || mime == "text/rtf" -> BookFormat.RTF
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
            .put("format", book.format?.name ?: "")
            .put("kind", book.kind.name)
            .put("addedAt", book.addedAt)
            .put("segmentVersion", SEGMENT_VERSION)
            .put("preset", book.preset.id)
            .put("chapterCount", book.chapterCount.coerceAtLeast(book.chapters.size))
        if (book.audioPublicKey != null) {
            json.put("audioPublicKey", book.audioPublicKey)
        }
        if (book.cloudKey != null) {
            json.put("cloudKey", book.cloudKey)
            json.put("cloudPath", book.cloudPath.orEmpty())
            json.put("cloudSize", book.cloudSize)
            json.put("cloudModified", book.cloudModified.orEmpty())
            json.put("cloudMissing", book.cloudMissing)
        }
        File(dir, "meta.json").writeText(json.toString())
    }

    private fun readSegmentVersion(dir: File): Int {
        val file = File(dir, "meta.json")
        if (!file.isFile) return 0
        return runCatching { JSONObject(file.readText()).optInt("segmentVersion", 0) }.getOrDefault(0)
    }

    /**
     * После сборки EPUB и обратного разбора возвращает в главы то, чего в EPUB
     * нет: сноски страниц и номера страниц (для просмотра оригинала).
     */
    private fun mergeChapterMeta(source: List<Chapter>, target: List<Chapter>): List<Chapter> {
        if (source.size != target.size) return target
        return target.mapIndexed { index, chapter ->
            val original = source[index]
            if (original.footnotes.isEmpty() && original.page < 0) {
                chapter
            } else {
                chapter.copy(
                    footnotes = original.footnotes,
                    page = original.page,
                )
            }
        }
    }

    private fun readMeta(dir: File): Book? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val kind = runCatching { BookKind.valueOf(json.optString("kind", "")) }
                .getOrDefault(BookKind.TEXT)
            Book(
                id = json.optString("id", dir.name),
                title = json.optString("title", "Без названия"),
                author = json.optString("author").ifBlank { null },
                format = json.optString("format").ifBlank { null }
                    ?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() },
                addedAt = json.optLong("addedAt", 0L),
                preset = TextPreset.fromId(json.optString("preset", TextPreset.AUTO.id)),
                coverPath = coverFile(dir)?.absolutePath,
                chapterCount = json.optInt("chapterCount", 0),
                kind = kind,
                audioPublicKey = json.optString("audioPublicKey").ifBlank { null },
                cloudKey = json.optString("cloudKey").ifBlank { null },
                cloudPath = json.optString("cloudPath").ifBlank { null },
                cloudMissing = json.optBoolean("cloudMissing", false),
                cloudSize = json.optLong("cloudSize", 0L),
                cloudModified = json.optString("cloudModified").ifBlank { null },
            )
        }.getOrNull()
    }

    private fun withCover(dir: File, book: Book): Book {
        coverFile(dir)?.let { return book.copy(coverPath = it.absolutePath) }
        val format = book.format ?: return book
        val source = dir.listFiles()?.firstOrNull { it.name.startsWith("source.") } ?: return book
        val cover = CoverExtractor.extract(format, source, dir) ?: return book
        return book.copy(coverPath = cover.absolutePath)
    }

    private fun coverFile(dir: File): File? = File(dir, "cover").takeIf { it.isFile }

    private fun writeChapters(dir: File, book: Book) {
        val chapters = JSONArray()
        book.chapters.forEach { chapter ->
            val segments = JSONArray()
            chapter.segments.forEach { segments.put(it) }
            val footnotes = JSONArray()
            chapter.footnotes.forEach { note ->
                footnotes.put(
                    JSONObject()
                        .put("label", note.label)
                        .put("text", note.text),
                )
            }
            val images = JSONArray()
            chapter.images.forEach { image ->
                images.put(
                    JSONObject()
                        .put("afterSegment", image.afterSegment)
                        .put("file", image.file)
                        .put("alt", image.alt),
                )
            }
            chapters.put(
                JSONObject()
                    .put("title", chapter.title)
                    .put("segments", segments)
                    .put("depth", chapter.depth)
                    .put("page", chapter.page)
                    .put("images", images)
                    .put("footnotes", footnotes),
            )
        }
        File(dir, "chapters.json").writeText(chapters.toString())
    }

    private fun readEpubVersion(dir: File): Int? =
        File(dir, "epub.version").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun readChapters(dir: File): List<Chapter>? {
        val file = File(dir, "chapters.json")
        if (!file.isFile) return null
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val segments = obj.getJSONArray("segments")
                val imagesArray = obj.optJSONArray("images")
                val images = if (imagesArray == null) {
                    emptyList()
                } else {
                    (0 until imagesArray.length()).mapNotNull { imageIndex ->
                        val image = imagesArray.optJSONObject(imageIndex) ?: return@mapNotNull null
                        val file = image.optString("file")
                        if (file.isBlank()) return@mapNotNull null
                        ChapterImage(
                            afterSegment = image.optInt("afterSegment", -1),
                            file = file,
                            alt = image.optString("alt"),
                        )
                    }
                }
                val footnotesArray = obj.optJSONArray("footnotes")
                val footnotes = if (footnotesArray == null) {
                    emptyList()
                } else {
                    (0 until footnotesArray.length()).mapNotNull { noteIndex ->
                        val note = footnotesArray.optJSONObject(noteIndex) ?: return@mapNotNull null
                        val label = note.optString("label")
                        if (label.isBlank()) return@mapNotNull null
                        ru.vtext.app.domain.model.ChapterNote(
                            label = label,
                            text = note.optString("text"),
                        )
                    }
                }
                Chapter(
                    title = obj.optString("title", "Глава ${index + 1}"),
                    segments = (0 until segments.length()).map { segments.getString(it) },
                    depth = obj.optInt("depth", 0),
                    page = obj.optInt("page", -1),
                    images = images,
                    footnotes = footnotes,
                )
            }
        }.getOrNull()
    }

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val SEGMENT_VERSION = 43
    }
}
