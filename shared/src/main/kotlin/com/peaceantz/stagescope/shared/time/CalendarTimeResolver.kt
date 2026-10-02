package com.peaceantz.stagescope.shared.time

import com.peaceantz.stagescope.shared.show.CalendarDefaults
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What a model (or a person) said about when an event is. Interpreted locally, never by the model. */
data class TimeSpec(
    val date: String? = null,
    /** `today`, `tomorrow`, or a weekday name (the next such day, never today). */
    val relativeDate: String? = null,
    /** 0..23 when [hourIs24] is true; 1..12 otherwise. */
    val hour: Int? = null,
    val minute: Int = 0,
    val hourIs24: Boolean = false,
    /** `AM`/`PM` when the person said it. */
    val meridiem: String? = null,
    val durationMinutes: Int? = null,
    val allDay: Boolean = false,
)

data class ResolvedTime(
    val zone: ZoneId,
    val startLocal: LocalDateTime,
    val endLocal: LocalDateTime,
    val startOffset: ZoneOffset,
    val endOffset: ZoneOffset,
    val allDay: Boolean,
    val assumptions: List<String>,
) {
    fun startZoned(): ZonedDateTime = ZonedDateTime.ofInstant(startLocal.toInstant(startOffset), zone)

    /** "Sat 14 Mar 2026, 4:00 PM – 5:00 PM (America/New_York, UTC-04:00)" -- date, time and zone always together. */
    fun describe(): String {
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        if (allDay) return "${startLocal.format(dayFmt)}, all day (${zone.id})"
        val offsetText = if (startOffset == endOffset) "UTC${startOffset.id.replace("Z", "+00:00")}" else "UTC${startOffset.id.replace("Z", "+00:00")} → UTC${endOffset.id.replace("Z", "+00:00")}"
        val sameDay = startLocal.toLocalDate() == endLocal.toLocalDate()
        val end = if (sameDay) endLocal.format(timeFmt) else endLocal.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US))
        return "${startLocal.format(dayFmt)}, ${startLocal.format(timeFmt)} – $end ($zone, $offsetText)"
    }
}

sealed interface TimeResolution {
    data class Resolved(val time: ResolvedTime) : TimeResolution
    /** Ask the person; never guess a date, AM/PM or a past date. */
    data class NeedsInfo(val questions: List<String>) : TimeResolution
}

/**
 * Turns a [TimeSpec] into a concrete zoned start/end. The zone is always explicit (the show's zone
 * or the phone's) -- never silently UTC or the developer machine's. Missing AM/PM, date or a past
 * date become questions; a missing duration uses the person's *visible* default; and a local time
 * that falls in a DST gap or overlap is resolved deterministically **and** called out so it is on
 * the confirmation card.
 */
object CalendarTimeResolver {

