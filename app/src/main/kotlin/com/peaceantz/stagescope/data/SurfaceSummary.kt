package com.peaceantz.stagescope.data

import kotlinx.serialization.Serializable

/**
 * Small, versioned snapshot of "the last thing worth showing outside the app" -- the Tile and the
 * watch-face complication both read this instead of touching [SettingsRepository] or the live
 * capture session, so a provider process never needs the microphone. Written at meaningful events
 * only (Level stop, Ring capture/pin/unpin/clear) -- never per audio frame or per spectrum.
 */
@Serializable
data class SurfaceSummary(
    val version: Int = 1,
    val lastReading: LastReadingSummary? = null,
    val ringSummary: RingSummaryState? = null,
)

/** [timestampMillis] is wall-clock (`System.currentTimeMillis()`), not session-elapsed time. */
@Serializable
data class LastReadingSummary(
    val rmsDisplayDbfs: Double,
    val unitLabel: String,
    val timestampMillis: Long,
)

/** [timestampMillis] is wall-clock, converted from the capture's monotonic `confirmedAtMs`. */
@Serializable
data class RingSummaryState(
    val captureId: Long,
    val frequencyHz: Double,
    val timestampMillis: Long,
    val pinned: Boolean,
)
