package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.EffectStatus
import com.peaceantz.stagescope.data.CalibrationState
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.dsp.RingSnapshot
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.analyzer.AnalyzerReading
import com.peaceantz.stagescope.ui.analyzer.RadialMapping
import com.peaceantz.stagescope.ui.analyzer.SpectrumDisplay

object TestMeasurements {
    const val SAMPLE_RATE = 48_000
    const val FFT = 4096
    const val BIN_WIDTH = SAMPLE_RATE.toDouble() / FFT

    fun config(demo: Boolean = false) = CaptureConfig(
        sampleRate = SAMPLE_RATE, audioSource = 9, sourceLabel = "Unprocessed", isUnprocessedSource = true,
        effects = listOf(
            EffectStatus("Noise Suppressor", presentOnDevice = true, disabledSuccessfully = true),
            EffectStatus("Automatic Gain Control", presentOnDevice = false, disabledSuccessfully = false),
        ),
        isDemo = demo,
    )

    /** A flat -80 dBFS floor with a loud narrow peak at ~2.1 kHz and a second one near 4.7 kHz. */
    fun magnitudes(): DoubleArray = DoubleArray(FFT / 2 + 1) { -80.0 }.also {
        it[180] = -20.0
        it[181] = -26.0
        it[179] = -27.0
        it[400] = -35.0
    }

    fun spectrum(mags: DoubleArray = magnitudes(), held: Boolean = false): SpectrumDisplay {
        val bands = RadialMapping.aggregateBands(mags)
        return SpectrumDisplay(
            sampleRate = SAMPLE_RATE, fftSize = FFT, magnitudesDbfs = mags, bands = bands,
            peakHoldBandsDbfs = DoubleArray(bands.size) { -90.0 }, nyquistHz = SAMPLE_RATE / 2.0, binWidthHz = BIN_WIDTH,
            cursorBandIndex = 10, isHeld = held,
        )
    }

    fun reading(
        rmsRaw: Double = -30.0, offset: Double = 0.0, calibrated: Boolean = false, held: Boolean = false, demo: Boolean = false,
        clipping: Boolean = false, mags: DoubleArray = magnitudes(),
    ) = AnalyzerReading(
        rmsDisplayDbfs = rmsRaw + offset, rmsRawDbfs = rmsRaw, peakDbfs = -12.5,
        maxDisplayDbfs = -20.0 + offset, sessionAverageDisplayDbfs = -34.0 + offset,
        elapsedSeconds = 42, isClippingNow = clipping, hasClippedEver = clipping,
        unitLabel = if (calibrated) "Estimated SPL" else "dBFS", isCalibrated = calibrated, isDemo = demo,
        isSuspiciousSilence = false, sourceLabel = "Unprocessed", keepAwakeRemainingSeconds = 78,
        spectrum = spectrum(mags, held),
    )

    fun calibration(offset: Double = 94.0) = CalibrationState(offsetDb = offset, referenceMeterReadingDb = 70.0, configFingerprint = "48000|9", timestampMillis = 1_700_000_000_000L)

    fun capture(
        id: Long, hz: Double, confirmedAt: Long, lastSeen: Long, prominence: Double = 20.0, pinned: Boolean = false,
        restored: Boolean = false, savedAt: Long? = null,
    ) = RingCapture(
        id = id, frequencyHz = hz, confirmedAtMs = confirmedAt, lastSeenAtMs = lastSeen, prominenceDb = prominence,
        smoothedProminenceDb = prominence, pinned = pinned, restoredFromDisk = restored, restoredWallClockMillis = savedAt,
    )

    fun ringSnapshot(captures: List<RingCapture>, mostProminent: Long? = captures.firstOrNull()?.id) = RingSnapshot(
        heroState = RingCaptureState.LIVE, heroCapture = null, detectingFrequencyHz = null, otherCandidates = emptyList(),
        history = captures, mostProminentCaptureId = mostProminent, slotsUsed = captures.size, slotsTotal = 5,
        allSlotsPinned = captures.size >= 5 && captures.all { it.pinned },
    )

    fun inputs(
        analyzer: AnalyzerSample? = null, ring: RingSample? = null, runState: RunState = RunState.RUNNING,
        nowEpoch: Long = 1_800_000_000_000L, nowElapsed: Long = 500_000L, nowMono: Long = 900_000L,
        origin: SnapshotOrigin = SnapshotOrigin.ASSISTANT, history: List<com.peaceantz.stagescope.shared.measurement.HistoryPoint> = emptyList(),
        error: String? = null,
    ) = MeasurementSnapshotBuilder.Inputs(
        snapshotId = "snap-1", nowEpochMs = nowEpoch, nowElapsedMs = nowElapsed, nowMonotonicMs = nowMono, origin = origin,
        analyzer = analyzer, ring = ring, runState = runState, errorMessage = error, deviceModel = "Pixel Watch 5",
        userContext = null, history = history,
    )

    fun analyzerSample(reading: AnalyzerReading, calibration: CalibrationState? = null, atElapsed: Long = 499_900L) =
        AnalyzerSample(reading, config(reading.isDemo), calibration, atElapsed)

    fun ringSample(snapshot: RingSnapshot, autoHoldMs: Long = 20_000L, atElapsed: Long = 499_900L) =
        RingSample(snapshot, config(), autoHoldMs, liveCount = 1, atElapsedMs = atElapsed)
}
