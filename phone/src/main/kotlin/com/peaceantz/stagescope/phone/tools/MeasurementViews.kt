package com.peaceantz.stagescope.phone.tools

import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.MeasurementQuality
import com.peaceantz.stagescope.shared.measurement.RingFreshness
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Renders a [MeasurementContext] for a model: compact for the prompt, fuller on demand through the
 * `get_measurement_context` tool. Numbers are rounded to what the measurement can support (the FFT
 * resolution is ~10 Hz and dB figures are not accurate to hundredths), and every figure keeps the
 * name that says what it is -- raw dBFS is never relabelled SPL, detector contrast is never called
 * a probability.
 */
object MeasurementViews {

    fun promptJson(ctx: MeasurementContext, nowEpochMs: Long): JsonObject = buildJsonObject {
        put("snapshot_id", ctx.snapshotId)
        put("captured_at_utc", iso(ctx.capturedAtEpochMs))
        put("seconds_before_this_request", MeasurementQuality.ageSeconds(ctx, nowEpochMs))
        put("age_note", "This is a snapshot taken when the question started; the sound may have changed since.")
        put("input", buildJsonObject {
            put("source", ctx.device.inputSourceLabel)
            put("is_demo_synthetic_signal", ctx.device.isDemo)
            ctx.device.deviceModel?.let { put("watch", it) }
        })
        put("capture_state", buildJsonObject {
            put("state", ctx.run.state.name)
            put("spectrum_frozen", ctx.run.spectrumFrozen)
            ctx.run.activeSeconds?.let { put("measuring_seconds", it) }
        })
        ctx.config?.let { c ->
            put("analysis", buildJsonObject {
                put("sample_rate_hz", c.sampleRateHz)
                put("fft_size", c.fftSize)
                put("bin_width_hz", r2(c.binWidthHz))
                put("window", c.window)
                put("overlap", c.overlap)
                put("note", "Frequency resolution is one FFT bin; a ring cannot be located more precisely than the bin width.")
            })
        }
        ctx.level?.let { l ->
            put("level_raw_dbfs", buildJsonObject {
                put("rms", r1(l.rmsDbfs))
                put("sample_peak", r1(l.peakDbfs))
                l.maxRmsDbfs?.let { put("max_rms", r1(it)) }
                l.sessionAverageRmsDbfs?.let { put("session_energy_average_rms", r1(it)) }
                put("clipping_now", l.clippingNow)
                put("clipped_since_reset", l.clippedSinceReset)
                put("suspicious_digital_silence", l.suspiciousSilence)
            })
        }
        ctx.calibration?.let { c ->
            if (c.applied) {
                put("estimated_spl", buildJsonObject {
                    put("applied", true)
                    c.offsetDb?.let { put("offset_db", r1(it)) }
                    c.estimatedSplDb?.let { put("rms_estimated_spl_db", r1(it)) }
                    put("note", "One broadband offset for this input configuration. Not certified, not A-weighted. Spectrum values are NOT offset-corrected.")
                })
            } else {
                put("estimated_spl", buildJsonObject { put("applied", false); put("note", "Uncalibrated: only raw dBFS is available.") })
            }
        }
        if (ctx.rings.isNotEmpty()) put("rings", ringsJson(ctx))
        ctx.ringBank?.let { b ->
            put("ring_bank", buildJsonObject {
                put("slots_used", b.slotsUsed); put("slots_total", b.slotsTotal)
                put("all_slots_pinned", b.allSlotsPinned); put("auto_hold_seconds", b.autoHoldSeconds)
            })
        }
        ctx.spectrum?.let { s ->
            put("spectrum_raw_dbfs", buildJsonObject {
                put("held_by_freeze", s.held)
                put("loudest_bands", buildJsonArray {
                    s.bands.sortedByDescending { it.maxDbfs }.take(10).forEach { b ->
                        add(buildJsonObject { put("band_hz", "${r0(b.loHz)}-${r0(b.hiHz)}"); put("peak_hz", r1(b.peakHz)); put("max_dbfs", r1(b.maxDbfs)) })
                    }
                })
                put("strongest_peaks", buildJsonArray {
                    s.peaks.take(8).forEach { p ->
                        add(buildJsonObject { put("hz", r1(p.frequencyHz)); put("dbfs", r1(p.magnitudeDbfs)); put("contrast_db", r1(p.contrastDb)) })
                    }
                })
                s.noiseFloorEstimateDbfs?.let { put("noise_floor_estimate_dbfs", r1(it)) }
            })
        }
        if (ctx.history.isNotEmpty()) put("recent_history", historyJson(ctx, 6))
        ctx.userContext?.let { u ->
            put("described_by_user", buildJsonObject {
                u.productionName?.let { put("production", it) }; u.venue?.let { put("venue", it) }
                u.equipment?.let { put("equipment", it) }; u.signalPath?.let { put("signal_path", it) }
                u.micPosition?.let { put("watch_position", it) }; u.performanceLabel?.let { put("performance", it) }
            })
        }
        put("measurement_notes", JsonArray(ctx.notes.map { JsonPrimitive(it) }))
    }

