package com.peaceantz.stagescope.input

import com.peaceantz.stagescope.input.PrimaryGestureTiming.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryGestureTimingTest {

    private val arrival = 5_000_000L // ~83 minutes of uptime

    @Test
    fun `an uptime stamp in milliseconds is used as the gesture's own time`() {
        val resolved = PrimaryGestureTiming.resolve(eventTime = arrival - 180, arrivalUptimeMillis = arrival)
        assertEquals(arrival - 180, resolved.atUptimeMillis)
        assertEquals(Clock.EVENT_MILLIS, resolved.clock)
    }

    @Test
    fun `an uptime stamp in nanoseconds is converted`() {
        val resolved = PrimaryGestureTiming.resolve(eventTime = (arrival - 95) * 1_000_000L, arrivalUptimeMillis = arrival)
        assertEquals(arrival - 95, resolved.atUptimeMillis)
        assertEquals(Clock.EVENT_NANOS, resolved.clock)
    }

    @Test
    fun `a stamp on some other clock falls back to the arrival time`() {
        val wallClock = 1_790_000_000_000L
        val resolved = PrimaryGestureTiming.resolve(eventTime = wallClock, arrivalUptimeMillis = arrival)
        assertEquals(arrival, resolved.atUptimeMillis)
        assertEquals(Clock.ARRIVAL, resolved.clock)
    }

    @Test
    fun `a stamp from the future or from long ago falls back to the arrival time`() {
        assertEquals(Clock.ARRIVAL, PrimaryGestureTiming.resolve(arrival + 1, arrival).clock)
        assertEquals(Clock.ARRIVAL, PrimaryGestureTiming.resolve(arrival - PrimaryGestureTiming.MAX_EVENT_AGE_MILLIS - 1, arrival).clock)
        assertEquals(Clock.EVENT_MILLIS, PrimaryGestureTiming.resolve(arrival - PrimaryGestureTiming.MAX_EVENT_AGE_MILLIS, arrival).clock)
    }

    @Test
    fun `the gesture is unavailable on a watch without it`() {
        assertEquals(false, NoPrimaryGesture.isAvailable())
    }
}
