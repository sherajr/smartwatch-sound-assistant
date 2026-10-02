package com.peaceantz.stagescope.input

import android.view.View

/**
 * The watch's "primary action" hand gesture -- a double pinch on Pixel Watch 3 and newer, Wear OS 7+ -- delivered to
 * whoever is subscribed while StageScope's window has focus and the screen is awake (never in ambient). StageScope
 * uses it for one thing: tapping the beat into the Analyzer's tap tempo.
 */
interface PrimaryGestureSource {
    /** True when this watch recognizes the gesture and the person has it switched on in the watch's settings. */
    fun isAvailable(): Boolean

    /**
     * Listens for the gesture while [view]'s window is focused, until the returned handle is closed; null when the gesture
     * isn't available. [onGesture] runs on the main thread with the moment of the gesture on the
     * `SystemClock.uptimeMillis()` clock (the one touch events use), see [PrimaryGestureTiming].
     */
    fun subscribe(view: View, gestureId: String, onGesture: (atUptimeMillis: Long) -> Unit): AutoCloseable?
}

/** A watch (or emulator) without the gesture. */
object NoPrimaryGesture : PrimaryGestureSource {
    override fun isAvailable(): Boolean = false
    override fun subscribe(view: View, gestureId: String, onGesture: (atUptimeMillis: Long) -> Unit): AutoCloseable? = null
}

/**
 * When a gesture happened. The Wear SDK stamps each gesture with an event time but does not say which clock it uses;
 * if that time reads as `uptimeMillis` (in milliseconds or nanoseconds) and is no older than [MAX_EVENT_AGE_MILLIS]
 * when it reaches us, it is used -- it marks the gesture itself, without the delivery delay a busy main thread adds.
 * Otherwise the arrival time stands in. Pure, see `PrimaryGestureTimingTest`.
 */
object PrimaryGestureTiming {
    /** Generous on purpose: a stamp that only sometimes passed would mix two clocks within one run of taps. */
    const val MAX_EVENT_AGE_MILLIS = 2_000L

    enum class Clock { EVENT_MILLIS, EVENT_NANOS, ARRIVAL }

    data class Resolved(val atUptimeMillis: Long, val clock: Clock)

    fun resolve(eventTime: Long, arrivalUptimeMillis: Long): Resolved {
        if (isPlausible(eventTime, arrivalUptimeMillis)) return Resolved(eventTime, Clock.EVENT_MILLIS)
        val fromNanos = eventTime / 1_000_000L
        if (isPlausible(fromNanos, arrivalUptimeMillis)) return Resolved(fromNanos, Clock.EVENT_NANOS)
        return Resolved(arrivalUptimeMillis, Clock.ARRIVAL)
    }

    private fun isPlausible(candidate: Long, arrival: Long): Boolean = arrival - candidate in 0..MAX_EVENT_AGE_MILLIS
}