    /** `detail`: summary | peaks | bands | rings | history | all. Bounded; never raw audio. */
    fun detailJson(ctx: MeasurementContext, detail: String, nowEpochMs: Long): JsonObject = buildJsonObject {
        put("snapshot_id", ctx.snapshotId)
        put("seconds_before_this_request", MeasurementQuality.ageSeconds(ctx, nowEpochMs))
        val all = detail == "all"
        if (detail == "summary" || all) put("summary", promptJson(ctx, nowEpochMs))
        if (detail == "peaks" || all) {
            put("peaks_native_resolution", buildJsonArray {
                ctx.spectrum?.peaks.orEmpty().forEach { p ->
                    add(buildJsonObject {
                        put("hz", r1(p.frequencyHz)); put("dbfs", r1(p.magnitudeDbfs)); put("contrast_db", r1(p.contrastDb))
                        put("neighbouring_bins", buildJsonArray { p.neighbours.forEach { n -> add(buildJsonObject { put("hz", r1(n.hz)); put("dbfs", r1(n.dbfs)) }) } })
                    })
                }
            })
        }
        if (detail == "bands" || all) {
            put("bands_raw_dbfs_max_aggregated", buildJsonArray {
                ctx.spectrum?.bands.orEmpty().forEach { b ->
                    add(buildJsonObject { put("lo_hz", r0(b.loHz)); put("hi_hz", r0(b.hiHz)); put("peak_hz", r1(b.peakHz)); put("max_dbfs", r1(b.maxDbfs)) })
                }
            })
        }
        if (detail == "rings" || all) put("rings", ringsJson(ctx))
        if (detail == "history" || all) put("recent_history", historyJson(ctx, 30))
    }

    private fun ringsJson(ctx: MeasurementContext): JsonArray = buildJsonArray {
        ctx.rings.forEach { r ->
            add(buildJsonObject {
                put("frequency_hz", r1(r.frequencyHz))
                put("detector_contrast_db", r1(r.prominenceDb))
                put("freshness", r.freshness.name)
                put("sounding_at_snapshot", r.freshness == RingFreshness.LIVE_NOW)
                if (r.lastObservedAgoMs != null) put("last_observed_ms_before_snapshot", r.lastObservedAgoMs) else put("last_observed_ms_before_snapshot", JsonNull)
                put("tracked_for_ms", r.trackingDurationMs)
                put("pinned", r.pinned)
                put("restored_from_previous_session", r.restoredFromDisk)
                put("most_prominent", r.mostProminent)
            })
        }
    }

    private fun historyJson(ctx: MeasurementContext, limit: Int): JsonArray = buildJsonArray {
        ctx.history.takeLast(limit).forEach { h ->
            add(buildJsonObject {
                put("seconds_before_snapshot", abs(h.offsetMs) / 1000.0)
                put("rms_dbfs", r1(h.rmsDbfs)); put("sample_peak_dbfs", r1(h.peakDbfs))
                h.topPeakHz?.let { put("top_peak_hz", r1(it)) }; h.topPeakDbfs?.let { put("top_peak_dbfs", r1(it)) }
                put("live_rings", h.liveRingCount)
            })
        }
    }

    private fun iso(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))
    private fun r0(v: Double): Long = Math.round(v)
    private fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
