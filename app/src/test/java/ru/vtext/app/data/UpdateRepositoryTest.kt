package ru.vtext.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun detectsNewerPatch() {
        assertTrue(UpdateRepository.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateRepository.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun olderReleaseIsNotNewer() {
        assertFalse(UpdateRepository.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun comparesMajorFirst() {
        assertTrue(UpdateRepository.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun missingPartCountsAsZero() {
        assertTrue(UpdateRepository.isNewer("1.1", "1.0.9"))
        assertFalse(UpdateRepository.isNewer("1.0", "1.0.1"))
    }

    @Test
    fun ignoresPreReleaseSuffix() {
        assertFalse(UpdateRepository.isNewer("1.0.0-beta", "1.0.0"))
    }
}
