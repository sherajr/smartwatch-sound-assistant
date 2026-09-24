package com.peaceantz.stagescope.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class DbScaleTest {

    @Test
    fun `silence returns the floor`() {
        assertEquals(DbScale.FLOOR_DBFS, DbScale.rmsDbfs(0.0), 1e-9)
        assertEquals(DbScale.FLOOR_DBFS, DbScale.peakDbfs(0.0), 1e-9)
    }

    @Test
    fun `half-amplitude sine matches the documented invariant`() {
        // mean(x^2) for x = 0.5*cos(theta) over a full period is (0.5^2)/2 = 0.125
        val meanSquare = 0.125
        val rmsDbfs = DbScale.rmsDbfs(meanSquare)
        assertEquals(-9.03, rmsDbfs, 0.01)

        val peakDbfs = DbScale.peakDbfs(0.5)
        assertEquals(-6.02, peakDbfs, 0.01)
    }

    @Test
    fun `values never report below the floor`() {
        assertEquals(DbScale.FLOOR_DBFS, DbScale.rmsDbfs(1e-30), 1e-6)
        assertEquals(DbScale.FLOOR_DBFS, DbScale.peakDbfs(1e-30), 1e-6)
    }
}
