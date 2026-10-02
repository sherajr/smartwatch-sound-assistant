package com.peaceantz.stagescope.shared.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowTest {

    private fun turn(i: Int, role: TurnRole = if (i % 2 == 0) TurnRole.USER else TurnRole.ASSISTANT, text: String = "turn $i", outcomes: List<ToolOutcome> = emptyList()) =
        ChatTurn(id = "t$i", role = role, text = text, seq = i.toLong(), atEpochMs = i.toLong(), toolOutcomes = outcomes)

    private fun conversation(turns: List<ChatTurn>) = Conversation(
        id = "c", title = "t", createdAtEpochMs = 0, updatedAtEpochMs = 0, turns = turns,
        providerId = ProviderId.OPENAI, modelId = "gpt-6.1-sol",
    )

    @Test
    fun `a short conversation is passed through whole with no summary`() {
        val w = ConversationWindow.build(conversation((0 until 6).map { turn(it) }))
        assertEquals(6, w.turns.size)
        assertNull(w.summaryText)
    }

    @Test
    fun `a long conversation keeps recent turns verbatim and summarises the rest deterministically`() {
        val turns = (0 until 40).map { turn(it, text = "message number $it") }
        val a = ConversationWindow.build(conversation(turns))
        val b = ConversationWindow.build(conversation(turns))
        assertEquals(a, b)
        assertEquals(ConversationWindow.MAX_RECENT_TURNS, a.turns.size)
        assertEquals("t39", a.turns.last().id)
        assertTrue(a.summaryText!!.contains("message number 0"))
        assertTrue(a.summaryText.length <= ConversationWindow.MAX_SUMMARY_CHARS)
    }

    @Test
    fun `the character budget also bounds the window`() {
        val big = "x".repeat(5_000)
        val w = ConversationWindow.build(conversation((0 until 10).map { turn(it, text = big) }))
        assertTrue(w.turns.sumOf { it.text.length } <= ConversationWindow.MAX_RECENT_CHARS || w.turns.size == 2)
        assertTrue(w.summaryText != null)
    }

    @Test
    fun `summaries record what StageScope already did so another provider cannot repeat it`() {
        val outcome = ToolOutcome("c1", "draft_email", "to PM", "Email draft created and sent for review", ToolStatus.OK, producedActionId = "act-1")
        val turns = (0 until 30).map { i -> if (i == 3) turn(i, text = "email the PM", outcomes = listOf(outcome)) else turn(i) }
        val summary = ConversationWindow.build(conversation(turns)).summaryText!!
        assertTrue(summary.contains("StageScope already did: draft_email"))
    }

    @Test
    fun `provider identity is stored per turn so a switch is visible and context survives it`() {
        val turns = listOf(
            turn(0), turn(1).copy(providerId = ProviderId.OPENAI, modelId = "gpt-6.1-sol"),
            turn(2), turn(3).copy(providerId = ProviderId.ANTHROPIC, modelId = "claude-opus-5-5"),
        )
        val conv = conversation(turns).copy(providerId = ProviderId.ANTHROPIC, modelId = "claude-opus-5-5")
        val w = ConversationWindow.build(conv)
        assertEquals(4, w.turns.size)
        assertEquals(setOf(ProviderId.OPENAI, ProviderId.ANTHROPIC), w.turns.mapNotNull { it.providerId }.toSet())
        assertFalse(w.turns.any { it.text.contains("signature") })
    }
}
