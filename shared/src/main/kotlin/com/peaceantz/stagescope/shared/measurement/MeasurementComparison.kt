package com.peaceantz.stagescope.shared.measurement

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class IncompatibilityReason {
    MISSING_SNAPSHOT,
    NO_LEVEL_DATA,
    DEMO_MISMATCH,
    INPUT_SOURCE_MISMATCH,
    SAMPLE_RATE_MISMATCH,
    FFT_MISMATCH,
    CALIBRATION_MISMATCH,
    ORDER_INVALID,
    TOO_FAR_APART,
    POSITION_DIFFERS,
}

@Serializable
data class BandDelta(
    val loHz: Double,
    val hiHz: Double,
    val beforeDbfs: Double,
    val afterDbfs: Double,
    val deltaDb: Double,
)

@Serializable
enum class RingChangeKind { APPEARED, DISAPPEARED, CHANGED, SAME }

@Serializable
data class RingChange(
    val frequencyHz: Double,
    val kind: RingChangeKind,
    val beforeProminenceDb: Double? = null,
    val afterProminenceDb: Double? = null,
    val deltaDb: Double? = null,
    val beforeFreshness: RingFreshness? = null,
    val afterFreshness: RingFreshness? = null,
)

@Serializable
data class ComparisonSummary(
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val secondsBetween: Long,
    val rmsDeltaDb: Double,
    val peakDeltaDb: Double,
    /** Largest band changes first, bounded. Bands with changes under [MIN_REPORTED_BAND_DELTA_DB] are omitted. */
    val bandDeltas: List<BandDelta>,
    val unreportedBandCount: Int,
    val ringChanges: List<RingChange>,
    val summaryLine: String,
)

@Serializable
sealed interface ComparisonResult {
    @Serializable
    @kotlinx.serialization.SerialName("compatible")
    data class Compatible(val summary: ComparisonSummary, val caveats: List<String>) : ComparisonResult

    @Serializable
    @kotlinx.serialization.SerialName("incompatible")
    data class Incompatible(
        val reasons: List<IncompatibilityReason>,
        val details: List<String>,
        /** What the user should do instead: a fresh measurement under matching conditions. */
        val requestedAction: String,
    ) : ComparisonResult
}

/**
 * Before/after comparison computed **locally**, never by a model. Two snapshots are only compared
 * when they were measured on the same footing -- same input path, demo status, sample rate, FFT
 * configuration and calibration, newer-after-older, close enough in time, and (when the user
 * described it) the same position. Anything else yields [ComparisonResult.Incompatible] with a
 * concrete request for a new measurement: an incompatible pair must never produce a confident
 * difference.
 */
object MeasurementComparison {
    const val MIN_REPORTED_BAND_DELTA_DB = 3.0
    const val MAX_REPORTED_BANDS = 12
    const val RING_MATCH_TOLERANCE_BINS = 4
    const val CAVEAT_GAP_MS = 2 * 60 * 60 * 1000L
    const val MAX_GAP_MS = 12 * 60 * 60 * 1000L

