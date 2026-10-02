package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.shared.measurement.CalibrationEvidence
import com.peaceantz.stagescope.shared.measurement.ConfigEvidence
import com.peaceantz.stagescope.shared.measurement.DeviceEvidence
import com.peaceantz.stagescope.shared.measurement.HistoryPoint
import com.peaceantz.stagescope.shared.measurement.LevelEvidence
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.MeasurementQuality
import com.peaceantz.stagescope.shared.measurement.RingBankEvidence
import com.peaceantz.stagescope.shared.measurement.RingEvidence
import com.peaceantz.stagescope.shared.measurement.RingFreshnessClassifier
import com.peaceantz.stagescope.shared.measurement.RunEvidence
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.measurement.UserContext
import com.peaceantz.stagescope.shared.util.StageScopeJson

/**
 * Builds the immutable [MeasurementContext] sent with a question, from the DSP state the person is
 * looking at -- never from a screenshot and never reconstructed. It is a pure function of its inputs
 * (including "now"), so a snapshot taken before dictation keeps the capture time it had at that
 * moment, and everything here is covered by unit tests.
 *
 * What it is careful about (see docs/MEASUREMENTS.md):
 *  - raw dBFS and calibrated Estimated SPL are different fields; the offset is applied to the RMS-derived
 *    reading only, never to the spectrum or to the sample peak;
 *  - a pinned, held or restored ring is *history*: [RingEvidence.freshness] is derived from when the tone
 *    was last actually observed, not from the pin;
 *  - ring prominence is detector contrast, passed through as such;
 *  - a stopped, paused or errored session says so, and says how old the readings are.
 */
object MeasurementSnapshotBuilder {
    /** A tone observed this recently, while capture runs, is "sounding now" (a little wider than one detector frame). */
    const val LIVE_GRACE_MS = 400L

    /** Comfortably under the Data Layer's per-message cap, leaving room for the text and the envelope. */
    const val MAX_CONTEXT_BYTES = 24_000

    data class Inputs(
        val snapshotId: String,
        val nowEpochMs: Long,
        val nowElapsedMs: Long,
        /** The Ring tracker's monotonic clock, in which capture timestamps are expressed. */
        val nowMonotonicMs: Long,
        val origin: SnapshotOrigin,
        val analyzer: AnalyzerSample?,
        val ring: RingSample?,
        val runState: RunState,
        val errorMessage: String?,
        val deviceModel: String?,
        val userContext: UserContext?,
        val history: List<HistoryPoint>,
    )

