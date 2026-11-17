package ru.vtext.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.vtext.app.data.TrackNaming

class TrackNamingTest {

    @Test
    fun `strips numeric track prefix`() {
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("03 - Понтий Пилат.mp3"))
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("03_Понтий Пилат.mp3"))
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("3. Понтий Пилат.mp3"))
    }

    @Test
    fun `strips chapter words with numbers`() {
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("Chapter 3 - Понтий Пилат.mp3"))
        assertEquals("Глава первая", TrackNaming.titleFromFileName("Глава первая.mp3"))
    }

    @Test
    fun `keeps name without prefix`() {
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("Понтий Пилат.mp3"))
        assertEquals("01", TrackNaming.titleFromFileName("01.mp3"))
    }

    @Test
    fun `replaces underscores and collapses spaces`() {
        assertEquals("Мой файл", TrackNaming.titleFromFileName("Мой_файл.mp3"))
        assertEquals("Понтий Пилат", TrackNaming.titleFromFileName("03 - Понтий_Пилат.mp3"))
    }

    @Test
    fun `orders tracks naturally`() {
        val names = listOf("10.mp3", "2.mp3", "1.mp3", "20.mp3")
        assertEquals(
            listOf("1.mp3", "2.mp3", "10.mp3", "20.mp3"),
            TrackNaming.naturalOrder(names),
        )
    }

    @Test
    fun `orders by title when numbers absent`() {
        val names = listOf("Глава б.mp3", "Глава а.mp3")
        assertEquals(listOf("Глава а.mp3", "Глава б.mp3"), TrackNaming.naturalOrder(names))
    }

    @Test
    fun `builds stored file name`() {
        assertEquals("01 - Понтий Пилат.mp3", TrackNaming.storedFileName(1, "03 - Понтий Пилат.mp3"))
        assertEquals("12 - Глава.flac", TrackNaming.storedFileName(12, "Глава.flac"))
    }
}
