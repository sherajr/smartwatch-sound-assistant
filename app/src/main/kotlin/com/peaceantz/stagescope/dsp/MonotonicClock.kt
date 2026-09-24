package com.peaceantz.stagescope.dsp

/** Source of monotonic milliseconds, injectable so track/hold timers are deterministically testable. */
interface MonotonicClock {
    fun nowMillis(): Long
}

/** `System.nanoTime()` is JVM-standard (no Android dependency) and immune to wall-clock changes. */
object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}
