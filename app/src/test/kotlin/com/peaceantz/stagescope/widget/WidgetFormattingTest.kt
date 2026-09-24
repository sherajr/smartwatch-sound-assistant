package com.peaceantz.stagescope.widget

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFormattingTest {

    private val zone = ZoneId.systemDefault()

    @Test
    fun `formatWhen shows a clock time for a same-day timestamp`() {
        val now = ZonedDateTime.of(2026, 9, 23, 15, 0, 0, 0, zone)
        val then = ZonedDateTime.of(2026, 9, 23, 10, 42, 0, 0, zone)
        val result = formatWhen(then.toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        assertTrue("expected a clock time like '10:42 AM', got '$result'", result.contains(":"))
        assertTrue(result.contains("10:42"))
    }

    @Test
    fun `formatWhen shows a date, not a clock time, for an older timestamp`() {
        val now = ZonedDateTime.of(2026, 9, 23, 15, 0, 0, 0, zone)
        val then = ZonedDateTime.of(2026, 9, 20, 10, 42, 0, 0, zone)
        val result = formatWhen(then.toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        assertEquals("Sep 20", result)
    }

    @Test
    fun `formatFrequencyCompact stays under the 7-char SHORT_TEXT limit`() {
        val samples = listOf(60.0, 326.0, 999.0, 1000.0, 2150.0, 9949.0, 12000.0)
        for (hz in samples) {
            val result = formatFrequencyCompact(hz)
            assertTrue("'$result' (from $hz Hz) is ${result.length} chars, must be < 7", result.length < 7)
        }
    }

    @Test
    fun `formatFrequencyCompact formats below and above 1kHz distinctly`() {
        assertEquals("326Hz", formatFrequencyCompact(326.0))
        assertEquals("2.2kHz", formatFrequencyCompact(2150.0))
        assertEquals("12kHz", formatFrequencyCompact(12000.0))
    }

    @Test
    fun `formatFrequencyReadable is roomier and unit-spaced`() {
        assertEquals("326 Hz", formatFrequencyReadable(326.0))
        assertEquals("2.15 kHz", formatFrequencyReadable(2150.0))
    }

    @Test
    fun `formatDbCompact always shows an explicit sign`() {
        assertEquals("-58.0", formatDbCompact(-58.0))
        assertEquals("+3.2", formatDbCompact(3.2))
        assertEquals("+0.0", formatDbCompact(0.0))
    }
}
