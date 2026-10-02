package com.peaceantz.stagescope.shared.measurement

import kotlinx.serialization.Serializable

/**
 * An immutable, versioned record of what StageScope actually measured, built from real DSP state
 * (never a screenshot, never reconstructed by a model). It is deep-copied at a snapshot boundary:
 * nothing here references a live array, capture, or ViewModel, and [capturedAtEpochMs] is never
 * relabeled -- a snapshot taken before dictation still says it was taken before dictation.
 *
 * Deliberate separations (see docs/MEASUREMENTS.md and docs/AI_ARCHITECTURE.md):
 * - raw dBFS ([LevelEvidence]) vs calibrated Estimated SPL ([CalibrationEvidence]) are different
 *   objects; the calibration offset applies to RMS-derived readings only, never to [SpectrumEvidence];
 * - a ring that is pinned or restored from disk is *history*, not proof the tone is sounding now --
 *   [RingEvidence.freshness] is derived from observation timing, not from the pinned flag;
 * - [RingEvidence.prominenceDb] is detector contrast (dB above neighbouring bins), not a calibrated
 *   probability of acoustic feedback.
 */
@Serializable
data class MeasurementContext(
    val schemaVersion: Int = SCHEMA_VERSION,
    val snapshotId: String,
    /** Wall-clock when the snapshot was taken (informational; never used to order edits). */
    val capturedAtEpochMs: Long,
    /** Monotonic time at capture (`SystemClock.elapsedRealtime()`); used for ages within one boot. */
    val capturedAtElapsedMs: Long,
    val origin: SnapshotOrigin,
    val device: DeviceEvidence,
    val run: RunEvidence,
    val config: ConfigEvidence? = null,
    val level: LevelEvidence? = null,
    val calibration: CalibrationEvidence? = null,
    val spectrum: SpectrumEvidence? = null,
    val rings: List<RingEvidence> = emptyList(),
    val ringBank: RingBankEvidence? = null,
    val history: List<HistoryPoint> = emptyList(),
    val userContext: UserContext? = null,
    /** Deterministic measurement-quality notes (see [MeasurementQuality]) -- caveats, not findings. */
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
enum class SnapshotOrigin { ANALYZER, RING, ASSISTANT, MANUAL }

@Serializable
data class DeviceEvidence(
    val deviceModel: String? = null,
    val inputSourceLabel: String,
    /** Synthetic Demo signal -- never a real acoustic measurement. */
    val isDemo: Boolean,
    /** Platform effects present/disabled on the input path (AGC, noise suppressor, echo canceller). */
    val effects: List<String> = emptyList(),
)

@Serializable
enum class RunState { NOT_STARTED, RUNNING, PAUSED_FOR_SPEECH, STOPPED, ERROR }

@Serializable
data class RunEvidence(
    val state: RunState,
    /** User pressed Freeze: the spectrum is held; level and Ring detection keep running. */
    val spectrumFrozen: Boolean = false,
    val activeSeconds: Long? = null,
    val keepAwakeRemainingSeconds: Int? = null,
    val errorMessage: String? = null,
)

/** The negotiated input configuration and the FFT built on it -- never an assumption. */
@Serializable
data class ConfigEvidence(
    val sampleRateHz: Int,
    val audioSourceLabel: String,
    /** Same fingerprint the calibration offset is tied to (`sampleRate|audioSource`). */
    val fingerprint: String,
    val fftSize: Int,
    val binWidthHz: Double,
    val nyquistHz: Double,
    val window: String = "Hann",
    val overlap: Double = 0.5,
)

@Serializable
data class LevelEvidence(
    /** Raw RMS dBFS = 10*log10(mean square), floor -90. Always uncalibrated. */
    val rmsDbfs: Double,
    /** Sample peak dBFS = 20*log10(max |x|), floor -90. Not a true-peak measurement. */
    val peakDbfs: Double,
    val maxRmsDbfs: Double? = null,
    /** Session energy-average (energy accumulated, converted to dB once), raw dBFS. */
    val sessionAverageRmsDbfs: Double? = null,
    val clippingNow: Boolean = false,
    val clippedSinceReset: Boolean = false,
    val suspiciousSilence: Boolean = false,
)

/**
 * Estimated-SPL metadata. [applied] says whether the live reading was offset-corrected;
 * [estimatedSplDb] is `rmsDbfs + offset` only when applied. Never a certified measurement, never
 * dBA, and not applied to any spectrum bin.
 */
@Serializable
data class CalibrationEvidence(
    val applied: Boolean,
    val offsetDb: Double? = null,
    val referenceMeterReadingDb: Double? = null,
    val calibratedAtEpochMs: Long? = null,
    val estimatedSplDb: Double? = null,
)

@Serializable
data class SpectrumEvidence(
    /** Raw one-sided amplitude dBFS (Hann, 50% overlap) -- never SPL, never offset-corrected. */
    val scale: String = "one-sided amplitude dBFS (raw, uncalibrated)",
    /** Log-spaced display bands, each the MAX of its raw bins (a narrow peak keeps its true level). */
    val bands: List<BandEvidence>,
    /** Strongest local peaks at native FFT resolution with their neighbouring bins. */
    val peaks: List<PeakDetail> = emptyList(),
    val noiseFloorEstimateDbfs: Double? = null,
    /** True when the spectrum shown was held by Freeze at capture time (so it may be older than the level). */
    val held: Boolean = false,
)

@Serializable
data class BandEvidence(
    val loHz: Double,
    val hiHz: Double,
    /** Frequency of the raw bin that produced [maxDbfs]. */
    val peakHz: Double,
    val maxDbfs: Double,
)

@Serializable
data class PeakDetail(
    val frequencyHz: Double,
    val magnitudeDbfs: Double,
    /** Peak minus the mean of its neighbourhood (detector contrast, dB). */
    val contrastDb: Double,
    /** Native-resolution bins around the peak: (Hz, dBFS). Bounded length. */
    val neighbours: List<BinPoint> = emptyList(),
)

@Serializable
data class BinPoint(val hz: Double, val dbfs: Double)

@Serializable
enum class RingFreshness {
    /** Observed within the live-grace window while capture was running. */
    LIVE_NOW,
    /** Seen recently (inside the auto-hold window) but not at this instant. */
    RECENTLY_SEEN,
    /** Not observed for longer than the auto-hold window: historical. */
    STALE,
    /** Restored from a previous session and not yet re-observed live: saved history only. */
    RESTORED_NOT_REOBSERVED,
}

@Serializable
data class RingEvidence(
    val captureId: Long,
    val frequencyHz: Double,
    /** Smoothed detector contrast in dB. Not a probability of feedback. */
    val prominenceDb: Double,
    /** Age of the last real observation at snapshot time; null when restored and never re-observed. */
    val lastObservedAgoMs: Long?,
    /** Confirmed-to-last-seen span: how long this tone has actually persisted. */
    val trackingDurationMs: Long,
    val pinned: Boolean,
    val restoredFromDisk: Boolean,
    val mostProminent: Boolean,
    val freshness: RingFreshness,
    /** Wall-clock the capture was saved, for restored captures only. */
    val restoredSavedAtEpochMs: Long? = null,
)

@Serializable
data class RingBankEvidence(
    val slotsUsed: Int,
    val slotsTotal: Int,
    val allSlotsPinned: Boolean,
    val autoHoldSeconds: Int,
    val acquisitionActive: Boolean,
)

/** One low-rate sample of the recent past (bounded buffer), offsets negative from the snapshot. */
@Serializable
data class HistoryPoint(
    val offsetMs: Long,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val topPeakHz: Double? = null,
    val topPeakDbfs: Double? = null,
    val liveRingCount: Int = 0,
)

/** Optional description the user (or show profile) supplies -- context the DSP cannot know. */
@Serializable
data class UserContext(
    val productionName: String? = null,
    val venue: String? = null,
    val equipment: String? = null,
    val signalPath: String? = null,
    val micPosition: String? = null,
    val performanceLabel: String? = null,
)
