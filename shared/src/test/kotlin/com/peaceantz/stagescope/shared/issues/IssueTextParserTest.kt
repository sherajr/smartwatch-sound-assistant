package com.peaceantz.stagescope.shared.issues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueTextParserTest {

    @Test
    fun `the spec's example splits into observation, fix and a tentative resolution`() {
        val p = IssueTextParser.parse("Log an issue: mic 12 crackled during the opening number. Swapped the cable at intermission; seems resolved.")
        assertEquals("Mic 12 crackled during the opening number", p.description)
        assertEquals("Mic 12", p.equipment)
        assertEquals("Swapped the cable at intermission", p.attemptedFix)
        assertEquals(IssueStatus.RESOLVED, p.status)
        assertEquals(ResolutionCertainty.TENTATIVE, p.certainty)
        assertEquals("seems resolved", p.resolutionNote)
        // The user's own words are kept verbatim, lead-in included.
        assertTrue(p.observation.startsWith("Log an issue:"))
    }

    @Test
    fun `an unclear sentence stays in the description instead of being guessed into a field`() {
        val p = IssueTextParser.parse("Something odd on the stage left wedge before the second act")
        assertEquals("Something odd on the stage left wedge before the second act", p.description)
        assertNull(p.attemptedFix)
        assertEquals(IssueStatus.OPEN, p.status)
    }

    @Test
    fun `still-open wording is not mistaken for a resolution`() {
        val p = IssueTextParser.parse("Wedge ringing at 2k. Tried a notch but it's still ringing.")
        assertEquals(IssueStatus.OPEN, p.status)
        assertEquals("Tried a notch but it's still ringing", p.attemptedFix)
    }

    @Test
    fun `channel and severity are only set when said`() {
        val p = IssueTextParser.parse("Critical: channel 7 dropped out for ten seconds")
        assertEquals("7", p.channel)
        assertEquals(IssueSeverity.CRITICAL, p.severity)
        assertNull(IssueTextParser.parse("Pack 4 battery low").channel)
        assertNull(IssueTextParser.parse("Pack 4 battery low").severity)
    }

    @Test
    fun `a standalone confirmed resolution is recorded as confirmed`() {
        val p = IssueTextParser.parse("Lav 3 had a loose clip. Replaced the clip. Resolved.")
        assertEquals(IssueStatus.RESOLVED, p.status)
        assertEquals(ResolutionCertainty.CONFIRMED, p.certainty)
        assertEquals("Lav 3", p.equipment)
    }
}
