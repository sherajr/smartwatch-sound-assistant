package com.peaceantz.stagescope.data

import kotlinx.serialization.Serializable

/**
 * A manual reference-alignment offset: "Estimated SPL = dBFS + offsetDb", stored alongside the
 * exact input configuration it was measured against so it can be invalidated if that changes.
 */
@Serializable
data class CalibrationState(
    val offsetDb: Double,
    val referenceMeterReadingDb: Double,
    val configFingerprint: String,
    val timestampMillis: Long,
)

/**
 * Selectable color theme. New field on [AppSettings] with a default -- an existing install's
 * settings.json simply lacks this key, and `Json { ignoreUnknownKeys = true }` plus this default
 * parameter means it deserializes to [PHOSPHOR_GREEN] (the original, only palette) automatically.
 */
@Serializable
enum class AppTheme { PHOSPHOR_GREEN, ICE_CYAN, WARM_AMBER, VIOLET, NIGHT_RED }

@Serializable
data class AppSettings(
    val demoModeEnabled: Boolean = false,
    val calibration: CalibrationState? = null,
    val dimAppearanceEnabled: Boolean = false,
    val ringAutoHoldSeconds: Int = 20,
    val theme: AppTheme = AppTheme.PHOSPHOR_GREEN,
)

/** A saved SPECTRUM snapshot with enough metadata to judge whether a comparison is valid. */
@Serializable
data class SpectrumSnapshot(
    val id: String,
    val name: String,
    val timestampMillis: Long,
    val sampleRate: Int,
    val fftSize: Int,
    val sourceLabel: String,
    val isDemo: Boolean,
    val magnitudesDbfs: List<Double>,
    val nyquistHz: Double,
    val binWidthHz: Double,
    val calibrationApplied: Boolean,
    val calibrationOffsetDb: Double? = null,
) {
    /** Snapshots are only directly comparable on identical scales: same rate, size, and offset. */
    fun isComparableTo(other: SpectrumSnapshot): Boolean =
        sampleRate == other.sampleRate &&
            fftSize == other.fftSize &&
            calibrationApplied == other.calibrationApplied &&
            (calibrationOffsetDb ?: 0.0) == (other.calibrationOffsetDb ?: 0.0)
}

const val MAX_SNAPSHOTS = 5

/** Pure fingerprint check so calibration invalidation logic is unit-testable without I/O. */
fun CalibrationState.isValidFor(currentFingerprint: String): Boolean = configFingerprint == currentFingerprint
