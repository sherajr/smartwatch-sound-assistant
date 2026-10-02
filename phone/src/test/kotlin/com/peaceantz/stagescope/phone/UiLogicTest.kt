package com.peaceantz.stagescope.phone

import androidx.compose.ui.text.font.FontWeight
import com.peaceantz.stagescope.phone.ui.DeepLink
import com.peaceantz.stagescope.phone.ui.Handoffs
import com.peaceantz.stagescope.phone.ui.boldMarkdown
import com.peaceantz.stagescope.phone.ui.cardIdsUnder
import com.peaceantz.stagescope.phone.ui.cardListIndex
import com.peaceantz.stagescope.phone.ui.cardOwners
import com.peaceantz.stagescope.phone.ui.parseAddresses
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.assistant.ChatTurn
import com.peaceantz.stagescope.shared.assistant.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeepLinkTest {
    @Test
    fun `a conversation or action link carries only its opaque id`() {
        assertEquals(DeepLink("conv-1", null), DeepLink.parse("stagescope", "task", listOf("conversation", "conv-1")))
        assertEquals(DeepLink(null, "7b0c-9f"), DeepLink.parse("stagescope", "task", listOf("action", "7b0c-9f")))
    }

    @Test
    fun `anything else is rejected`() {
        assertNull(DeepLink.parse("https", "task", listOf("conversation", "x")))
        assertNull(DeepLink.parse("stagescope", "other", listOf("conversation", "x")))
        assertNull(DeepLink.parse("stagescope", "task", listOf("conversation")))
        assertNull(DeepLink.parse("stagescope", "task", listOf("email", "x")))
        assertNull(DeepLink.parse("stagescope", "task", listOf("conversation", "has space")))
        assertNull(DeepLink.parse("stagescope", "task", listOf("conversation", "../etc")))
        assertNull(DeepLink.parse("stagescope", "task", listOf("conversation", "x".repeat(81))))
        assertNull(DeepLink.parse(null, null, emptyList()))
    }
}

class AddressParsingTest {
    @Test
    fun `plain, named and mixed lists parse`() {
        val (list, error) = parseAddresses("dana@theatre.example, Priya Shah <priya@theatre.example>; \"Lee, Sam\" <sam@x.example>")
        assertNull(error)
        assertEquals(listOf("dana@theatre.example", "priya@theatre.example", "sam@x.example"), list.map { it.address })
        assertEquals(listOf(null, "Priya Shah", "Lee, Sam"), list.map { it.name }.let { names -> names })
    }

    @Test
    fun `blank input is an empty list, not an error`() {
        assertEquals(emptyList<Any>() to null, parseAddresses("  ").let { it.first to it.second })
    }

    @Test
    fun `the first invalid address is reported and nothing is returned`() {
        val (list, error) = parseAddresses("ok@fine.example, not-an-address, other@fine.example")
        assertTrue(list.isEmpty())
        assertNotNull(error)
        assertTrue(error!!.contains("not-an-address"))
    }

    @Test
    fun `header injection attempts are not valid addresses`() {
        val (list, error) = parseAddresses("a@b.example\nBcc: evil@x.example")
        // The newline splits it into two tokens; the second isn't an address.
        assertTrue(list.isEmpty())
        assertNotNull(error)
    }
}

class HandoffTimeTest {
    private fun draft(start: String, end: String, so: String, eo: String, allDay: Boolean = false) = CalendarDraft(
        title = "Sound check", eventId = "abcdef0123456789", timezoneId = "America/New_York",
        startLocal = start, endLocal = end, startOffset = so, endOffset = eo, allDay = allDay,
    )

