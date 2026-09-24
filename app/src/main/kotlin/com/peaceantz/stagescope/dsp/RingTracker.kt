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
    /** Bounded capture bank size -- up to this many distinct confirmed frequencies at once. */
    val maxCaptures: Int = 5,
    val emaAlpha: Double = 0.35,
    /**
     * Dedup tolerance for a recurring tone re-confirming into the SAME capture record, expressed in
     * bins and converted to Hz via the frame's actual `binWidthHz` at confirm time -- resolution
     * aware, not a fixed Hz-per-bin assumption. Wider than [matchToleranceBins] (the live frame-to-
     * frame match tolerance) since a recurring tone may drift slightly between separate
     * confirmations, but still tight enough not to merge two genuinely distinct, resolvable tones.
     */
    val dedupToleranceBins: Int = 6,
    /**
     * Minimum prominence advantage (dB) a new confirmed candidate needs over the weakest eligible
     * unpinned bank member before it may evict it, once all [maxCaptures] slots are full and none
     * are expired -- this is what "sufficiently stronger" means, and it exists specifically to
     * avoid constant churn between two similar-strength tones trading the last slot.
     */
    val replacementMarginDb: Double = 6.0,
    /**
     * Smoothed-prominence margin (dB) the most-prominent-ring selector requires to switch
     * immediately to a clearly stronger candidate; below this margin, a candidate must lead for
     * [prominenceDwellMs] before it takes over (hysteresis against near-equal candidates flapping).
     */
    val prominenceSwitchMarginDb: Double = 4.0,
    /** Dwell time (ms) a near-equal candidate must remain the top live candidate before the
     *  most-prominent-ring selection switches to it. */
    val prominenceDwellMs: Long = 600,
    /** EMA smoothing factor applied to each capture's scored prominence (distinct from the raw
     *  per-frame contrast used only for confirmation gating) -- keeps the most-prominent-ring
     *  selector from reacting to single-frame noise. */
    val prominenceEmaAlpha: Double = 0.3,
)

enum class RingCaptureState { SEARCHING, DETECTING, LIVE, HELD, EXPIRED, PINNED, PAUSED }

