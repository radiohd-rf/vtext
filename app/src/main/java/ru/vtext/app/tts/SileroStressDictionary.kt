package ru.vtext.app.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Словарь ударений RUAccent (MIT, 3,19 млн словоформ) в компактном виде.
 *
 * Формат `stress.bin`: заголовок `VSTR` + версия + количество, затем записи
 * по 9 байт — 64-битный хеш словоформы (FNV-1a, little-endian) и номер
 * символа, перед которым ставится «+». Файл читается через memory-mapped
 * буфер, поэтому в оперативной памяти занимает только страничный кеш.
 */
internal class SileroStressDictionary private constructor(
    private val channel: FileChannel,
    private val buffer: ByteBuffer,
    private val count: Int,
) {

    /** Индекс ударной гласной (позиция символа) или -1, если слова нет в словаре. */
    fun stressIndex(word: String): Int {
        if (count == 0) return -1
        val hash = fnv1a64(word)
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = buffer.getLong(HEADER_SIZE + middle * RECORD_SIZE)
            val comparison = java.lang.Long.compareUnsigned(value, hash)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return buffer.get(HEADER_SIZE + middle * RECORD_SIZE + 8).toInt() and 0xFF
            }
        }
        return -1
    }

    fun close() {
        runCatching { channel.close() }
    }

    companion object {
        private const val HEADER_SIZE = 12
        private const val RECORD_SIZE = 9
        private const val FNV_OFFSET = -3750763034362895579L // 0xCBF29CE484222325
        private const val FNV_PRIME = 0x100000001B3L

        /** Открывает словарь, если файл на месте и формат совпадает. */
        fun open(file: File): SileroStressDictionary? = runCatching {
            if (!file.isFile || file.length() < HEADER_SIZE) return null
            val stream = RandomAccessFile(file, "r")
            val channel = stream.channel
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                .order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            mapped.get(magic, 0, 4)
            if (String(magic) != "VSTR") {
                channel.close()
                return null
            }
            val version = mapped.getInt(4)
            val count = mapped.getInt(8)
            if (version != 2 || count <= 0) {
                channel.close()
                return null
            }
            SileroStressDictionary(channel, mapped, count)
        }.getOrNull()

        /** FNV-1a по UTF-8: тот же алгоритм используется при сборке словаря. */
        fun fnv1a64(word: String): Long {
            var hash = FNV_OFFSET
            for (byte in word.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME
            }
            return hash
        }
    }
}
