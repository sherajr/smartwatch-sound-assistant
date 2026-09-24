package com.peaceantz.stagescope.ui.rotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationMathTest {

    @Test
    fun `delta is additive and proportional to scroll pixels`() {
        val a = OrientationMath.applyDelta(0f, 10f)
        val b = OrientationMath.applyDelta(0f, 20f)
        assertEquals(a * 2f, b, 1e-3f)
    }

    @Test
    fun `the live unwrapped angle keeps growing past 360 with no wraparound`() {
        var angle = 0f
        repeat(1000) { angle = OrientationMath.applyDelta(angle, 100f) }
        assertTrue("continuous crown rotation must not be clamped", angle > 360f)
    }

    @Test
    fun `negative rotary pixels rotate the other way`() {
        val angle = OrientationMath.applyDelta(0f, -50f)
        assertTrue(angle < 0f)
    }

    @Test
    fun `normalize wraps into 0 until 360`() {
        assertEquals(0f, OrientationMath.normalize(0f), 1e-3f)
        assertEquals(0f, OrientationMath.normalize(360f), 1e-3f)
        assertEquals(10f, OrientationMath.normalize(370f), 1e-3f)
        assertEquals(350f, OrientationMath.normalize(-10f), 1e-3f)
        assertEquals(10f, OrientationMath.normalize(-350f), 1e-3f)
    }

    @Test
    fun `normalize is idempotent`() {
        val once = OrientationMath.normalize(725f)
        val twice = OrientationMath.normalize(once)
        assertEquals(once, twice, 1e-3f)
    }
}
