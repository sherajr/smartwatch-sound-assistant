package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.CalibrationState
import com.peaceantz.stagescope.dsp.RingSnapshot
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.ui.analyzer.AnalyzerReading

/** The Analyzer's latest published reading, with the configuration and calibration it was made under. */
class AnalyzerSample(
    val reading: AnalyzerReading,
    val config: CaptureConfig,
    val calibration: CalibrationState?,
    val atElapsedMs: Long,
)

/** The Ring bank's latest published state. [liveCount] is how many captures were being heard at publish time. */
class RingSample(
    val snapshot: RingSnapshot,
    val config: CaptureConfig?,
    val autoHoldMs: Long,
    val liveCount: Int,
    val atElapsedMs: Long,
)

/**
 * The most recent measurement state, published by the Analyzer and Ring ViewModels and read when the
 * person asks the assistant something. It holds *references to immutable values already on screen* --
 * nothing is copied or computed per audio block -- so keeping it costs nothing, and it is read
 * only at the moment of a question (never streamed anywhere). It lives at app level so it outlives the
 * ViewModels, and carries the session's run state so a stopped or paused measurement is described
 * honestly instead of looking live.
 */
class MeasurementHub(private val elapsedMs: () -> Long) {
    @Volatile var analyzer: AnalyzerSample? = null
        private set

    @Volatile var ring: RingSample? = null
        private set

    @Volatile var runState: RunState = RunState.NOT_STARTED
        private set

    @Volatile var errorMessage: String? = null
        private set

    val history = MeasurementHistoryBuffer()

    fun publishAnalyzer(reading: AnalyzerReading, config: CaptureConfig, calibration: CalibrationState?, recordHistory: Boolean) {
        val now = elapsedMs()
        analyzer = AnalyzerSample(reading, config, calibration, now)
        if (!recordHistory || runState != RunState.RUNNING) return
        val spectrum = reading.spectrum
        val top = spectrum?.bands?.maxByOrNull { it.magnitudeDbfs }
        history.add(
            atMs = now, rmsDbfs = reading.rmsRawDbfs, peakDbfs = reading.peakDbfs,
            topPeakHz = top?.let { it.peakBin * spectrum.binWidthHz }, topPeakDbfs = top?.magnitudeDbfs,
            liveRingCount = ring?.liveCount ?: 0,
        )
    }

    fun publishRing(snapshot: RingSnapshot, config: CaptureConfig?, autoHoldMs: Long, liveCount: Int) {
        ring = RingSample(snapshot, config, autoHoldMs, liveCount, elapsedMs())
    }

    fun onStatus(status: CaptureStatus) {
        errorMessage = null
        runState = when (status) {
            CaptureStatus.Idle -> RunState.NOT_STARTED
            is CaptureStatus.Running -> RunState.RUNNING
            is CaptureStatus.Paused -> RunState.PAUSED_FOR_SPEECH
            CaptureStatus.Stopped -> RunState.STOPPED
            CaptureStatus.PermissionDenied -> { errorMessage = "Microphone permission denied"; RunState.ERROR }
            is CaptureStatus.Unavailable -> { errorMessage = status.reason; RunState.ERROR }
            is CaptureStatus.Error -> { errorMessage = status.message; RunState.ERROR }
        }
    }
}
