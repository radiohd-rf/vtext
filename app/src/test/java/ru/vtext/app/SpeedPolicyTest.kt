package ru.vtext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vtext.app.player.audio.SpeedPolicy

class SpeedPolicyTest {

    @Test
    fun `outside the fine zone the step is a tenth`() {
        assertEquals(0.10f, SpeedPolicy.stepFor(0.70f), 0.0001f)
        assertEquals(0.10f, SpeedPolicy.stepFor(1.30f), 0.0001f)
        assertEquals(0.01f, SpeedPolicy.stepFor(1.00f), 0.0001f)
    }

    @Test
    fun `fine zone is inclusive on both ends`() {
        assertTrue(SpeedPolicy.isFine(0.85f))
        assertTrue(SpeedPolicy.isFine(1.15f))
        assertFalse(SpeedPolicy.isFine(0.84f))
        assertFalse(SpeedPolicy.isFine(1.16f))
    }

    @Test
    fun `clamps to the supported range`() {
        assertEquals(0.50f, SpeedPolicy.snap(0.20f), 0.0001f)
        assertEquals(2.00f, SpeedPolicy.snap(2.80f), 0.0001f)
    }

    @Test
    fun `rounds to a tenth outside the fine zone`() {
        assertEquals(0.80f, SpeedPolicy.snap(0.84f), 0.0001f)
        assertEquals(0.70f, SpeedPolicy.snap(0.74f), 0.0001f)
        assertEquals(1.20f, SpeedPolicy.snap(1.16f), 0.0001f)
        assertEquals(1.60f, SpeedPolicy.snap(1.63f), 0.0001f)
    }

    @Test
    fun `rounds to a hundredth inside the fine zone`() {
        assertEquals(0.86f, SpeedPolicy.snap(0.864f), 0.0001f)
        assertEquals(1.04f, SpeedPolicy.snap(1.043f), 0.0001f)
        assertEquals(1.15f, SpeedPolicy.snap(1.15f), 0.0001f)
        assertEquals(0.85f, SpeedPolicy.snap(0.85f), 0.0001f)
    }

    @Test
    fun `stepping uses the step of the current value`() {
        assertEquals(1.01f, SpeedPolicy.next(1.00f, +1), 0.0001f)
        assertEquals(0.99f, SpeedPolicy.next(1.00f, -1), 0.0001f)
        assertEquals(1.40f, SpeedPolicy.next(1.30f, +1), 0.0001f)
        assertEquals(0.70f, SpeedPolicy.next(0.80f, -1), 0.0001f)
    }

    @Test
    fun `crossing the zone boundary jumps to the coarse grid`() {
        assertEquals(1.15f, SpeedPolicy.next(1.14f, +1), 0.0001f)
        assertEquals(1.20f, SpeedPolicy.next(1.15f, +1), 0.0001f)
        assertEquals(0.80f, SpeedPolicy.next(0.85f, -1), 0.0001f)
    }

    @Test
    fun `formats without trailing zeros`() {
        assertEquals("1.15×", SpeedPolicy.format(1.15f))
        assertEquals("0.7×", SpeedPolicy.format(0.70f))
        assertEquals("2×", SpeedPolicy.format(2.00f))
        assertEquals("1×", SpeedPolicy.format(1.00f))
        assertEquals("0.5×", SpeedPolicy.format(0.50f))
    }

    @Test
    fun `repeated snapping is stable`() {
        var value = 0.5f
        repeat(20) { value = SpeedPolicy.next(value, +1) }
        assertEquals(value, SpeedPolicy.snap(value), 0.0001f)
    }
}
