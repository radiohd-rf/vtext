package ru.vtext.app.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SileroStressDictionaryTest {

    @Test
    fun hashesMatchExporter() {
        // Значения посчитаны тем же FNV-1a в tools/silero/export_silero.py.
        assertEquals(8057107934112161451L, SileroStressDictionary.fnv1a64("главу"))
        assertEquals(2006539529158035231L, SileroStressDictionary.fnv1a64("привет"))
        assertEquals(5430289805732001354L, SileroStressDictionary.fnv1a64("ежик"))
    }

    @Test
    fun findsStressIndexInBinaryDictionary() {
        val file = File.createTempFile("stress", ".bin")
        try {
            val records = listOf(
                "главу" to 4,
                "привет" to 5,
                "ежик" to 1,
            ).map { (word, index) -> SileroStressDictionary.fnv1a64(word) to index }
                .sortedWith { first, second ->
                    java.lang.Long.compareUnsigned(first.first, second.first)
                }

            val body = ByteBuffer.allocate(12 + records.size * 9).order(ByteOrder.LITTLE_ENDIAN)
            body.put("VSTR".toByteArray())
            body.putInt(2)
            body.putInt(records.size)
            records.forEach { (hash, index) ->
                body.putLong(hash)
                body.put(index.toByte())
            }
            file.writeBytes(body.array())

            val dictionary = SileroStressDictionary.open(file)
            assertNotNull(dictionary)
            assertEquals(5, dictionary!!.stressIndex("привет"))
            assertEquals(4, dictionary.stressIndex("главу"))
            assertEquals(1, dictionary.stressIndex("ежик"))
            assertEquals(-1, dictionary.stressIndex("словарь"))
            dictionary.close()
        } finally {
            file.delete()
        }
    }
}
