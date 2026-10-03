package com.peaceantz.stagescope.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tap tempo from beat timestamps (milliseconds, all on one monotonic clock). Pure Kotlin, zero
 * Android deps -- see `TapTempoTest` for the behavior contract.
 *
 * The beats come from a hand gesture the watch recognizes (a double pinch), not only from touch, so
 * the estimate has to survive what a recognizer does: miss a beat now and then, occasionally report
 * one that wasn't there, and add some timing jitter. So instead of averaging intervals, each tap
 * re-fits the most recent run of taps:
 *  1. every recent interval is tried as the beat length;
 *  2. from one of the two newest taps, walk back through older taps, giving each a whole-beat
 *     position -- a gap of two beats is a missed beat (bridged; no tap is invented for it), a tap
 *     that sits off the grid is skipped as a stray, and two strays in a row end the chain;
 *  3. each chain is re-walked on its own fitted beat length until it settles, so a wrong candidate
 *     can't stretch its tolerance over taps that don't belong;
 *  4. the chain with the most taps wins -- each bridged beat counting half against it, because a
 *     recognizer missing a pinch is likelier than one inventing a pinch -- and the beat length is the
 *     least-squares slope of time against beat position, which uses every tap's timing (not just the
 *     first and last) and is unaffected by a constant gesture latency.
 * Requiring the chain to start at one of the two newest taps is what lets a single sloppy or stray
 * tap leave the reading alone while a real tempo change takes over after two consistent taps.
 */
class TapTempo(private val settings: Settings = Settings()) {

    data class Settings(
        /** Closer than this to the previous tap is the same beat reported twice (a bounce, or a duplicate detection): ignored. */
        val minIntervalMillis: Long = 150L,
        /** The longest beat accepted (30 BPM). A longer pause starts a new run. */
        val maxBeatMillis: Long = 2_000L,
        /** How far (in beats) an interval may sit from a whole number of beats and still count. */
        val beatTolerance: Double = 0.3,
        /** A gap of up to this many beats is bridged (missed beats); a bigger gap breaks the chain. */
        val maxBeatsPerGap: Int = 2,
        /** How many recent taps are kept and fitted -- enough for a steady estimate, few enough to follow a performer. */
        val window: Int = 16,
    )

    /** [beatCount] is how many taps the estimate rests on: two taps give a rough tempo, a dozen a good one. */
    data class Estimate(val bpm: Double, val beatMillis: Double, val beatCount: Int)

    private val taps = ArrayDeque<Long>()

    /** The latest tempo; kept after a pause (and through the first tap of a new run) until two new taps replace it. */
    var estimate: Estimate? = null
        private set

    /** Records a beat at [atMillis]. Returns false if it was ignored as a duplicate (or arrived out of order). */
    fun tap(atMillis: Long): Boolean {
        val previous = taps.lastOrNull()
        if (previous != null) {
            val gap = atMillis - previous
            if (gap < settings.minIntervalMillis) return false
            val longestBridge = (estimate?.beatMillis ?: 0.0) * (settings.maxBeatsPerGap + settings.beatTolerance)
            if (gap > max(settings.maxBeatMillis.toDouble(), longestBridge)) taps.clear()
        }
        taps.addLast(atMillis)
        while (taps.size > settings.window) taps.removeFirst()
        bestChain()?.let { estimate = it.toEstimate() }
        return true
    }

    fun clear() {
        taps.clear()
        estimate = null
    }

    private class Chain(val times: List<Long>, val beats: List<Int>, val bridges: Int, val newest: Boolean) {
        /** Taps, with each bridged (missed) beat counting half against -- in halves, to stay an Int. */
        val score: Int get() = 2 * times.size - bridges
        val slope: Double
        val residual: Double

        init {
            val n = times.size
            val origin = times.first()
            val meanBeat = beats.average()
            val meanTime = times.sumOf { (it - origin).toDouble() } / n
            var covariance = 0.0
            var variance = 0.0
            for (i in 0 until n) {
                val db = beats[i] - meanBeat
                covariance += db * ((times[i] - origin) - meanTime)
                variance += db * db
            }
            slope = covariance / variance
            var squares = 0.0
            for (i in 0 until n) {
                val fitted = meanTime + slope * (beats[i] - meanBeat)
                val error = (times[i] - origin) - fitted
                squares += error * error
            }
            residual = sqrt(squares / n)
        }

        fun toEstimate() = Estimate(bpm = 60_000.0 / slope, beatMillis = slope, beatCount = times.size)

        /** More beats first; at a tie, fewer invented (bridged) beats, then the chain that includes the newest tap, then the tighter fit. */
        fun isBetterThan(other: Chain?): Boolean = when {
            other == null -> true
            score != other.score -> score > other.score
            bridges != other.bridges -> bridges < other.bridges
            newest != other.newest -> newest
            else -> residual < other.residual
        }
    }

    private fun bestChain(): Chain? {
        if (taps.size < 2) return null
        var best: Chain? = null
        for (i in 1 until taps.size) {
            val candidate = (taps[i] - taps[i - 1]).toDouble()
            if (candidate < settings.minIntervalMillis || candidate > settings.maxBeatMillis) continue
            for (anchor in listOf(taps.size - 1, taps.size - 2)) {
                val chain = fit(anchor, candidate, newest = anchor == taps.size - 1) ?: continue
                if (chain.isBetterThan(best)) best = chain
            }
        }
        return best
    }

    /**
     * Walks back on the [candidate] grid, then again on the chain's own fitted beat length until the
     * chain stops changing. Without this a wrong candidate stretches to swallow off-grid taps (a
     * 700 ms candidate accepts 500 ms intervals at the edge of the tolerance); refitted at the beat
     * those taps actually imply, such a chain falls apart.
     */
    private fun fit(anchor: Int, candidate: Double, newest: Boolean): Chain? {
        var chain = walkBack(anchor, candidate, newest) ?: return null
        repeat(REFINE_PASSES) {
            val refined = walkBack(anchor, chain.slope, newest) ?: return null
            if (refined.times == chain.times) return refined
            chain = refined
        }
        return chain
    }

    /** The chain of taps on a [beatMillis] grid, walking back from [anchor]; null if it has fewer than two taps. */
    private fun walkBack(anchor: Int, beatMillis: Double, newest: Boolean): Chain? {
        val times = mutableListOf(taps[anchor])
        val beats = mutableListOf(0)
        var bridges = 0
        var strays = 0
        for (j in anchor - 1 downTo 0) {
            val ratio = (times.last() - taps[j]) / beatMillis
            if (ratio > settings.maxBeatsPerGap + settings.beatTolerance) break // older taps are further still
            val k = ratio.roundToInt()
            if (k >= 1 && abs(ratio - k) <= settings.beatTolerance) {
                times += taps[j]
                beats += beats.last() - k
                if (k > 1) bridges++
                strays = 0
            } else if (++strays >= 2) {
                break
            }
        }
        if (times.size < 2) return null
        // Oldest first, so the fit's time origin is the earliest tap.
        return Chain(times.reversed(), beats.reversed(), bridges, newest)
    }

    private companion object {
        const val REFINE_PASSES = 3
    }
}
