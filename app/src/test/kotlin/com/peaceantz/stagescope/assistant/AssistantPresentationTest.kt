package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.KeepDraft
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.ProviderStatus
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.VoicePurpose
import com.peaceantz.stagescope.shared.show.PerformanceStatus
import com.peaceantz.stagescope.shared.show.WatchPerformance
import com.peaceantz.stagescope.shared.show.WatchProduction
import com.peaceantz.stagescope.shared.show.WatchShowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPresentationTest {

    private fun card(id: String, state: ActionState) = ActionCard(
        actionId = id, kind = ActionKind.EMAIL, state = state, revision = 1, contentHash = "h", title = "Email", summary = "To Dana",
        draft = KeepDraft("x"), statusLine = "…",
    )

    private fun thread(id: String, vararg cards: ActionCard) = ThreadView(id, 1, "Title $id", ProviderId.OPENAI, "OpenAI", "m", actions = cards.toList())

    private fun provider(id: ProviderId, hasKey: Boolean = true, validated: Boolean = true, model: String = "m") =
        ProviderStatus(id, id.label, hasKey, validated, model, true, false, false)

    // ----------------------------------------------------------------------------- attention

    @Test
    fun `only drafts that need a person appear, not finished or in-flight ones`() {
        val items = AssistantAttention.build(
            threads = listOf(
                thread(
                    "c1",
                    card("review", ActionState.AWAITING_REVIEW), card("info", ActionState.AWAITING_INFORMATION), card("phone", ActionState.AWAITING_PHONE),
                    card("unsure", ActionState.OUTCOME_UNCERTAIN), card("failed", ActionState.FAILED),
                    card("done", ActionState.COMPLETED), card("gone", ActionState.CANCELLED), card("busy", ActionState.EXECUTING), card("draft", ActionState.DRAFT),
                ),
            ),
            outbox = emptyList(), memos = emptyList(),
        )
        assertEquals(listOf("review", "info", "phone", "unsure", "failed"), items.filterIsInstance<AttentionItem.Action>().map { it.card.actionId })
        assertEquals("c1", (items.first() as AttentionItem.Action).conversationId)
    }

    @Test
    fun `stale and failed questions need a decision, others do not`() {
        fun e(id: String, s: OutboxState) = OutboxEntry(TestRequests.request(id = id), s, 0)
        val items = AssistantAttention.build(
            emptyList(),
            listOf(e("a", OutboxState.STALE), e("b", OutboxState.FAILED), e("c", OutboxState.QUEUED_OFFLINE), e("d", OutboxState.RESULT_READY), e("f", OutboxState.CANCELLED), e("g", OutboxState.EXPIRED), e("h", OutboxState.SENDING)),
            emptyList(),
        )
        assertEquals(listOf("a", "b"), items.filterIsInstance<AttentionItem.Question>().map { it.entry.requestId })
    }

    @Test
    fun `a transcript to check or a failed memo needs attention, one still in flight does not`() {
        fun m(id: String, s: MemoState) = VoiceMemo(id, "r-$id", 0, 1, 16_000, 1, VoicePurpose.DICTATION, TaskKind.FREE_CHAT, s)
        val items = AssistantAttention.build(emptyList(), emptyList(), listOf(m("1", MemoState.TRANSCRIPT_READY), m("2", MemoState.FAILED), m("3", MemoState.PENDING_PHONE), m("4", MemoState.TRANSCRIBING), m("5", MemoState.SENT)))
        assertEquals(listOf("1", "2"), items.filterIsInstance<AttentionItem.Memo>().map { it.memo.memoId })
    }

    @Test
    fun `attention keys are unique per item so a list can be keyed`() {
        val items = AssistantAttention.build(listOf(thread("c1", card("a", ActionState.AWAITING_REVIEW), card("b", ActionState.FAILED))), emptyList(), emptyList())
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    // ---------------------------------------------------------------------------------- chips

    @Test
    fun `the provider chip shows the actual model, and says plainly when there is no key or test mode`() {
        fun view(devMode: Boolean = false, selected: ProviderId = ProviderId.OPENAI, p: ProviderStatus) = ProvidersView(1, selected, listOf(p), devMode = devMode)
        assertEquals("OpenAI · gpt-6.1-sol", AssistantFormatting.providerChip(view(p = provider(ProviderId.OPENAI, model = "gpt-6.1-sol"))))
        assertEquals("Claude · no key", AssistantFormatting.providerChip(view(selected = ProviderId.ANTHROPIC, p = provider(ProviderId.ANTHROPIC, hasKey = false))))
        assertEquals("Grok · TEST MODE", AssistantFormatting.providerChip(view(devMode = true, selected = ProviderId.XAI, p = provider(ProviderId.XAI))))
        assertEquals("Providers", AssistantFormatting.providerChip(null))
    }

    @Test
    fun `provider readiness is a plain sentence plus whether it is usable`() {
        fun v(p: ProviderStatus, dev: Boolean = false) = ProvidersView(1, p.providerId, listOf(p), devMode = dev)
        assertEquals("OpenAI is ready" to true, AssistantFormatting.providerReadiness(v(provider(ProviderId.OPENAI))))
        assertEquals("OpenAI key not checked yet" to true, AssistantFormatting.providerReadiness(v(provider(ProviderId.OPENAI, validated = false))))
        assertEquals("OpenAI needs a key on your phone" to false, AssistantFormatting.providerReadiness(v(provider(ProviderId.OPENAI, hasKey = false))))
        assertEquals("Test mode (no real AI)" to false, AssistantFormatting.providerReadiness(v(provider(ProviderId.OPENAI), dev = true)))
        assertEquals("Waiting for your phone" to false, AssistantFormatting.providerReadiness(null))
    }

    @Test
    fun `the show chip follows the watch's own choice before the phone's selection`() {
        val shows = WatchShowView(
            1, listOf(WatchProduction("p1", "Our Town", "Playhouse")),
            listOf(WatchPerformance("f1", "p1", "Performance #12 · 2026-03-14 19:30", PerformanceStatus.UPCOMING), WatchPerformance("f2", "p1", "Opening night · 2026-03-01", PerformanceStatus.COMPLETED)),
            selectedProductionId = "p1", selectedPerformanceId = "f1",
        )
        assertEquals("Our Town · Performance #12", AssistantFormatting.showChip(shows, null))
        assertEquals("Our Town · Opening night", AssistantFormatting.showChip(shows, "f2"))
        assertEquals("No show selected", AssistantFormatting.showChip(null, null))
        assertEquals("Our Town", AssistantFormatting.showChip(shows.copy(selectedPerformanceId = null), null))
    }

    // ------------------------------------------------------------------------------- wording

    @Test
    fun `the page status explains pauses first, then work in progress, then readiness`() {
        val ready = ProvidersView(1, ProviderId.OPENAI, listOf(provider(ProviderId.OPENAI)))
        val noKey = ProvidersView(1, ProviderId.OPENAI, listOf(provider(ProviderId.OPENAI, hasKey = false)))
        val working = OutboxEntry(TestRequests.request(), OutboxState.RUNNING, 0, progress = ProgressStage.THINKING)

        assertEquals(PageStatus("Dictation open… measurement paused", StatusSeverity.NOTE), AssistantFormatting.pageStatus(true, ready, com.peaceantz.stagescope.audio.AudioMode.LISTENING, working))
        assertTrue(AssistantFormatting.pageStatus(true, ready, com.peaceantz.stagescope.audio.AudioMode.SPEAKING, null).text.contains("measurement paused"))
        assertTrue(AssistantFormatting.pageStatus(true, ready, com.peaceantz.stagescope.audio.AudioMode.PHONE_PLAYBACK, null).text.contains("Phone is speaking"))
        assertEquals(PageStatus("Thinking…", StatusSeverity.OK), AssistantFormatting.pageStatus(true, ready, com.peaceantz.stagescope.audio.AudioMode.IDLE, working))
        val offline = AssistantFormatting.pageStatus(false, ready, com.peaceantz.stagescope.audio.AudioMode.IDLE, null)
        assertEquals(StatusSeverity.NOTE, offline.severity)
        assertTrue(offline.text.contains("up to 3 min"))
        assertEquals(PageStatus("OpenAI needs a key on your phone", StatusSeverity.PROBLEM), AssistantFormatting.pageStatus(true, noKey, com.peaceantz.stagescope.audio.AudioMode.IDLE, null))
        assertEquals(PageStatus("Tap to ask", StatusSeverity.OK), AssistantFormatting.pageStatus(true, ready, com.peaceantz.stagescope.audio.AudioMode.IDLE, null))
    }

    @Test
    fun `progress and outbox wording restates the data`() {
        assertEquals("Waiting for your phone…", AssistantFormatting.progressText(null, null))
        assertEquals("Thinking…", AssistantFormatting.progressText(ProgressStage.THINKING, null))
        assertEquals("Using log_issue…", AssistantFormatting.progressText(ProgressStage.USING_TOOL, "log_issue"))
        assertEquals("Working…", AssistantFormatting.progressText(ProgressStage.USING_TOOL, null))
        fun e(s: OutboxState, msg: String? = null) = OutboxEntry(TestRequests.request(), s, 0, message = msg)
        assertEquals("Waiting for your phone", AssistantFormatting.outboxLabel(e(OutboxState.QUEUED_OFFLINE)))
        assertEquals("Your phone has it", AssistantFormatting.outboxLabel(e(OutboxState.ACKED)))
        assertEquals("Old question — not sent", AssistantFormatting.outboxLabel(e(OutboxState.STALE)))
        assertEquals("Your Gemini key was rejected.", AssistantFormatting.outboxLabel(e(OutboxState.FAILED, "Your Gemini key was rejected.")))
    }

    @Test
    fun `ages read naturally`() {
        val now = 10_000_000L
        assertEquals("just now", AssistantFormatting.ago(now - 2_000, now))
        assertEquals("30 s ago", AssistantFormatting.ago(now - 30_000, now))
        assertEquals("4 min ago", AssistantFormatting.ago(now - 4 * 60_000, now))
        assertEquals("3 h ago", AssistantFormatting.ago(now - 3 * 3_600_000L, now))
        assertEquals("just now", AssistantFormatting.ago(now + 5_000, now))
    }

    @Test
    fun `usage shows tokens and a cost that says whether it is reported, estimated or unknown`() {
        assertEquals("1.2k in · 300 out · \$0.0042 est.", AssistantFormatting.usageLine(UsageSummary(1_200, 0, 300, estimatedCostMicros = 4_200)))
        assertEquals("100 in · 20 out · \$0.50 reported", AssistantFormatting.usageLine(UsageSummary(100, 0, 20, estimatedCostMicros = 1, reportedCostMicros = 500_000)))
        assertEquals("100 in · 20 out · cost unknown", AssistantFormatting.usageLine(UsageSummary(100, 0, 20)))
        assertNull(AssistantFormatting.usageLine(UsageSummary()))
        assertNull(AssistantFormatting.usageLine(null))
        assertTrue(AssistantFormatting.usd(2_500_000).startsWith("$2.50"))
        assertFalse(AssistantFormatting.usd(10).contains("0.00 "))
    }

    @Test
    fun `the spoken summary is bounded and prefers the compact summary`() {
        val v = ThreadView("c", 1, "t", ProviderId.OPENAI, "OpenAI", "m", summary = "Short.", detail = "x".repeat(5_000))
        assertEquals("Short.", AssistantFormatting.spokenSummary(v))
        assertEquals(600, AssistantFormatting.spokenSummary(v.copy(summary = null)).length)
        assertEquals("", AssistantFormatting.spokenSummary(v.copy(summary = null, detail = null)))
    }

    // ------------------------------------------------------------------------------- long text

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `long text is cut at blank lines first, and no words are lost`() {
        val text = "Observed: one peak at 2109 Hz.\n\nPlausible explanations: feedback or a held note.\n  \nNext check: mute the channel.\nThen look again."
        val parts = AssistantFormatting.paragraphs(text)
        assertEquals(3, parts.size)
        assertEquals("a single newline stays inside its paragraph", "Next check: mute the channel.\nThen look again.", parts.last())
        assertEquals(words(text), parts.flatMap { words(it) })
    }

    @Test
    fun `a long paragraph is packed into whole sentences, never cut mid-sentence`() {
        val sentence = "This sentence is exactly forty-two chars."
        val paragraph = List(12) { sentence }.joinToString(" ")
        val parts = AssistantFormatting.paragraphs(paragraph, maxChunk = 100)
        assertTrue("split into several pieces", parts.size > 3)
        assertTrue(parts.all { it.length <= 100 })
        assertTrue("every piece is whole sentences", parts.all { p -> p.split(Regex("(?<=[.!?])\\s+")).all { it == sentence } })
        assertEquals(paragraph, parts.joinToString(" "))
    }

    @Test
    fun `a single sentence longer than the limit stays whole, and empty text gives nothing`() {
        val long = "word ".repeat(80).trim() + "."
        assertEquals(listOf(long), AssistantFormatting.paragraphs(long, maxChunk = 50))
        assertTrue(AssistantFormatting.paragraphs("").isEmpty())
        assertTrue(AssistantFormatting.paragraphs(" \n\n \n").isEmpty())
    }
}
