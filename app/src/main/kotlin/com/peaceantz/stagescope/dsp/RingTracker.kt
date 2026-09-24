package com.peaceantz.stagescope.dsp

import kotlin.math.abs

/** Centralized, tunable detection settings -- initial engineering values, not a universal claim. */
data class RingTrackerSettings(
    val minSignalGateDbfs: Double = -55.0,
    val contrastThresholdDb: Double = 10.0,
    val neighborHalfWidthBins: Int = 6,
    val matchToleranceBins: Int = 3,
    val confirmDurationMs: Long = 350,
    val maxDropoutMs: Long = 150,
    val autoHoldMs: Long = 20_000,
    val maxHistorySize: Int = 8,
    val emaAlpha: Double = 0.35,
)

enum class RingCaptureState { SEARCHING, DETECTING, LIVE, HELD, PINNED, PAUSED }

/** One confirmed capture. [frequencyHz] is frozen at confirmation; tracking may keep moving. */
data class RingCapture(
    val id: Long,
    val frequencyHz: Double,
    val confirmedAtMs: Long,
    var lastSeenAtMs: Long,
    var prominenceDb: Double,
    var pinned: Boolean = false,
) {
    fun stateAt(nowMillis: Long, autoHoldMs: Long, liveGraceMs: Long): RingCaptureState = when {
        pinned -> RingCaptureState.PINNED
        nowMillis - lastSeenAtMs <= liveGraceMs -> RingCaptureState.LIVE
        nowMillis - lastSeenAtMs <= autoHoldMs -> RingCaptureState.HELD
        else -> RingCaptureState.HELD // still shown as historical/expired-held until evicted
    }

    fun isExpired(nowMillis: Long, autoHoldMs: Long): Boolean =
        !pinned && nowMillis - lastSeenAtMs > autoHoldMs
}

/** A live, uncommitted observation trail -- becomes a [RingCapture] once it holds for long enough. */
private class Track(
    val id: Long,
    var smoothedFrequencyHz: Double,
    val firstSeenAtMs: Long,
    var lastSeenAtMs: Long,
    var prominenceDb: Double,
    var confirmed: Boolean = false,
    var linkedCaptureId: Long? = null,
)

data class RingSnapshot(
    val heroState: RingCaptureState,
    val heroCapture: RingCapture?,
    val detectingFrequencyHz: Double?,
    val otherCandidates: List<RingCapture>,
    val history: List<RingCapture>,
)

/**
 * Owns the full FIND A RING lifecycle: instantaneous spectral observations -> persistent tracks
 * (narrow frequency tolerance, brief-dropout tolerant) -> confirmed captures (frozen frequency,
 * auto-held, pinnable) -> a bounded history. This is analysis/session state, not page-local UI
 * state -- it must survive navigating between pages and must not be cleared by empty frames.
 */
