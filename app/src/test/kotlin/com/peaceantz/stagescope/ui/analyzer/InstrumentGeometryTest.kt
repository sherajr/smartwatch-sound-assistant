package com.peaceantz.stagescope.ui.analyzer

import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentGeometryTest {

    @Test
    fun `radii nest strictly from outer meter down to the center safe zone`() {
        for (usableRadius in listOf(40f, 80f, 96f, 113.5f, 120f, 140f)) {
            val r = InstrumentGeometry.compute(usableRadius)
            assertTrue("meterOuter must not exceed usableRadius", r.meterOuter <= usableRadius)
            assertTrue("meterOuter > meterInner", r.meterOuter > r.meterInner)
            assertTrue("meterInner > spectrumOuter (visible gap)", r.meterInner > r.spectrumOuter)
            assertTrue("spectrumOuter > spectrumInner", r.spectrumOuter > r.spectrumInner)
            assertTrue("spectrumInner > centerSafeRadius (own separation)", r.spectrumInner > r.centerSafeRadius)
            assertTrue("centerSafeRadius stays positive", r.centerSafeRadius > 0f)
        }
    }

    @Test
    fun `outer meter boundary lands in the suggested 95-98 percent range`() {
        val r = InstrumentGeometry.compute(100f)
        assertTrue(r.meterOuter in 95f..98f)
    }

    @Test
    fun `spectrum inner boundary lands in the suggested 60-65 percent range`() {
        val r = InstrumentGeometry.compute(100f)
        assertTrue(r.spectrumInner in 60f..65f)
    }

    @Test
    fun `geometry scales linearly with usableRadius`() {
        val small = InstrumentGeometry.compute(50f)
        val large = InstrumentGeometry.compute(100f)
        assertTrue(kotlin.math.abs(large.meterOuter - small.meterOuter * 2f) < 1e-3f)
        assertTrue(kotlin.math.abs(large.spectrumInner - small.spectrumInner * 2f) < 1e-3f)
    }
}
