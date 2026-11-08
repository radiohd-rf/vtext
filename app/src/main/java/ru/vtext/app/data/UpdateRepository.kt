package ru.vtext.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UpdateInfo(
    val version: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/**
 * Проверка и скачивание APK из GitHub Releases.
 *
 * Вместо api.github.com (лимит запросов, 5xx) используем редирект
 * `github.com/<repo>/releases/latest` → `/releases/tag/vX.Y.Z`:
 * он не требует токена и не упирается в квоту API.
 */
class UpdateRepository(private val context: Context) {

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL("$REPO_URL/releases/latest").openConnection() as HttpURLConnection
        connection.apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            Log.d(TAG, "releases/latest → $code")
            if (code !in 300..399) error("GitHub ответил $code")
            val location = connection.getHeaderField("Location")
                ?: error("GitHub не вернул адрес релиза")
            val tag = location.substringAfterLast("/tag/", missingDelimiterValue = "")
            val version = tag.removePrefix("v")
            if (version.isBlank()) error("Не удалось определить версию релиза")
            val downloadUrl = "$REPO_URL/releases/download/v$version/vtext-$version.apk"
            UpdateInfo(
                version = version,
                sizeBytes = sizeOf(downloadUrl),
                downloadUrl = downloadUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "vtext-${info.version}.apk")
            val connection = URL(info.downloadUrl).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("GitHub ответил ${connection.responseCode}")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                            read = input.read(buffer)
                        }
                    }
                }
                target
            } finally {
                connection.disconnect()
            }
        }

    /** Размер APK для подписи «84 МБ»: HEAD-запрос, при ошибке — 0. */
    private fun sizeOf(url: String): Long = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode in 200..299) connection.contentLengthLong else 0L
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "UpdateRepository"
        private const val OWNER = "radiohd-rf"
        private const val REPO = "vtext"
        private const val REPO_URL = "https://github.com/$OWNER/$REPO"
        private const val USER_AGENT = "vtext-updater"

        /** true, если [remote] новее [current] (сравнение по числовым частям версии). */
        fun isNewer(remote: String, current: String): Boolean {
            val remoteParts = remote.substringBefore('-')
                .split('.')
                .mapNotNull { it.toIntOrNull() }
            val currentParts = current.substringBefore('-')
                .split('.')
                .mapNotNull { it.toIntOrNull() }
            for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
                val left = remoteParts.getOrElse(index) { 0 }
                val right = currentParts.getOrElse(index) { 0 }
                if (left != right) return left > right
            }
            return false
        }
    }
}
