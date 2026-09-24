package com.peaceantz.stagescope.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Shared by the Tile and the complication: both show cached, historical values that must stay
 * truthful even if the surface isn't re-rendered for hours (no "5 seconds ago" that silently goes
 * stale). Same-day records show a clock time; anything older shows a date instead.
 */
fun formatWhen(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (then.toLocalDate() == today) {
        then.format(DateTimeFormatter.ofPattern("h:mm a"))
    } else {
        then.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** e.g. "326Hz" / "2.2kHz" -- kept under the 7-character limit for a complication SHORT_TEXT field. */
fun formatFrequencyCompact(hz: Double): String = when {
    hz >= 10_000 -> "${(hz / 1000).roundToInt()}kHz"
    hz >= 1_000 -> "%.1fkHz".format(hz / 1000)
    else -> "${hz.roundToInt()}Hz"
}

/** e.g. "326 Hz" / "2.15 kHz" -- roomier form for the Tile and accessible descriptions. */
fun formatFrequencyReadable(hz: Double): String = when {
    hz >= 1_000 -> "%.2f kHz".format(hz / 1000)
    else -> "${hz.roundToInt()} Hz"
}

/** e.g. "-58.0" -- matches LevelScreen's own formatDb convention (explicit sign, one decimal). */
fun formatDbCompact(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(value)}"
