package ru.vtext.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontSizeTest {

    @Test
    fun stepsFromTwelveToForty() {
        val ladder = (0..60).map { ReaderSettingsStore.normalizeSize(it) }.distinct()
        println("LADDER: $ladder")
        assertEquals(
            listOf(12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40),
            ladder,
        )
    }

    @Test
    fun defaultIsSystemStyle() {
        val ladder = (0..60).map { ReaderSettingsStore.normalizeSize(it) }.distinct()
        assertEquals(16, ladder[2])
        assertEquals(16, ReaderSettingsStore.DEFAULT_SIZE)
    }

    @Test
    fun oldValuesSnapToNearestStep() {
        assertEquals(16, ReaderSettingsStore.normalizeSize(15))
        assertEquals(18, ReaderSettingsStore.normalizeSize(17))
        assertEquals(12, ReaderSettingsStore.normalizeSize(3))
        assertEquals(40, ReaderSettingsStore.normalizeSize(60))
        assertEquals(26, ReaderSettingsStore.normalizeSize(25))
    }
}
