package ru.vtext.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexDiskLinksTest {

    @Test
    fun normalizesPublicFolderLink() {
        assertEquals(
            "https://disk.yandex.ru/d/yS2Mi2YlLpXnSw",
            YandexDiskLinks.normalize("https://disk.yandex.ru/d/yS2Mi2YlLpXnSw"),
        )
    }

    @Test
    fun acceptsLinkWithoutScheme() {
        assertEquals(
            "https://disk.yandex.ru/d/abc123",
            YandexDiskLinks.normalize("disk.yandex.ru/d/abc123"),
        )
    }

    @Test
    fun stripsQueryAndTrailingSlash() {
        assertEquals(
            "https://disk.yandex.ru/d/abc123",
            YandexDiskLinks.normalize("https://disk.yandex.ru/d/abc123/?w=1#top"),
        )
    }

    @Test
    fun acceptsYadiShortHost() {
        assertEquals(
            "https://disk.yandex.ru/d/xyz",
            YandexDiskLinks.normalize("https://yadi.sk/d/xyz"),
        )
    }

    @Test
    fun stripsSpacesInsertedByKeyboard() {
        assertEquals(
            "https://disk.yandex.ru/d/abc123",
            YandexDiskLinks.normalize("https://disk. yandex. ru/d/abc 123"),
        )
    }

    @Test
    fun rejectsSingleFileLink() {
        assertNull(YandexDiskLinks.normalize("https://disk.yandex.ru/i/abc123"))
    }

    @Test
    fun rejectsForeignHost() {
        assertNull(YandexDiskLinks.normalize("https://example.com/d/abc123"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(YandexDiskLinks.normalize(""))
        assertNull(YandexDiskLinks.normalize("привет"))
    }

    @Test
    fun detectsAudioExtensions() {
        assertTrue(AudioFormats.isAudio("01 - Глава.mp3"))
        assertTrue(AudioFormats.isAudio("BOOK.M4B"))
        assertTrue(AudioFormats.isAudio("track.flac"))
        assertFalse(AudioFormats.isAudio("cover.jpg"))
        assertFalse(AudioFormats.isAudio("notes.txt"))
    }
}