class RingTracker(
    private val clock: MonotonicClock = SystemMonotonicClock,
    var settings: RingTrackerSettings = RingTrackerSettings(),
) {
    private val tracks = mutableListOf<Track>()
    private val captures = mutableListOf<RingCapture>()
    private var selectedCaptureId: Long? = null
    private var nextTrackId = 1L
    private var nextCaptureId = 1L
    private var acquisitionActive = true
    private var lastConfigFingerprint: String? = null

    fun setAcquisitionActive(active: Boolean) {
        acquisitionActive = active
        if (!active) tracks.clear()
    }

    /** Drops transient tracks (not yet confirmed evidence) on a config change; keeps history. */
    fun onConfigChanged(fingerprint: String) {
        if (fingerprint != lastConfigFingerprint) {
            tracks.clear()
            lastConfigFingerprint = fingerprint
        }
    }

    fun pin(captureId: Long) {
        captures.forEach { it.pinned = it.id == captureId }
        if (captures.any { it.id == captureId }) selectedCaptureId = captureId
    }

    fun unpin() {
        captures.forEach { it.pinned = false }
    }

    fun selectCapture(captureId: Long) {
        if (captures.any { it.id == captureId }) selectedCaptureId = captureId
    }

    /** Removes the currently selected capture (pinned or not) and releases the selection. */
    fun clearSelected() {
        val id = selectedCaptureId ?: return
        captures.removeAll { it.id == id }
        tracks.removeAll { it.linkedCaptureId == id }
        selectedCaptureId = null
    }

    fun clearAll() {
        captures.clear()
        tracks.clear()
        selectedCaptureId = null
    }

    fun reset() = clearAll()

    /** Recomputes hero/history state without processing a new frame (e.g. after pin/unpin/clear). */
    fun currentSnapshot(): RingSnapshot {
        val now = clock.nowMillis()
        val heroState = if (!acquisitionActive) RingCaptureState.PAUSED else resolveHeroState(now)
        return snapshot(now, heroState)
    }

    fun update(frame: SpectrumAnalyzer.Frame): RingSnapshot {
        val now = clock.nowMillis()

        if (!acquisitionActive) {
            return snapshot(now, RingCaptureState.PAUSED)
        }

        val overallLevel = frame.magnitudesDbfs.maxOrNull() ?: DbScale.FLOOR_DBFS
        val observations = if (overallLevel < settings.minSignalGateDbfs) {
            emptyList()
        } else {
            consolidatedPeaks(frame.magnitudesDbfs, frame.binWidthHz)
        }

        matchAndUpdateTracks(observations, frame.binWidthHz, now)
        pruneHistory()

        val heroState = resolveHeroState(now)
        return snapshot(now, heroState)
    }

    private fun snapshot(now: Long, heroState: RingCaptureState): RingSnapshot {
        val hero = selectedCaptureId?.let { id -> captures.find { it.id == id } }
        val detecting = tracks.filter { !it.confirmed }.maxByOrNull { it.prominenceDb }?.smoothedFrequencyHz
        val others = captures
            .filter { it.id != selectedCaptureId }
            .sortedByDescending { it.lastSeenAtMs }
            .take(2)
        val history = captures.sortedByDescending { it.lastSeenAtMs }
        return RingSnapshot(
            heroState = heroState,
            heroCapture = hero,
            detectingFrequencyHz = if (hero == null) detecting else null,
            otherCandidates = others,
            history = history,
        )
    }

    private fun resolveHeroState(now: Long): RingCaptureState {
        val hero = selectedCaptureId?.let { id -> captures.find { it.id == id } }
        if (hero != null) {
            return hero.stateAt(now, settings.autoHoldMs, liveGraceMs = settings.maxDropoutMs)
        }
        return if (tracks.any { !it.confirmed }) RingCaptureState.DETECTING else RingCaptureState.SEARCHING
    }

    private fun matchAndUpdateTracks(observations: List<Observation>, binWidthHz: Double, now: Long) {
        val toleranceHz = settings.matchToleranceBins * binWidthHz
        val unmatchedObservations = observations.toMutableList()

        // Match existing tracks to the nearest unclaimed observation within tolerance.
        for (track in tracks) {
            val best = unmatchedObservations.minByOrNull { abs(it.frequencyHz - track.smoothedFrequencyHz) }
            if (best != null && abs(best.frequencyHz - track.smoothedFrequencyHz) <= toleranceHz) {
                unmatchedObservations.remove(best)
                val alpha = settings.emaAlpha
                track.smoothedFrequencyHz = track.smoothedFrequencyHz * (1 - alpha) + best.frequencyHz * alpha
                track.lastSeenAtMs = now
                track.prominenceDb = best.prominenceDb

                if (!track.confirmed && now - track.firstSeenAtMs >= settings.confirmDurationMs) {
                    confirm(track, now)
                }
                track.linkedCaptureId?.let { capId ->
                    captures.find { it.id == capId }?.let {
                        it.lastSeenAtMs = now
                        it.prominenceDb = best.prominenceDb
                    }
                }
            }
        }

        // Drop tracks that have exceeded the allowed brief-dropout window.
        tracks.removeAll { now - it.lastSeenAtMs > settings.maxDropoutMs }

        // Anything left over starts a brand-new track.
        for (obs in unmatchedObservations) {
            tracks.add(Track(id = nextTrackId++, smoothedFrequencyHz = obs.frequencyHz, firstSeenAtMs = now, lastSeenAtMs = now, prominenceDb = obs.prominenceDb))
        }
    }

    private fun confirm(track: Track, now: Long) {
        track.confirmed = true
        val toleranceHz = settings.matchToleranceBins * 4.0 // dedup window a bit wider than live-match tolerance
        val existing = captures.minByOrNull { abs(it.frequencyHz - track.smoothedFrequencyHz) }
            ?.takeIf { abs(it.frequencyHz - track.smoothedFrequencyHz) <= toleranceHz }

        val capture = if (existing != null) {
            existing.lastSeenAtMs = now
            existing.prominenceDb = track.prominenceDb
            existing
        } else {
            val fresh = RingCapture(
                id = nextCaptureId++,
                frequencyHz = track.smoothedFrequencyHz,
                confirmedAtMs = now,
                lastSeenAtMs = now,
                prominenceDb = track.prominenceDb,
            )
            captures.add(fresh)
            fresh
        }
        track.linkedCaptureId = capture.id

        val current = selectedCaptureId?.let { id -> captures.find { it.id == id } }
        if (current == null || current.isExpired(now, settings.autoHoldMs)) {
            selectedCaptureId = capture.id
        }
    }

    private fun pruneHistory() {
        if (captures.size <= settings.maxHistorySize) return
        val evictable = captures.filter { !it.pinned && it.id != selectedCaptureId }
            .sortedBy { it.lastSeenAtMs }
        var overflow = captures.size - settings.maxHistorySize
        for (c in evictable) {
            if (overflow <= 0) break
            captures.remove(c)
            tracks.removeAll { it.linkedCaptureId == c.id }
            overflow--
        }
    }

    private data class Observation(val frequencyHz: Double, val prominenceDb: Double, val binIndex: Int)

    private fun consolidatedPeaks(magnitudesDbfs: DoubleArray, binWidthHz: Double): List<Observation> {
        val minBin = 2
        val maxBin = magnitudesDbfs.size - 2
        if (maxBin <= minBin) return emptyList()

        val raw = mutableListOf<Observation>()
        for (k in minBin..maxBin) {
            if (magnitudesDbfs[k] < magnitudesDbfs[k - 1] || magnitudesDbfs[k] < magnitudesDbfs[k + 1]) continue
            val lo = maxOf(minBin, k - settings.neighborHalfWidthBins)
            val hi = minOf(maxBin, k + settings.neighborHalfWidthBins)
            var sum = 0.0
            var count = 0
            for (j in lo..hi) {
                if (j == k) continue
                sum += magnitudesDbfs[j]
                count++
            }
            if (count == 0) continue
            val contrast = magnitudesDbfs[k] - (sum / count)
            if (contrast >= settings.contrastThresholdDb) {
                raw.add(Observation(k * binWidthHz, contrast, k))
            }
        }

        // Greedy non-max suppression: consolidate bins from the same windowed lobe into one peak,
        // while keeping separately resolvable simultaneous tones distinct.
        val toleranceBins = settings.matchToleranceBins
        val accepted = mutableListOf<Observation>()
        for (candidate in raw.sortedByDescending { it.prominenceDb }) {
            if (accepted.none { abs(it.binIndex - candidate.binIndex) <= toleranceBins }) {
                accepted.add(candidate)
            }
        }
        return accepted
    }
}
