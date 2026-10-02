package com.peaceantz.stagescope.shared.measurement

/**
 * Observation-timing based ring freshness. A pinned capture is *not* proof a tone is present: the
 * pin only protects the slot from eviction. This looks at when the tone was last actually observed
 * and whether it was merely restored from disk.
 */
object RingFreshnessClassifier {
    fun classify(
        restoredFromDisk: Boolean,
        lastObservedAgoMs: Long?,
        acquisitionActive: Boolean,
        liveGraceMs: Long,
        autoHoldMs: Long,
    ): RingFreshness {
        if (restoredFromDisk) return RingFreshness.RESTORED_NOT_REOBSERVED
        val ago = lastObservedAgoMs ?: return RingFreshness.STALE
        return when {
            acquisitionActive && ago <= liveGraceMs -> RingFreshness.LIVE_NOW
            ago <= autoHoldMs -> RingFreshness.RECENTLY_SEEN
            else -> RingFreshness.STALE
        }
    }
}

/** How old a snapshot is *now*, relative to when it was taken -- never relabelled as fresh. */
enum class SnapshotAge { JUST_TAKEN, RECENT, OLD }

object MeasurementQuality {
    const val JUST_TAKEN_MS = 20_000L
    const val RECENT_MS = 5 * 60_000L

    fun age(ctx: MeasurementContext, nowEpochMs: Long): SnapshotAge {
        val age = (nowEpochMs - ctx.capturedAtEpochMs).coerceAtLeast(0L)
        return when {
            age <= JUST_TAKEN_MS -> SnapshotAge.JUST_TAKEN
            age <= RECENT_MS -> SnapshotAge.RECENT
            else -> SnapshotAge.OLD
        }
    }

    fun ageSeconds(ctx: MeasurementContext, nowEpochMs: Long): Long =
        ((nowEpochMs - ctx.capturedAtEpochMs).coerceAtLeast(0L)) / 1000L

    /**
     * Deterministic caveats attached to every snapshot. These are statements about what the
     * measurement can and cannot show -- written so a model (or a person) cannot mistake a detector
     * heuristic for a diagnosis. They contain no findings and no recommendations.
     */
    fun notes(ctx: MeasurementContext): List<String> = buildList {
        if (ctx.device.isDemo) {
            add("DEMO: this is a synthetic test signal, not a real acoustic measurement.")
        }
        when (ctx.run.state) {
            RunState.NOT_STARTED, RunState.STOPPED ->
                add("Capture was not running when this snapshot was taken; values are the last captured readings, if any.")
            RunState.ERROR ->
                add("Capture reported an error (${ctx.run.errorMessage ?: "unknown"}); treat the values as unreliable.")
            RunState.PAUSED_FOR_SPEECH, RunState.RUNNING -> Unit
        }
        add("Single microphone on the wrist: it cannot tell which console channel, microphone, or loudspeaker produced a peak.")
        ctx.level?.let { level ->
            if (level.clippingNow || level.clippedSinceReset) {
                add("The clipping flag refers to the watch microphone input only; it does not show clipping at the console or in the PA.")
            }
            if (level.suspiciousSilence) {
                add("The input is digital silence, which usually means a muted or misrouted microphone; level readings are not meaningful.")
            }
        }
        if (ctx.rings.isNotEmpty()) {
            add("Ring prominence is detector contrast (dB above neighbouring bins), not a probability of acoustic feedback; a sustained musical note triggers it too.")
            val notNow = ctx.rings.count { it.freshness != RingFreshness.LIVE_NOW }
            if (notNow > 0) {
                add("$notNow of ${ctx.rings.size} captured ring(s) were not sounding at snapshot time (pinned, held, saved, or restored captures are history, not live evidence).")
            }
            if (ctx.rings.any { it.freshness == RingFreshness.RESTORED_NOT_REOBSERVED }) {
                add("Some pinned rings were restored from a previous session and have not been observed again in this one.")
            }
        }
        val cal = ctx.calibration
        if (cal != null && cal.applied) {
            add("Estimated SPL is one broadband offset for this input configuration; it is not a certified measurement and not A-weighted. Spectrum values stay raw dBFS.")
        } else {
            add("Levels are uncalibrated dBFS (relative to digital full scale), not sound pressure level.")
        }
        if (ctx.run.spectrumFrozen || ctx.spectrum?.held == true) {
            add("The spectrum was frozen at snapshot time; level and ring detection kept running, so they may be newer than the spectrum.")
        }
        if (ctx.config == null) {
            add("The negotiated input configuration was not available.")
        }
    }
}