    fun build(i: Inputs): MeasurementContext {
        val reading = i.analyzer?.reading
        val config: CaptureConfig? = i.analyzer?.config ?: i.ring?.config
        val calibration = i.analyzer?.calibration
        val applied = reading?.isCalibrated == true && calibration != null
        val offset = if (applied) calibration!!.offsetDb else 0.0
        val spectrum = reading?.spectrum
        val running = i.runState == RunState.RUNNING

        val level = reading?.let {
            LevelEvidence(
                rmsDbfs = round1(it.rmsRawDbfs),
                peakDbfs = round1(it.peakDbfs),
                // The reading's MAX/AVG carry the offset for display; evidence is always raw.
                maxRmsDbfs = round1(it.maxDisplayDbfs - offset),
                sessionAverageRmsDbfs = round1(it.sessionAverageDisplayDbfs - offset),
                clippingNow = it.isClippingNow, clippedSinceReset = it.hasClippedEver, suspiciousSilence = it.isSuspiciousSilence,
            )
        }

        val rings = i.ring?.let { r ->
            val snap = r.snapshot
            snap.history.map { c ->
                val ago = if (c.restoredFromDisk) null else (i.nowMonotonicMs - c.lastSeenAtMs).coerceAtLeast(0L)
                RingEvidence(
                    captureId = c.id,
                    frequencyHz = round1(c.frequencyHz),
                    prominenceDb = round1(c.smoothedProminenceDb),
                    lastObservedAgoMs = ago,
                    trackingDurationMs = if (c.restoredFromDisk) 0L else (c.lastSeenAtMs - c.confirmedAtMs).coerceAtLeast(0L),
                    pinned = c.pinned,
                    restoredFromDisk = c.restoredFromDisk,
                    mostProminent = c.id == snap.mostProminentCaptureId,
                    freshness = RingFreshnessClassifier.classify(c.restoredFromDisk, ago, running, LIVE_GRACE_MS, r.autoHoldMs),
                    restoredSavedAtEpochMs = c.restoredWallClockMillis,
                )
            }.sortedWith(compareBy({ it.freshness.ordinal }, { -it.prominenceDb }))
        }.orEmpty()

        val base = MeasurementContext(
            snapshotId = i.snapshotId,
            capturedAtEpochMs = i.nowEpochMs,
            capturedAtElapsedMs = i.nowElapsedMs,
            origin = i.origin,
            device = DeviceEvidence(
                deviceModel = i.deviceModel,
                inputSourceLabel = config?.sourceLabel ?: "unknown",
                isDemo = config?.isDemo ?: reading?.isDemo ?: false,
                effects = config?.effects.orEmpty().map { e ->
                    "${e.name}: " + when {
                        !e.presentOnDevice -> "not present"
                        e.disabledSuccessfully -> "disabled"
                        else -> "present, could not be disabled"
                    }
                },
            ),
            run = RunEvidence(
                state = i.runState,
                spectrumFrozen = spectrum?.isHeld == true,
                activeSeconds = reading?.elapsedSeconds,
                keepAwakeRemainingSeconds = reading?.keepAwakeRemainingSeconds,
                errorMessage = i.errorMessage,
            ),
            config = config?.let {
                ConfigEvidence(
                    sampleRateHz = it.sampleRate, audioSourceLabel = it.sourceLabel, fingerprint = it.fingerprint(),
                    fftSize = spectrum?.fftSize ?: com.peaceantz.stagescope.dsp.SpectrumAnalyzer.DEFAULT_FFT_SIZE,
                    binWidthHz = spectrum?.binWidthHz ?: (it.sampleRate.toDouble() / com.peaceantz.stagescope.dsp.SpectrumAnalyzer.DEFAULT_FFT_SIZE),
                    nyquistHz = spectrum?.nyquistHz ?: (it.sampleRate / 2.0),
                )
            },
            level = level,
            calibration = reading?.let {
                if (applied) {
                    CalibrationEvidence(
                        applied = true, offsetDb = round1(calibration!!.offsetDb), referenceMeterReadingDb = round1(calibration.referenceMeterReadingDb),
                        calibratedAtEpochMs = calibration.timestampMillis, estimatedSplDb = round1(it.rmsDisplayDbfs),
                    )
                } else {
                    CalibrationEvidence(applied = false)
                }
            },
            spectrum = spectrum?.let { SpectrumEvidenceBuilder.build(it.magnitudesDbfs, it.binWidthHz, it.bands, it.isHeld) },
            rings = rings,
            ringBank = i.ring?.let {
                RingBankEvidence(
                    slotsUsed = it.snapshot.slotsUsed, slotsTotal = it.snapshot.slotsTotal, allSlotsPinned = it.snapshot.allSlotsPinned,
                    autoHoldSeconds = (it.autoHoldMs / 1000L).toInt(), acquisitionActive = running,
                )
            },
            history = i.history,
            userContext = i.userContext,
        )

        val extra = buildList {
            val sampleAt = i.analyzer?.atElapsedMs
            if (sampleAt != null && !running) {
                val ageS = ((i.nowElapsedMs - sampleAt).coerceAtLeast(0L)) / 1000L
                add("The readings are from about $ageS s before this snapshot (measurement was ${describe(i.runState)} at snapshot time).")
            }
        }
        return base.copy(notes = MeasurementQuality.notes(base) + extra)
    }

    /** True when there is something to look at; a snapshot with no level, spectrum or ring is not evidence of anything. */
    fun hasEvidence(ctx: MeasurementContext): Boolean = ctx.level != null || ctx.spectrum != null || ctx.rings.isNotEmpty()

    /**
     * Shrinks a context until it fits [maxBytes] once serialized, dropping the least valuable detail first
     * (history, then peak neighbours, then extra peaks, then bands). Always keeps level, calibration,
     * run state and ring evidence, and says what was dropped.
     */
    fun fitToBudget(ctx: MeasurementContext, maxBytes: Int = MAX_CONTEXT_BYTES): MeasurementContext {
        fun size(c: MeasurementContext) = StageScopeJson.encodeToString(MeasurementContext.serializer(), c).toByteArray(Charsets.UTF_8).size
        var current = ctx
        if (size(current) <= maxBytes) return current
        val dropped = ArrayList<String>()
        val steps: List<Pair<String, (MeasurementContext) -> MeasurementContext>> = listOf(
            "recent history" to { c -> c.copy(history = emptyList()) },
            "peak neighbour bins" to { c -> c.copy(spectrum = c.spectrum?.let { s -> s.copy(peaks = s.peaks.map { it.copy(neighbours = emptyList()) }) }) },
            "extra peaks" to { c -> c.copy(spectrum = c.spectrum?.let { s -> s.copy(peaks = s.peaks.take(4)) }) },
            "display bands" to { c -> c.copy(spectrum = c.spectrum?.let { s -> s.copy(bands = emptyList()) }) },
        )
        for ((label, change) in steps) {
            val next = change(current)
            if (next == current) continue
            current = next
            dropped += label
            if (size(current) <= maxBytes) break
        }
        return if (dropped.isEmpty()) current else current.copy(notes = current.notes + "To fit the message size, StageScope left out: ${dropped.joinToString()}.")
    }

    private fun describe(state: RunState) = when (state) {
        RunState.NOT_STARTED -> "not started"
        RunState.RUNNING -> "running"
        RunState.PAUSED_FOR_SPEECH -> "paused for voice"
        RunState.STOPPED -> "stopped"
        RunState.ERROR -> "in an error state"
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
