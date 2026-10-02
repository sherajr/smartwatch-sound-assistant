package com.peaceantz.stagescope.assistant.measure

import com.peaceantz.stagescope.shared.measurement.HistoryPoint

/**
 * A small in-memory trail of the last few seconds of measurement, so a question like "was it
 * ringing just now?" has something to look at besides one instant. It is never persisted and never
 * uploaded on its own -- it is attached only to a snapshot the person asked an assistant about.
 *
 * Bounded three ways: by age ([windowMs]), by rate ([minIntervalMs]) and by size ([maxPoints]).
 * Only fed while measurement is actually running, so a pause or a stop leaves a gap, not invented points.
 * Thread-safe: samples arrive on the audio thread, snapshots are taken on the UI thread.
 */
class MeasurementHistoryBuffer(
    private val windowMs: Long = 10_000,
    private val minIntervalMs: Long = 250,
    private val maxPoints: Int = 40,
) {
    private class Sample(
        val atMs: Long,
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val topPeakHz: Double?,
        val topPeakDbfs: Double?,
        val liveRingCount: Int,
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    fun add(
        atMs: Long, rmsDbfs: Double, peakDbfs: Double,
        topPeakHz: Double? = null, topPeakDbfs: Double? = null, liveRingCount: Int = 0,
    ) = synchronized(lock) {
        val last = samples.lastOrNull()
        if (last != null && atMs - last.atMs < minIntervalMs) return
        samples.addLast(Sample(atMs, rmsDbfs, peakDbfs, topPeakHz, topPeakDbfs, liveRingCount))
        trim(atMs)
    }

    fun clear() = synchronized(lock) { samples.clear() }

    /** Points within the window ending at [nowMs], oldest first, with offsets <= 0 relative to [nowMs]. */
    fun snapshot(nowMs: Long): List<HistoryPoint> = synchronized(lock) {
        trim(nowMs)
        samples.map {
            HistoryPoint(
                offsetMs = (it.atMs - nowMs).coerceAtMost(0L),
                rmsDbfs = round1(it.rmsDbfs), peakDbfs = round1(it.peakDbfs),
                topPeakHz = it.topPeakHz?.let(::round1), topPeakDbfs = it.topPeakDbfs?.let(::round1),
                liveRingCount = it.liveRingCount,
            )
        }
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs) samples.removeFirst()
        while (samples.size > maxPoints) samples.removeFirst()
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