    fun compare(before: MeasurementContext?, after: MeasurementContext?): ComparisonResult {
        if (before == null || after == null) {
            return incompatible(
                listOf(IncompatibilityReason.MISSING_SNAPSHOT),
                listOf("Both a 'before' and an 'after' snapshot are needed."),
            )
        }
        val reasons = mutableListOf<IncompatibilityReason>()
        val details = mutableListOf<String>()
        fun fail(reason: IncompatibilityReason, detail: String) {
            reasons += reason
            details += detail
        }

        if (before.level == null || after.level == null) {
            fail(IncompatibilityReason.NO_LEVEL_DATA, "At least one snapshot has no level reading (capture was not running).")
        }
        if (before.device.isDemo != after.device.isDemo) {
            fail(IncompatibilityReason.DEMO_MISMATCH, "One snapshot is the synthetic Demo signal and the other is a real measurement.")
        }
        val bc = before.config
        val ac = after.config
        if (bc != null && ac != null) {
            if (bc.audioSourceLabel != ac.audioSourceLabel) {
                fail(IncompatibilityReason.INPUT_SOURCE_MISMATCH, "Input source differs: '${bc.audioSourceLabel}' vs '${ac.audioSourceLabel}'.")
            }
            if (bc.sampleRateHz != ac.sampleRateHz) {
                fail(IncompatibilityReason.SAMPLE_RATE_MISMATCH, "Sample rate differs: ${bc.sampleRateHz} Hz vs ${ac.sampleRateHz} Hz.")
            }
            if (bc.fftSize != ac.fftSize) {
                fail(IncompatibilityReason.FFT_MISMATCH, "FFT size differs: ${bc.fftSize} vs ${ac.fftSize}.")
            }
        } else {
            fail(IncompatibilityReason.INPUT_SOURCE_MISMATCH, "The input configuration is unknown for at least one snapshot.")
        }
        if (!sameCalibration(before.calibration, after.calibration)) {
            fail(IncompatibilityReason.CALIBRATION_MISMATCH, "Calibration differs between the snapshots (one is uncalibrated or the offsets differ).")
        }
        val gapMs = after.capturedAtEpochMs - before.capturedAtEpochMs
        if (gapMs <= 0L) {
            fail(IncompatibilityReason.ORDER_INVALID, "The 'after' snapshot was not taken later than the 'before' snapshot.")
        } else if (gapMs > MAX_GAP_MS) {
            fail(IncompatibilityReason.TOO_FAR_APART, "The snapshots are more than ${MAX_GAP_MS / 3_600_000L} hours apart; room and audience conditions will have changed.")
        }
        val bpRaw = before.userContext?.micPosition
        val apRaw = after.userContext?.micPosition
        val bp = bpRaw?.normalized()
        val ap = apRaw?.normalized()
        if (bp != null && ap != null && bp != ap) {
            fail(IncompatibilityReason.POSITION_DIFFERS, "The microphone position described differs: '$bpRaw' vs '$apRaw'.")
        }

        if (reasons.isNotEmpty()) return incompatible(reasons, details)

        val caveats = mutableListOf<String>()
        if (gapMs > CAVEAT_GAP_MS) caveats += "The snapshots are ${gapMs / 60_000L} minutes apart; changes may reflect the room or audience, not only your adjustment."
        if (bp == null || ap == null) caveats += "The microphone position was not described for ${if (bp == null && ap == null) "either snapshot" else "one snapshot"}; confirm the watch was in the same place and orientation."
        if (before.run.spectrumFrozen || after.run.spectrumFrozen) caveats += "A spectrum was frozen in at least one snapshot, so spectral differences may not match the level difference in time."
        if (before.rings.any { it.freshness != RingFreshness.LIVE_NOW } || after.rings.any { it.freshness != RingFreshness.LIVE_NOW }) {
            caveats += "Some ring captures were not sounding at snapshot time; their prominence is historical."
        }
        caveats += "Single wrist microphone: differences do not show which source changed."

        val bl = before.level!!
        val al = after.level!!
        val rmsDelta = round1(al.rmsDbfs - bl.rmsDbfs)
        val peakDelta = round1(al.peakDbfs - bl.peakDbfs)

        val bandDeltas = bandDeltas(before.spectrum, after.spectrum)
        val reported = bandDeltas.filter { abs(it.deltaDb) >= MIN_REPORTED_BAND_DELTA_DB }
            .sortedByDescending { abs(it.deltaDb) }.take(MAX_REPORTED_BANDS)
        val unreported = bandDeltas.size - reported.size

        val binWidth = ac!!.binWidthHz
        val ringChanges = ringChanges(before.rings, after.rings, RING_MATCH_TOLERANCE_BINS * binWidth)

        val line = buildString {
            append("RMS ${signed(rmsDelta)} dB, sample peak ${signed(peakDelta)} dB (raw dBFS)")
            val appeared = ringChanges.count { it.kind == RingChangeKind.APPEARED }
            val gone = ringChanges.count { it.kind == RingChangeKind.DISAPPEARED }
            if (appeared + gone > 0) append("; rings: $appeared appeared, $gone no longer captured")
        }
        return ComparisonResult.Compatible(
            ComparisonSummary(
                beforeSnapshotId = before.snapshotId,
                afterSnapshotId = after.snapshotId,
                secondsBetween = gapMs / 1000L,
                rmsDeltaDb = rmsDelta,
                peakDeltaDb = peakDelta,
                bandDeltas = reported,
                unreportedBandCount = unreported,
                ringChanges = ringChanges,
                summaryLine = line,
            ),
            caveats,
        )
    }

    private fun incompatible(reasons: List<IncompatibilityReason>, details: List<String>) =
        ComparisonResult.Incompatible(
            reasons = reasons,
            details = details,
            requestedAction = "Take a new 'after' measurement on the same input, with Demo off, at the same position and under the same calibration, " +
                "then compare it with the 'before' snapshot (or take a new 'before' as well).",
        )

    private fun sameCalibration(a: CalibrationEvidence?, b: CalibrationEvidence?): Boolean {
        val aApplied = a?.applied == true
        val bApplied = b?.applied == true
        if (aApplied != bApplied) return false
        if (!aApplied) return true
        val ao = a.offsetDb ?: 0.0
        val bo = b?.offsetDb ?: 0.0
        return abs(ao - bo) < 1e-9
    }

    private fun bandDeltas(before: SpectrumEvidence?, after: SpectrumEvidence?): List<BandDelta> {
        if (before == null || after == null || before.bands.size != after.bands.size) return emptyList()
        return before.bands.indices.map { i ->
            val b = before.bands[i]
            val a = after.bands[i]
            BandDelta(b.loHz, b.hiHz, round1(b.maxDbfs), round1(a.maxDbfs), round1(a.maxDbfs - b.maxDbfs))
        }
    }

    private fun ringChanges(before: List<RingEvidence>, after: List<RingEvidence>, toleranceHz: Double): List<RingChange> {
        val unmatchedAfter = after.toMutableList()
        val out = mutableListOf<RingChange>()
        for (b in before) {
            val match = unmatchedAfter.minByOrNull { abs(it.frequencyHz - b.frequencyHz) }
                ?.takeIf { abs(it.frequencyHz - b.frequencyHz) <= toleranceHz }
            if (match == null) {
                out += RingChange(b.frequencyHz, RingChangeKind.DISAPPEARED, b.prominenceDb, null, null, b.freshness, null)
            } else {
                unmatchedAfter.remove(match)
                val delta = round1(match.prominenceDb - b.prominenceDb)
                out += RingChange(
                    match.frequencyHz,
                    if (abs(delta) >= 3.0) RingChangeKind.CHANGED else RingChangeKind.SAME,
                    b.prominenceDb, match.prominenceDb, delta, b.freshness, match.freshness,
                )
            }
        }
        for (a in unmatchedAfter) {
            out += RingChange(a.frequencyHz, RingChangeKind.APPEARED, null, a.prominenceDb, null, null, a.freshness)
        }
        return out.sortedBy { it.frequencyHz }
    }

    private fun String.normalized(): String? = trim().lowercase().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun signed(v: Double): String = if (v >= 0) "+$v" else "$v"
}