    fun resolve(spec: TimeSpec, zone: ZoneId, now: Instant, defaults: CalendarDefaults): TimeResolution {
        val questions = mutableListOf<String>()
        val assumptions = mutableListOf<String>()
        val today = now.atZone(zone).toLocalDate()

        val date: LocalDate? = when {
            !spec.date.isNullOrBlank() -> runCatching { LocalDate.parse(spec.date.trim()) }.getOrElse {
                questions += "I couldn't read the date \"${spec.date}\". Which day do you mean?"
                null
            }
            !spec.relativeDate.isNullOrBlank() -> relativeDate(spec.relativeDate, today).also {
                if (it == null) questions += "I couldn't tell which day \"${spec.relativeDate}\" is. Which day do you mean?"
            }
            else -> {
                questions += "Which day is the event on?"
                null
            }
        }
        if (date != null && date.isBefore(today)) {
            questions += "That date (${date}) has already passed. Which date do you want?"
        }

        if (spec.allDay) {
            if (questions.isNotEmpty() || date == null) return TimeResolution.NeedsInfo(questions)
            val start = date.atStartOfDay()
            val end = date.plusDays(1).atStartOfDay()
            return TimeResolution.Resolved(
                ResolvedTime(zone, start, end, zone.rules.getOffset(start), zone.rules.getOffset(end), true, assumptions),
            )
        }

        val hour24: Int? = when {
            spec.hour == null -> {
                questions += "What time does it start?"
                null
            }
            spec.hourIs24 || spec.hour == 0 || spec.hour in 13..23 -> spec.hour.takeIf { it in 0..23 }
            spec.hour !in 1..12 -> {
                questions += "I couldn't read the hour ${spec.hour}. What time does it start?"
                null
            }
            spec.meridiem.equals("AM", true) -> if (spec.hour == 12) 0 else spec.hour
            spec.meridiem.equals("PM", true) -> if (spec.hour == 12) 12 else spec.hour + 12
            else -> when (defaults.ambiguousHourBias.uppercase()) {
                "PM" -> (if (spec.hour == 12) 12 else spec.hour + 12).also { assumptions += "Assumed PM for ${spec.hour}:${"%02d".format(spec.minute)} (your default is PM for hours without AM/PM)." }
                "AM" -> (if (spec.hour == 12) 0 else spec.hour).also { assumptions += "Assumed AM for ${spec.hour}:${"%02d".format(spec.minute)} (your default is AM for hours without AM/PM)." }
                else -> {
                    questions += "Did you mean ${spec.hour}:${"%02d".format(spec.minute)} AM or ${spec.hour}:${"%02d".format(spec.minute)} PM?"
                    null
                }
            }
        }
        if (spec.minute !in 0..59) questions += "I couldn't read the minutes (${spec.minute}). What time does it start?"

        if (questions.isNotEmpty() || date == null || hour24 == null) return TimeResolution.NeedsInfo(questions)

        val durationMinutes = spec.durationMinutes?.takeIf { it in 1..(24 * 60 * 14) } ?: defaults.defaultDurationMinutes.also {
            assumptions += "Duration assumed to be $it minutes (your default)."
        }

        val startLocal = LocalDateTime.of(date, LocalTime.of(hour24, spec.minute))
        val validOffsets = zone.rules.getValidOffsets(startLocal)
        val startZoned: ZonedDateTime = when (validOffsets.size) {
            0 -> {
                // Spring-forward gap: the wall-clock time doesn't exist. java.time shifts it forward.
                val shifted = ZonedDateTime.ofLocal(startLocal, zone, null)
                assumptions += "${startLocal.toLocalTime()} doesn't exist on $date in ${zone.id} (clocks move forward); using ${shifted.toLocalTime()} (UTC${shifted.offset.id})."
                shifted
            }
            2 -> {
                val first = ZonedDateTime.ofLocal(startLocal, zone, validOffsets.first())
                assumptions += "${startLocal.toLocalTime()} happens twice on $date in ${zone.id} (clocks move back); using the first occurrence (UTC${first.offset.id})."
                first
            }
            else -> ZonedDateTime.ofLocal(startLocal, zone, null)
        }
        // Duration is elapsed time, so an event spanning a DST change keeps its real length.
        val endZoned = startZoned.plus(Duration.ofMinutes(durationMinutes.toLong()))
        return TimeResolution.Resolved(
            ResolvedTime(zone, startZoned.toLocalDateTime(), endZoned.toLocalDateTime(), startZoned.offset, endZoned.offset, false, assumptions),
        )
    }

    /** Only the unambiguous words: today, tomorrow, and weekday names (the next such day, strictly after today). */
    fun relativeDate(word: String, today: LocalDate): LocalDate? {
        val w = word.trim().lowercase().removePrefix("next ").removePrefix("this ").trim()
        return when (w) {
            "today", "tonight" -> today
            "tomorrow" -> today.plusDays(1)
            else -> {
                val dow = DayOfWeek.entries.firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() == w }
                    ?: DayOfWeek.entries.firstOrNull { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() == w }
                    ?: return null
                var d = today.plusDays(1)
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                d
            }
        }
    }
}

/** Calendar API event ids must be base32hex (a-v, 0-9), 5..1024 chars; a UUID's hex digits qualify. */
object CalendarEventId {
    private val VALID = Regex("^[a-v0-9]{5,1024}$")

    fun fromActionId(actionId: String): String {
        val hex = actionId.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        val id = "ss$hex".take(64)
        return if (isValid(id)) id else "ss" + java.util.UUID.nameUUIDFromBytes(actionId.toByteArray()).toString().replace("-", "")
    }

    fun isValid(id: String): Boolean = VALID.matches(id)
}
