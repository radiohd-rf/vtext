package ru.vtext.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontSizeTest {

    @Test
    fun nineStepsFromEightToTwentyFour() {
        val ladder = (0..40).map { ReaderSettingsStore.normalizeSize(it) }.distinct()
        println("LADDER: $ladder")
        assertEquals(listOf(8, 10, 12, 14, 16, 18, 20, 22, 24), ladder)
    }

    @Test
    fun middleStepIsSystemDefault() {
        val ladder = (0..40).map { ReaderSettingsStore.normalizeSize(it) }.distinct()
        assertEquals(16, ladder[4])
        assertEquals(16, ReaderSettingsStore.DEFAULT_SIZE)
    }

    @Test
    fun oldValuesSnapToNearestStep() {
        assertEquals(16, ReaderSettingsStore.normalizeSize(15))
        assertEquals(18, ReaderSettingsStore.normalizeSize(17))
        assertEquals(8, ReaderSettingsStore.normalizeSize(3))
        assertEquals(24, ReaderSettingsStore.normalizeSize(30))
    }
}
