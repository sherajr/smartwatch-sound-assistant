package com.peaceantz.stagescope.ui.analyzer

/**
 * Shared radii for the Analyzer's full-bleed circular composition, derived from a single
 * [usableRadius] so the circumference level meter, the spectrum annulus, and the center text
 * "safe zone" always agree on where each other's boundaries are -- whether [usableRadius] is
 * measured in dp (for sizing the center content box) or px (inside a Canvas draw scope), since
 * both are computed from the exact same full-bleed box in [AnalyzerScreen].
 *
 * Proportions are initial engineering starting points from the redesign spec, not derived from
 * device measurement -- [InstrumentGeometryTest] only checks internal ordering/bounds, and these
 * fractions are meant to be retuned after rendering on real watch dimensions.
 */
object InstrumentGeometry {
    /** Level-meter outer boundary, as a fraction of [usableRadius] (spec range 0.95-0.98). */
    const val METER_OUTER_FRACTION = 0.965f

    /** Level-meter track thickness, as a fraction of [usableRadius]. */
    const val METER_TRACK_FRACTION = 0.045f

    /** Visible gap between the level meter's inner edge and the spectrum's outer edge. */
    const val METER_SPECTRUM_GAP_FRACTION = 0.02f

    /** Spectrum inner boundary, as a fraction of [usableRadius] (spec range 0.60-0.65). */
    const val SPECTRUM_INNER_FRACTION = 0.62f

    /** Center "safe zone" radius, as a fraction of the spectrum's own inner radius -- leaves a
     *  visible gap between the center content and the spectrum bars/cursor. */
    const val CENTER_SAFE_FRACTION = 0.92f

    data class Radii(
        val meterOuter: Float,
        val meterInner: Float,
        val spectrumOuter: Float,
        val spectrumInner: Float,
        val centerSafeRadius: Float,
    )

    fun compute(usableRadius: Float): Radii {
        val meterOuter = usableRadius * METER_OUTER_FRACTION
        val meterInner = meterOuter - usableRadius * METER_TRACK_FRACTION
        val spectrumOuter = meterInner - usableRadius * METER_SPECTRUM_GAP_FRACTION
        val spectrumInner = usableRadius * SPECTRUM_INNER_FRACTION
        val centerSafeRadius = spectrumInner * CENTER_SAFE_FRACTION
        return Radii(meterOuter, meterInner, spectrumOuter, spectrumInner, centerSafeRadius)
    }
}