/** One confirmed capture. [frequencyHz] is frozen at confirmation; tracking may keep moving. */
data class RingCapture(
    val id: Long,
    val frequencyHz: Double,
    val confirmedAtMs: Long,
    var lastSeenAtMs: Long,
    var prominenceDb: Double,
    /** EMA-smoothed prominence used for eviction/most-prominent scoring; starts at [prominenceDb]. */
    var smoothedProminenceDb: Double = prominenceDb,
    var pinned: Boolean = false,
    /**
     * True for a capture restored from disk (a previous app run) that has not yet been matched by
     * a live track again this session -- the UI marks these "Saved" rather than implying they are
     * currently being heard. Cleared the moment this capture is updated by a real detection.
     */
    var restoredFromDisk: Boolean = false,
    /**
     * Wall-clock save time for a restored capture, display-only. [confirmedAtMs]/[lastSeenAtMs] are
     * always in THIS process's monotonic clock, which has no relationship to a previous run's --
     * this field is the only trustworthy "when" for a restored, not-yet-reconfirmed capture.
     */
    val restoredWallClockMillis: Long? = null,
) {
    /** Per-capture state: pinned always wins, then live/held/expired by recency of [lastSeenAtMs]. */
    fun stateAt(nowMillis: Long, autoHoldMs: Long, liveGraceMs: Long): RingCaptureState = when {
        pinned -> RingCaptureState.PINNED
        nowMillis - lastSeenAtMs <= liveGraceMs -> RingCaptureState.LIVE
        nowMillis - lastSeenAtMs <= autoHoldMs -> RingCaptureState.HELD
        else -> RingCaptureState.EXPIRED
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
    /**
     * The capture id of the strongest currently-detected (LIVE) confirmed ring, chosen purely by
     * smoothed spectral prominence with hysteresis -- entirely independent of [heroCapture]
     * (the user's manual selection) and of any capture's pinned flag. Sticky: when nothing is
     * currently LIVE, this keeps returning the last id it held (the capture itself may since have
     * gone HELD/EXPIRED), so callers can treat it as "last known most-prominent ring" and check
     * that capture's own state to tell live from cached. Null only once nothing has ever qualified,
     * or after that capture was removed by a clear/eviction.
     */
    val mostProminentCaptureId: Long?,
    val slotsUsed: Int,
    val slotsTotal: Int,
    /** True when the bank is full AND every slot is pinned -- nothing can be evicted automatically. */
    val allSlotsPinned: Boolean,
)

/**
 * Owns the full Ring-finder lifecycle: instantaneous spectral observations -> persistent tracks
 * (narrow frequency tolerance, brief-dropout tolerant) -> confirmed captures (frozen frequency,
 * auto-held, independently pinnable, bounded five-slot bank) -> a most-prominent-ring selector.
 * This is analysis/session state, not page-local UI state -- it must survive navigating between
 * pages and must not be cleared by empty frames.
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

    private var mostProminentId: Long? = null
    private var prominenceCandidateId: Long? = null
    private var prominenceCandidateSinceMs: Long = 0L

    fun setAcquisitionActive(active: Boolean) {
        acquisitionActive = active
        if (!active) tracks.clear()
    }

    /** Drops transient tracks (not yet confirmed evidence) on a config change; keeps the bank. */
    fun onConfigChanged(fingerprint: String) {
        if (fingerprint != lastConfigFingerprint) {
            tracks.clear()
            lastConfigFingerprint = fingerprint
        }
    }

    /** Pins [captureId] only -- every other capture's pinned state is left untouched. */
    fun pin(captureId: Long) = setPinned(captureId, true)

    /** Unpins [captureId] only -- every other capture's pinned state is left untouched. */
    fun unpin(captureId: Long) = setPinned(captureId, false)

    fun setPinned(captureId: Long, pinned: Boolean) {
        captures.find { it.id == captureId }?.pinned = pinned
    }

    fun selectCapture(captureId: Long) {
        if (captures.any { it.id == captureId }) selectedCaptureId = captureId
    }

    /** Removes the currently selected capture (pinned or not) and releases the selection. */
    fun clearSelected() {
        val id = selectedCaptureId ?: return
        removeCaptureInternal(id)
    }

    /** Removes every unpinned capture, leaving pinned ones (and the current selection, if pinned). */
    fun clearUnpinned() {
        captures.filter { !it.pinned }.map { it.id }.forEach { removeCaptureInternal(it) }
    }

    fun clearAll() {
        captures.clear()
        tracks.clear()
        selectedCaptureId = null
        mostProminentId = null
        prominenceCandidateId = null
    }

    fun reset() = clearAll()

    /**
     * Restores a capture persisted from a previous run (pinned metadata only -- no audio is ever
     * stored). Marked [RingCapture.restoredFromDisk] until it's matched by a live detection again.
     * Silently ignored if [id] is already present or the bank is already full -- restore happens
     * once at startup, before any live confirmation could plausibly claim that id or slot.
     */
    fun restoreCapture(id: Long, frequencyHz: Double, savedAtWallClockMillis: Long) {
        if (captures.any { it.id == id }) return
        if (captures.size >= settings.maxCaptures) return
        val now = clock.nowMillis()
        captures.add(
            RingCapture(
                id = id,
                frequencyHz = frequencyHz,
                confirmedAtMs = now,
                lastSeenAtMs = now,
                prominenceDb = 0.0,
                smoothedProminenceDb = 0.0,
                pinned = true,
                restoredFromDisk = true,
                restoredWallClockMillis = savedAtWallClockMillis,
            )
        )
        if (id >= nextCaptureId) nextCaptureId = id + 1
    }

    /** Recomputes hero/history/most-prominent state without processing a new frame. */
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

        val heroState = resolveHeroState(now)
        return snapshot(now, heroState)
    }

    private fun snapshot(now: Long, heroState: RingCaptureState): RingSnapshot {
        updateMostProminent(now)
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
            mostProminentCaptureId = mostProminentId,
            slotsUsed = captures.size,
            slotsTotal = settings.maxCaptures,
            allSlotsPinned = captures.size >= settings.maxCaptures && captures.all { it.pinned },
        )
    }

    private fun resolveHeroState(now: Long): RingCaptureState {
        val hero = selectedCaptureId?.let { id -> captures.find { it.id == id } }
        if (hero != null) {
            return hero.stateAt(now, settings.autoHoldMs, liveGraceMs = settings.maxDropoutMs)
        }
        return if (tracks.any { !it.confirmed }) RingCaptureState.DETECTING else RingCaptureState.SEARCHING
    }

    /**
     * Recomputes which currently-LIVE confirmed capture is most prominent, with hysteresis. Purely
     * a function of smoothed spectral prominence among LIVE captures -- never influenced by
     * [selectedCaptureId] or a capture's pinned flag, so manual selection/pinning never redefines
     * this, and an old pinned ring never outranks a currently stronger unpinned one. When nothing
     * is currently LIVE, [mostProminentId] is left as-is (sticky "last known"), not cleared.
     */
    private fun updateMostProminent(now: Long) {
        val liveCaptures = captures.filter {
            it.stateAt(now, settings.autoHoldMs, settings.maxDropoutMs) == RingCaptureState.LIVE
        }
        val top = liveCaptures.maxByOrNull { it.smoothedProminenceDb }
        if (top == null) {
            prominenceCandidateId = null
            return
        }
        if (top.id == mostProminentId) {
            prominenceCandidateId = null
            return
        }
        val currentProminence = captures.find { it.id == mostProminentId }?.smoothedProminenceDb
        val clearlyStronger = currentProminence == null ||
            top.smoothedProminenceDb - currentProminence >= settings.prominenceSwitchMarginDb
        if (clearlyStronger) {
            mostProminentId = top.id
            prominenceCandidateId = null
            return
        }
        if (prominenceCandidateId != top.id) {
            prominenceCandidateId = top.id
            prominenceCandidateSinceMs = now
        } else if (now - prominenceCandidateSinceMs >= settings.prominenceDwellMs) {
            mostProminentId = top.id
            prominenceCandidateId = null
        }
    }

    private fun removeCaptureInternal(id: Long) {
        captures.removeAll { it.id == id }
        tracks.removeAll { it.linkedCaptureId == id }
        if (selectedCaptureId == id) selectedCaptureId = null
        if (mostProminentId == id) mostProminentId = null
        if (prominenceCandidateId == id) prominenceCandidateId = null
    }

    private fun matchAndUpdateTracks(observations: List<Observation>, binWidthHz: Double, now: Long) {
        val toleranceHz = settings.matchToleranceBins * binWidthHz
        val unmatchedObservations = observations.toMutableList()

        // Iterate a snapshot: confirming a track can evict a bank slot, which removes OTHER
        // tracks linked to that slot via removeCaptureInternal -- mutating `tracks` mid-iteration.
        for (track in tracks.toList()) {
            val best = unmatchedObservations.minByOrNull { abs(it.frequencyHz - track.smoothedFrequencyHz) }
            if (best != null && abs(best.frequencyHz - track.smoothedFrequencyHz) <= toleranceHz) {
                unmatchedObservations.remove(best)
                val alpha = settings.emaAlpha
                track.smoothedFrequencyHz = track.smoothedFrequencyHz * (1 - alpha) + best.frequencyHz * alpha
                track.lastSeenAtMs = now
                track.prominenceDb = best.prominenceDb

                if (!track.confirmed && now - track.firstSeenAtMs >= settings.confirmDurationMs) {
                    confirm(track, now, binWidthHz)
                }
                track.linkedCaptureId?.let { capId ->
                    captures.find { it.id == capId }?.let { cap ->
                        cap.lastSeenAtMs = now
                        cap.prominenceDb = best.prominenceDb
                        val pAlpha = settings.prominenceEmaAlpha
                        cap.smoothedProminenceDb = cap.smoothedProminenceDb * (1 - pAlpha) + best.prominenceDb * pAlpha
                        cap.restoredFromDisk = false
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

    private fun confirm(track: Track, now: Long, binWidthHz: Double) {
        track.confirmed = true
        val dedupToleranceHz = settings.dedupToleranceBins * binWidthHz
        val existing = captures.minByOrNull { abs(it.frequencyHz - track.smoothedFrequencyHz) }
            ?.takeIf { abs(it.frequencyHz - track.smoothedFrequencyHz) <= dedupToleranceHz }

        val capture: RingCapture? = when {
            existing != null -> {
                existing.lastSeenAtMs = now
                existing.prominenceDb = track.prominenceDb
                val alpha = settings.prominenceEmaAlpha
                existing.smoothedProminenceDb = existing.smoothedProminenceDb * (1 - alpha) + track.prominenceDb * alpha
                existing.restoredFromDisk = false
                existing
            }
            captures.size < settings.maxCaptures -> insertNewCapture(track, now)
            else -> {
                val evictionTarget = findEvictionTarget(now, track.prominenceDb)
                if (evictionTarget != null) {
                    removeCaptureInternal(evictionTarget.id)
                    insertNewCapture(track, now)
                } else {
                    null
                }
            }
        }

        if (capture == null) {
            // No room and nothing eligible to evict (e.g. all 5 pinned): this tone stays a live,
            // confirmed track but never claims a bank slot until room frees up.
            track.linkedCaptureId = null
            return
        }
        track.linkedCaptureId = capture.id

        val current = selectedCaptureId?.let { id -> captures.find { it.id == id } }
        if (current == null || current.isExpired(now, settings.autoHoldMs)) {
            selectedCaptureId = capture.id
        }
    }

    private fun insertNewCapture(track: Track, now: Long): RingCapture {
        val fresh = RingCapture(
            id = nextCaptureId++,
            frequencyHz = track.smoothedFrequencyHz,
            confirmedAtMs = now,
            lastSeenAtMs = now,
            prominenceDb = track.prominenceDb,
            smoothedProminenceDb = track.prominenceDb,
        )
        captures.add(fresh)
        return fresh
    }

    /**
     * Deterministic eviction target when the bank is already full: prefer the oldest
     * expired-and-unpinned capture (fill-empty-first equivalent once a slot has genuinely gone
     * stale); otherwise the weakest unpinned capture, but only if the new candidate is at least
     * [RingTrackerSettings.replacementMarginDb] stronger -- "sufficiently stronger", to avoid
     * constant churn. Pinned captures are never returned. Null means: don't evict anything.
     */
    private fun findEvictionTarget(now: Long, candidateProminenceDb: Double): RingCapture? {
        val expiredUnpinned = captures.filter { it.isExpired(now, settings.autoHoldMs) }
            .minByOrNull { it.lastSeenAtMs }
        if (expiredUnpinned != null) return expiredUnpinned

        val weakestUnpinned = captures.filter { !it.pinned }.minByOrNull { it.smoothedProminenceDb } ?: return null
        return if (candidateProminenceDb - weakestUnpinned.smoothedProminenceDb >= settings.replacementMarginDb) {
            weakestUnpinned
        } else {
            null
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
