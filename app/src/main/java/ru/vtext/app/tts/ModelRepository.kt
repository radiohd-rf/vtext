package ru.vtext.app.tts

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Скачивание, распаковка и проверка локальных TTS-моделей. */
class ModelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "tts")

    fun modelDir(model: NeuralModel): File = File(modelsDir, model.id)

    fun isInstalled(model: NeuralModel): Boolean = model.requiredFiles.all {
        File(modelDir(model), it).isFile
    }

    suspend fun download(model: NeuralModel, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val archive = File(appContext.cacheDir, "tts-${model.id}.tar.bz2")
            try {
                downloadArchive(model, archive, onProgress)
                ensureActive()
                extract(archive, modelDir(model))
                if (!isInstalled(model)) {
                    modelDir(model).deleteRecursively()
                    throw IOException("Архив модели повреждён")
                }
            } finally {
                archive.delete()
            }
        }
    }

    fun delete(model: NeuralModel) {
        modelDir(model).deleteRecursively()
    }

    /** Импорт пользовательского словаря произношения Silero (текст `слово=произношение`). */
    suspend fun importDictionary(uri: android.net.Uri) {
        withContext(Dispatchers.IO) {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Не удалось открыть словарь")
            val target = File(modelsDir, SileroSpeechEngine.DICTIONARY_FILE)
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        }
    }

    /** Установка модели из локального zip-архива (для Silero, который не скачивается автоматически). */    suspend fun importArchive(model: NeuralModel, uri: android.net.Uri, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val staging = File(modelDir(model).parentFile, model.id + ".import")
            staging.deleteRecursively()
            staging.mkdirs()
            val root = staging.canonicalFile
            try {
                val resolver = appContext.contentResolver
                val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть архив")
                var entries = 0
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (entry.isDirectory || name.endsWith("/")) {
                            zip.closeEntry()
                            continue
                        }
                        val relative = name.substringAfter('/', missingDelimiterValue = name)
                        val output = File(staging, relative)
                        if (!output.canonicalPath.startsWith(root.path + File.separator)) {
                            throw IOException("Некорректный путь в архиве модели")
                        }
                        output.parentFile?.mkdirs()
                        output.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                        entries++
                        onProgress((entries % 100) / 100f)
                    }
                }
                if (entries == 0) throw IOException("Архив пуст")
                val target = modelDir(model)
                target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
                if (!isInstalled(model)) {
                    target.deleteRecursively()
                    throw IOException("В архиве нет файлов модели")
                }
                onProgress(1f)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    private suspend fun downloadArchive(model: NeuralModel, target: File, onProgress: (Float) -> Unit) {
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "vtext-android")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.approxBytes
            var copied = 0L
            var lastReportedPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
            if (copied == 0L) throw IOException("Пустой ответ сервера")
            onProgress(1f)
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, destination: File) {
        val staging = File(destination.parentFile, destination.name + ".tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingRoot = staging.canonicalFile
        try {
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val relative = entry.name
                            .replace('\\', '/')
                            .substringAfter('/', missingDelimiterValue = "")
                        if (relative.isEmpty()) continue
                        val output = File(staging, relative)
                        if (!output.canonicalPath.startsWith(stagingRoot.path + File.separator)) {
                            throw IOException("Некорректный путь в архиве модели")
                        }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
            }
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) {
                staging.copyRecursively(destination, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }
}
