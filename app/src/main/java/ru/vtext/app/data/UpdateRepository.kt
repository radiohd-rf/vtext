package ru.vtext.app.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(
    val version: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/** Проверка и скачивание APK из GitHub Releases публичного репозитория. */
class UpdateRepository(private val context: Context) {

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "vtext-updater")
        }
        try {
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("tag_name")
                .removePrefix("v")
                .ifBlank { return@withContext null }
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return@withContext UpdateInfo(
                        version = version,
                        sizeBytes = asset.optLong("size"),
                        downloadUrl = asset.optString("browser_download_url"),
                    )
                }
            }
            null
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
                setRequestProperty("User-Agent", "vtext-updater")
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

    companion object {
        private const val LATEST_RELEASE =
            "https://api.github.com/repos/radiohd-rf/vtext/releases/latest"

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
