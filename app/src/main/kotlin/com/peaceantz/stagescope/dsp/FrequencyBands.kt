package com.peaceantz.stagescope.dsp

/**
 * Approximate, editable frequency-region listening guides for a sound designer. These are not
 * automatic diagnoses -- they only label which loose region a frequency falls into so a human can
 * make a listening judgement. Edit the ranges here; nothing else in the app hard-codes them.
 */
object FrequencyBands {

    data class Band(val label: String, val lowHz: Double, val highHz: Double)

    val ALL: List<Band> = listOf(
        Band("Rumble", 20.0, 80.0),
        Band("Body", 80.0, 250.0),
        Band("Warmth", 250.0, 500.0),
        Band("Presence", 2000.0, 6000.0),
        Band("Sibilance", 6000.0, 10000.0),
    )

    /** Returns the first matching band label for [frequencyHz], or null if it falls in a gap. */
    fun labelFor(frequencyHz: Double): String? =
        ALL.firstOrNull { frequencyHz >= it.lowHz && frequencyHz < it.highHz }?.label
}
