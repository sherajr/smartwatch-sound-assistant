package com.peaceantz.stagescope.shared.time

import com.peaceantz.stagescope.shared.show.CalendarDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarTimeResolverTest {
    private val ny = ZoneId.of("America/New_York")
    private val ask = CalendarDefaults()
    private fun at(iso: String) = Instant.parse(iso)

    private fun resolved(spec: TimeSpec, zone: ZoneId = ny, now: Instant = at("2026-03-10T15:00:00Z"), defaults: CalendarDefaults = ask) =
        (CalendarTimeResolver.resolve(spec, zone, now, defaults) as TimeResolution.Resolved).time

    private fun needs(spec: TimeSpec, zone: ZoneId = ny, now: Instant = at("2026-03-10T15:00:00Z"), defaults: CalendarDefaults = ask) =
        (CalendarTimeResolver.resolve(spec, zone, now, defaults) as TimeResolution.NeedsInfo).questions

    @Test
    fun `an hour with no AM or PM asks instead of guessing`() {
        val q = needs(TimeSpec(relativeDate = "tomorrow", hour = 4))
        assertEquals(1, q.size)
        assertTrue(q.first().contains("AM or 4:00 PM"))
    }

    @Test
    fun `a configured default is applied and shown as an assumption`() {
        val r = resolved(TimeSpec(relativeDate = "tomorrow", hour = 4), defaults = CalendarDefaults(ambiguousHourBias = "PM"))
        assertEquals(16, r.startLocal.hour)
        assertTrue(r.assumptions.any { it.contains("Assumed PM") })
    }

    @Test
    fun `explicit meridiem and 24 hour times are unambiguous`() {
        assertEquals(16, resolved(TimeSpec(relativeDate = "tomorrow", hour = 4, meridiem = "PM")).startLocal.hour)
        assertEquals(0, resolved(TimeSpec(relativeDate = "tomorrow", hour = 12, meridiem = "AM")).startLocal.hour)
        assertEquals(12, resolved(TimeSpec(relativeDate = "tomorrow", hour = 12, meridiem = "PM")).startLocal.hour)
        assertEquals(19, resolved(TimeSpec(relativeDate = "tomorrow", hour = 19, hourIs24 = true)).startLocal.hour)
    }

    @Test
    fun `missing duration uses the visible default and says so`() {
        val r = resolved(TimeSpec(date = "2026-03-14", hour = 16, hourIs24 = true, minute = 30))
        assertEquals(60, java.time.Duration.between(r.startLocal, r.endLocal).toMinutes())
        assertTrue(r.assumptions.any { it.contains("Duration assumed to be 60 minutes") })
    }

    @Test
    fun `tomorrow is computed in the show's zone, not UTC`() {
        // 2026-03-14 02:30 UTC is still the evening of 13 March in New York.
        val now = at("2026-03-14T02:30:00Z")
        val inNewYork = resolved(TimeSpec(relativeDate = "tomorrow", hour = 16, hourIs24 = true), zone = ny, now = now)
        assertEquals(LocalDate.of(2026, 3, 14), inNewYork.startLocal.toLocalDate())
        val inUtc = resolved(TimeSpec(relativeDate = "tomorrow", hour = 16, hourIs24 = true), zone = ZoneId.of("UTC"), now = now)
        assertEquals(LocalDate.of(2026, 3, 15), inUtc.startLocal.toLocalDate())
    }

    @Test
    fun `a missing or unreadable date asks, and a past date asks`() {
        assertTrue(needs(TimeSpec(hour = 16, hourIs24 = true)).any { it.contains("Which day") })
        assertTrue(needs(TimeSpec(date = "next-ish", hour = 16, hourIs24 = true)).any { it.contains("couldn't read the date") })
        assertTrue(needs(TimeSpec(date = "2026-03-01", hour = 16, hourIs24 = true)).any { it.contains("already passed") })
    }

    @Test
    fun `weekday names mean the next such day strictly after today`() {
        val today = LocalDate.of(2026, 3, 10) // a Tuesday
        assertEquals(LocalDate.of(2026, 3, 16), CalendarTimeResolver.relativeDate("monday", today))
        assertEquals(LocalDate.of(2026, 3, 17), CalendarTimeResolver.relativeDate("next tuesday", today))
        assertEquals(today, CalendarTimeResolver.relativeDate("today", today))
        assertEquals(null, CalendarTimeResolver.relativeDate("whenever", today))
    }

    @Test
    fun `a local time inside the spring-forward gap is shifted and called out`() {
        // 2026-03-08 02:30 does not exist in New York (clocks jump 02:00 -> 03:00).
        val r = resolved(TimeSpec(date = "2026-03-08", hour = 2, minute = 30, hourIs24 = true), now = at("2026-03-01T12:00:00Z"))
        assertEquals(3, r.startLocal.hour)
        assertTrue(r.assumptions.any { it.contains("doesn't exist") })
        assertEquals("-04:00", r.startOffset.id)
    }

    @Test
    fun `a local time in the fall-back overlap uses the first occurrence and says so`() {
        // 2026-11-01 01:30 happens twice in New York.
        val r = resolved(TimeSpec(date = "2026-11-01", hour = 1, minute = 30, hourIs24 = true), now = at("2026-10-20T12:00:00Z"))
        assertEquals("-04:00", r.startOffset.id)
        assertTrue(r.assumptions.any { it.contains("happens twice") })
    }

    @Test
    fun `an event across a DST change keeps its real elapsed length`() {
        // 01:00 EST + 2 elapsed hours across the 2026-03-08 jump ends at 04:00 EDT local time.
        val r = resolved(TimeSpec(date = "2026-03-08", hour = 1, hourIs24 = true, durationMinutes = 120), now = at("2026-03-01T12:00:00Z"))
        assertEquals(4, r.endLocal.hour)
        assertEquals("-05:00", r.startOffset.id)
        assertEquals("-04:00", r.endOffset.id)
    }

    @Test
    fun `the description always carries date time and zone together`() {
        val d = resolved(TimeSpec(date = "2026-03-14", hour = 4, meridiem = "PM", durationMinutes = 60)).describe()
        assertTrue(d.contains("Sat 14 Mar 2026"))
        assertTrue(d.contains("4:00 PM"))
        assertTrue(d.contains("America/New_York"))
        assertTrue(d.contains("UTC-04:00"))
    }

    @Test
    fun `all day events resolve without a time`() {
        val r = resolved(TimeSpec(date = "2026-03-14", allDay = true))
        assertTrue(r.allDay)
        assertTrue(r.describe().contains("all day"))
    }

    @Test
    fun `event ids are valid base32hex and derived from the action id`() {
        val id = CalendarEventId.fromActionId("3f2b8c1e-9d4a-4c0e-8a55-1b2c3d4e5f60")
        assertTrue(CalendarEventId.isValid(id))
        assertEquals(id, CalendarEventId.fromActionId("3f2b8c1e-9d4a-4c0e-8a55-1b2c3d4e5f60"))
        assertFalse(CalendarEventId.isValid("UPPER"))
        assertFalse(CalendarEventId.isValid("abc"))
        assertFalse(CalendarEventId.isValid("has-dash"))
        assertTrue(CalendarEventId.isValid(CalendarEventId.fromActionId("not-a-uuid-at-all!!")))
    }
}
