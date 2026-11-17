package ru.vtext.app.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Расширения аудио, которые умеет проигрывать ExoPlayer. */
object AudioFormats {
    val extensions = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav")

    fun isAudio(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in extensions
}

/** Разбор публичной ссылки Яндекс.Диска: нужна папка вида /d/<id>. */
object YandexDiskLinks {
    private val hosts = setOf(
        "disk.yandex.ru",
        "disk.yandex.com",
        "disk.yandex.kz",
        "disk.yandex.by",
        "disk.yandex.ua",
        "yadi.sk",
    )

    /** Возвращает каноническую ссылку или null, если это не публичная папка. */
    fun normalize(input: String): String? {
        // Клавиатуры любят вставлять пробел после точки — для ссылки они лишние.
        val trimmed = input.filterNot { it.isWhitespace() }
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in hosts) return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        if (segments.size < 2 || segments[0] != "d") return null
        val id = segments[1].trim()
        if (id.isEmpty()) return null
        return "https://disk.yandex.ru/d/$id"
    }
}

data class YandexAudioFile(val name: String, val path: String, val sizeBytes: Long)

data class YandexAudioFolder(val name: String, val files: List<YandexAudioFile>)

/**
 * Публичное API Яндекс.Диска без токена: список файлов папки и временные ссылки
 * на скачивание. Ссылки живут около часа, поэтому кэшируются и обновляются.
 */
class YandexDiskClient {

    private data class CachedHref(val href: String, val expiresAtMs: Long)

    private val hrefCache = ConcurrentHashMap<String, CachedHref>()

    /** Аудиофайлы публичной папки в естественном порядке номеров. */
    suspend fun listAudio(publicKey: String): YandexAudioFolder = withContext(Dispatchers.IO) {
        var offset = 0
        var folderName: String? = null
        var rootPath: String? = null
        val files = mutableListOf<YandexAudioFile>()

        while (true) {
            val url = buildString {
                append(API)
                append("/public/resources?public_key=")
                append(encode(publicKey))
                append("&limit=200&offset=")
                append(offset)
            }
            val json = getJson(url)
            if (offset == 0) {
                if (json.optString("type") != "dir") {
                    throw IllegalStateException("Ссылка должна вести на папку с аудиофайлами")
                }
                folderName = json.optString("name")
                rootPath = json.optString("path")
            }
            val embedded = json.optJSONObject("_embedded") ?: break
            val items = embedded.optJSONArray("items") ?: break
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (item.optString("type") != "file") continue
                val name = item.optString("name")
                if (!AudioFormats.isAudio(name)) continue
                val fullPath = item.optString("path")
                // download-эндпоинт ждёт путь от корня публичной папки, с ведущим «/».
                val relativeRaw = fullPath
                    .removePrefix(rootPath.orEmpty())
                    .trimStart('/')
                val relative = if (relativeRaw.isBlank() || relativeRaw.startsWith("disk:")) {
                    "/$name"
                } else {
                    "/$relativeRaw"
                }
                files += YandexAudioFile(
                    name = name,
                    path = relative,
                    sizeBytes = item.optLong("size"),
                )
            }
            val total = embedded.optInt("total", 0)
            offset += items.length()
            if (items.length() == 0 || offset >= total) break
        }

        if (files.isEmpty()) {
            throw IllegalStateException("В папке нет аудиофайлов")
        }
        val ordered = TrackNaming.naturalOrder(files.map { it.name })
            .map { name -> files.first { it.name == name } }
        YandexAudioFolder(
            name = folderName?.takeIf { it.isNotBlank() } ?: "Аудиокнига",
            files = ordered,
        )
    }

    /** Временная ссылка на файл; кэшируется на 45 минут. */
    suspend fun resolveHref(publicKey: String, path: String, force: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val cacheKey = "$publicKey|$path"
            val now = System.currentTimeMillis()
            val cached = hrefCache[cacheKey]
            if (!force && cached != null && cached.expiresAtMs > now) {
                return@withContext cached.href
            }
            val url = buildString {
                append(API)
                append("/public/resources/download?public_key=")
                append(encode(publicKey))
                append("&path=")
                append(encode(path))
            }
            val href = getJson(url).optString("href")
            if (href.isBlank()) error("Яндекс не выдал ссылку на файл")
            hrefCache[cacheKey] = CachedHref(href, now + HREF_TTL_MS)
            href
        }

    fun invalidateHref(publicKey: String, path: String) {
        hrefCache.remove("$publicKey|$path")
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code == 404) error("Ссылка недействительна или папка закрыта")
            if (code == 401 || code == 403) error("Нет доступа к папке по ссылке")
            if (code !in 200..299) error("Яндекс.Диск ответил $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val API = "https://cloud-api.yandex.net/v1/disk"
        const val USER_AGENT = "vtext"
        const val HREF_TTL_MS = 45 * 60 * 1000L
    }
}