    @Test
    fun `a timed event is converted using its own persisted offsets`() {
        val (begin, end) = Handoffs.calendarWindow(draft("2026-11-07T19:30", "2026-11-07T20:30", "-05:00", "-05:00"))!!
        assertEquals(Instant.parse("2026-11-08T00:30:00Z").toEpochMilli(), begin)
        assertEquals(Instant.parse("2026-11-08T01:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `an event across the spring-forward gap lasts its real elapsed time`() {
        // 01:30 EST to 03:30 EDT on 2026-03-08 is one hour, not two.
        val (begin, end) = Handoffs.calendarWindow(draft("2026-03-08T01:30", "2026-03-08T03:30", "-05:00", "-04:00"))!!
        assertEquals(3_600_000L, end - begin)
    }

    @Test
    fun `an all-day event uses UTC midnights as Calendar expects`() {
        val (begin, end) = Handoffs.calendarWindow(draft("2026-11-07T00:00", "2026-11-08T00:00", "-05:00", "-05:00", allDay = true))!!
        assertEquals(Instant.parse("2026-11-07T00:00:00Z").toEpochMilli(), begin)
        assertEquals(Instant.parse("2026-11-08T00:00:00Z").toEpochMilli(), end)
    }

    @Test
    fun `an unresolved draft yields no window rather than a wrong one`() {
        assertNull(Handoffs.calendarWindow(draft("", "", "", "")))
    }
}

class MarkdownTest {
    @Test
    fun `bold spans render and everything else stays plain`() {
        val s = boldMarkdown("Observed: **narrow peak at 2.1 kHz** with a second one")
        assertEquals("Observed: narrow peak at 2.1 kHz with a second one", s.text)
        val span = s.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals("narrow peak at 2.1 kHz", s.text.substring(span.start, span.end))
    }

    @Test
    fun `an unmatched marker is left as typed`() {
        assertEquals("a ** b", boldMarkdown("a ** b").text)
        assertTrue(boldMarkdown("a ** b").spanStyles.isEmpty())
    }
}

/** How a conversation is laid out as a flat list, and where a "continue on phone" link to one action must scroll to. */
class ChatCardLayoutTest {
    private fun turn(id: String, role: TurnRole = TurnRole.ASSISTANT, actions: List<String> = emptyList()) =
        ChatTurn(id = id, role = role, text = id, seq = 1, atEpochMs = 0, actionIds = actions)

    private val everything: (String) -> Boolean = { true }

    @Test
    fun `an action card is drawn once, under the latest turn that mentions it`() {
        val turns = listOf(turn("t1", TurnRole.USER), turn("t2", actions = listOf("a", "b")), turn("t3", actions = listOf("b", "b", "c")))
        assertEquals(mapOf("a" to "t2", "b" to "t3", "c" to "t3"), cardOwners(turns))
        val owners = cardOwners(turns)
        assertEquals(listOf("a"), cardIdsUnder(turns[1], owners))
        assertEquals("a repeated id is not drawn twice", listOf("b", "c"), cardIdsUnder(turns[2], owners))
        assertEquals(emptyList<String>(), cardIdsUnder(turns[0], owners))
    }

    @Test
    fun `the list position counts leading items, then each turn and each card under it`() {
        val turns = listOf(turn("t1", TurnRole.USER), turn("t2", actions = listOf("email", "calendar", "keep")), turn("t3"))
        // [0] t1  [1] t2  [2] email  [3] calendar  [4] keep  [5] t3
        assertEquals(2, cardListIndex(turns, leadingItems = 0, actionId = "email", exists = everything))
        assertEquals(3, cardListIndex(turns, 0, "calendar", everything))
        assertEquals(4, cardListIndex(turns, 0, "keep", everything))
        // a measurements panel (or the empty-state panel) before the turns pushes everything down by one
        assertEquals(4, cardListIndex(turns, 1, "calendar", everything))
    }

    @Test
    fun `a card that is not drawn is skipped, and an unknown or missing one has no position`() {
        val turns = listOf(turn("t1", actions = listOf("gone", "kept")), turn("t2"))
        val exists: (String) -> Boolean = { it != "gone" }
        assertEquals("the missing record takes no slot", 1, cardListIndex(turns, 0, "kept", exists))
        assertNull(cardListIndex(turns, 0, "gone", exists))
        assertNull(cardListIndex(turns, 0, "never-heard-of-it", everything))
        assertNull(cardListIndex(emptyList(), 0, "kept", everything))
    }

    @Test
    fun `a later edit that mentions the same action moves its card to that turn`() {
        val turns = listOf(turn("t1", actions = listOf("draft")), turn("t2"), turn("t3", actions = listOf("draft")))
        // [0] t1  [1] t2  [2] t3  [3] draft  -- drawn under t3 only
        assertEquals(3, cardListIndex(turns, 0, "draft", everything))
    }
}
