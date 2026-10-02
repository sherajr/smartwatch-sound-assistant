package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.core.NeutralMessage
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.data.AcceptResult
import com.peaceantz.stagescope.phone.data.ClaimResult
import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.phone.data.UsageEntry
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.CompletionEvidence
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.assistant.TurnRole
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueStatus
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.RequestState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrchestratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun harness(vararg adapters: ScriptedAdapter): Harness =
        Harness(tmp.newFolder(), adapters.associateBy { it.id }.toMutableMap<ProviderId, com.peaceantz.stagescope.phone.ai.core.ProviderAdapter>())

    private fun Harness.lastAssistant() =
        data.conversations.get("conv-1")!!.turns.last { it.role == TurnRole.ASSISTANT }

    @Test
    fun `a text reply is stored with provider and model identity and its watch summary split off`() = runBlocking {
        val openai = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(ScriptedAdapter.text("<watch_summary>Likely a narrow ring near 2 kHz.</watch_summary>\nHere is the full reasoning.")))
        val h = harness(openai)
        h.withShows()
        val out = h.orchestrator.run(Samples.request("What do you think?", kind = TaskKind.FREE_CHAT)) { false }

        assertEquals(RequestState.COMPLETED, out.state)
        val turn = h.lastAssistant()
        assertEquals("Here is the full reasoning.", turn.text)
        assertEquals("Likely a narrow ring near 2 kHz.", turn.watchSummary)
        assertEquals(ProviderId.OPENAI, turn.providerId)
        assertEquals("gpt-6.1-sol", turn.modelId)
        assertEquals(1, h.data.usage.state.value.entries.size)
        assertEquals(RequestState.COMPLETED, h.events.results.single().second)
        assertTrue(h.events.progress.contains(ProgressStage.THINKING))
    }

    @Test
    fun `a tool call runs once, validates outside the model, and the result goes back`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(
                ScriptedAdapter.tool("log_issue", """{"description":"Mic 12 crackled during the opening number","equipment":"Mic 12","attempted_fix":"Swapped the cable at intermission","resolution":"seems_resolved","resolution_note":"seems resolved"}"""),
                ScriptedAdapter.text("<watch_summary>Logged.</watch_summary>Saved the issue."),
            ),
        )
        val h = harness(openai)
        h.withShows()
        h.orchestrator.run(Samples.request()) { false }

        val issues = IssueLedger.views(h.data.issues.state.value)
        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals("Mic 12 crackled during the opening number", issue.description)
        assertEquals("Swapped the cable at intermission", issue.attemptedFix)
        assertEquals(IssueStatus.RESOLVED, issue.status)
        assertEquals(ResolutionCertainty.TENTATIVE, issue.certainty)
        assertEquals("perf-1", issue.performanceId)
        // The person's own words are stored verbatim, separate from the model's wording.
        assertTrue(issue.originalObservation.startsWith("Log an issue: mic 12 crackled"))

        // The second call carried the result for call_1 and the first call's opaque state.
        assertEquals(2, openai.requests.size)
        val second = openai.requests[1]
        assertSame(null, openai.requests[0].loop)
        assertNotNull(second.loop)
        assertEquals("call_1", second.toolResults.single().callId)
        assertFalse(second.toolResults.single().isError)

        val turn = h.lastAssistant()
        val action = h.data.actions.get(turn.actionIds.single())!!
        assertEquals(ActionState.COMPLETED, action.state)
        assertEquals(CompletionEvidence.LOCAL_WRITE, action.completionEvidence)
        assertEquals("log_issue", turn.toolOutcomes.single().toolName)
    }

    @Test
    fun `invalid or unapproved arguments are rejected and nothing runs`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(
                ScriptedAdapter.tool("log_issue", """{"severity":"catastrophic"}""", "c1"),
                ScriptedAdapter.tool("log_issue", """{"description":"x","approved":true}""", "c2"),
                ScriptedAdapter.tool("log_issue", """{"description":"incomplete""", "c3"),
                ScriptedAdapter.tool("does_not_exist", "{}", "c4"),
                ScriptedAdapter.text("Sorry, I couldn't log that."),
            ),
        )
        val h = harness(openai)
        h.withShows()
        h.orchestrator.run(Samples.request()) { false }

        assertTrue("nothing was logged", IssueLedger.views(h.data.issues.state.value).isEmpty())
        val results = openai.requests.drop(1).map { it.toolResults.single() }
        assertTrue(results.all { it.isError })
        assertTrue(results[0].content.contains("description is required"))
        assertTrue(results[1].content.contains("not a known parameter") && results[1].content.contains("only the person can approve"))
        assertTrue(results[2].content.contains("nothing was done"))
        assertTrue(results[3].content.contains("Unknown tool"))
        assertTrue(h.lastAssistant().toolOutcomes.all { it.status == com.peaceantz.stagescope.shared.assistant.ToolStatus.REJECTED })
    }

    @Test
    fun `a model cannot confirm its own draft - the action stays awaiting review with no confirmation`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(
                ScriptedAdapter.tool("draft_email", """{"purpose":"performance_report","to":["Dana"],"subject":"Report","body":"Dialogue clarity was good.","confirmed":true}""", "c1"),
                ScriptedAdapter.tool("draft_email", """{"purpose":"performance_report","to":["Dana"],"subject":"Report","body":"Dialogue clarity was good."}""", "c2"),
                ScriptedAdapter.text("<watch_summary>Draft ready.</watch_summary>Review it in the app."),
            ),
        )
        val h = harness(openai)
        h.withShows()
        h.orchestrator.run(Samples.request("Email tonight's report", kind = TaskKind.EMAIL_REPORT)) { false }

        val action = h.data.actions.all().single()
        assertEquals(ActionState.AWAITING_REVIEW, action.state)
        assertNull(action.confirmation)
        assertTrue(openai.requests[1].toolResults.single().isError) // the 'confirmed' flag was refused
        assertFalse(h.lastAssistant().text.lowercase().contains("sent"))
    }

    @Test
    fun `switching provider keeps visible context but replays no tools and no vendor state`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(
                ScriptedAdapter.tool("log_issue", """{"description":"Mic 12 crackled"}"""),
                ScriptedAdapter.text("Saved the issue."),
            ),
        )
        val claude = ScriptedAdapter(ProviderId.ANTHROPIC, mutableListOf(ScriptedAdapter.text("Following up on that.")))
        val h = harness(openai, claude)
        h.withShows()
        h.orchestrator.run(Samples.request("Log an issue: mic 12 crackled", id = "req-1")) { false }
        h.data.settings.update { it.copy(selectedProvider = ProviderId.ANTHROPIC) }
        h.orchestrator.run(Samples.request("What else should I check?", id = "req-2", kind = TaskKind.FREE_CHAT)) { false }

        val first = claude.requests.single()
        assertNull("no vendor-specific continuation crosses providers", first.loop)
        assertTrue(first.toolResults.isEmpty())
        val texts = first.history.map { it.text }
        assertTrue(texts.any { it.contains("Log an issue: mic 12 crackled") })
        assertTrue(texts.any { it.contains("Saved the issue.") })
        assertTrue("it is told what was already done so it cannot repeat it", texts.any { it.contains("already completed") && it.contains("log_issue") })
        assertEquals(1, IssueLedger.views(h.data.issues.state.value).size)

        val turns = h.data.conversations.get("conv-1")!!.turns.filter { it.role == TurnRole.ASSISTANT }
        assertEquals(listOf(ProviderId.OPENAI, ProviderId.ANTHROPIC), turns.map { it.providerId })
        assertEquals("claude-opus-5-5", turns.last().modelId)
    }

    @Test
    fun `a failing provider is reported as itself and no other provider is tried or charged`() = runBlocking {
        val openai = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(ProviderException(TurnErrorKind.INVALID_KEY, "The API key was rejected.")))
        val claude = ScriptedAdapter(ProviderId.ANTHROPIC, mutableListOf(ScriptedAdapter.text("should never run")))
        val h = harness(openai, claude)
        h.withShows()
        val out = h.orchestrator.run(Samples.request("Hello", kind = TaskKind.FREE_CHAT)) { false }

        assertEquals(RequestState.FAILED, out.state)
        assertEquals(TurnErrorKind.INVALID_KEY, out.error!!.kind)
        assertEquals(0, claude.calls)
        val turn = h.lastAssistant()
        assertEquals(ProviderId.OPENAI, turn.providerId)
        assertEquals("gpt-6.1-sol", turn.modelId)
    }

    @Test
    fun `transient failures retry only before any output and never mid-stream`() = runBlocking {
        val rate = ProviderException(TurnErrorKind.RATE_LIMITED, "slow down", retryAfterMs = 2_000, retryable = true)
        val flaky = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(rate, rate, ScriptedAdapter.text("ok after retries")))
        val h = harness(flaky)
        h.withShows()
        h.orchestrator.run(Samples.request("Hi", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(3, flaky.calls)
        assertEquals(listOf(2_000L, 2_000L), h.backoffs)
        assertEquals("ok after retries", h.lastAssistant().text)

        val tmp2 = tmp.newFolder()
        val midStream = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(ScriptedAdapter.Emit("Partial answer", rate), ScriptedAdapter.text("must not be used")))
        val h2 = Harness(tmp2, mutableMapOf(ProviderId.OPENAI to midStream))
        h2.withShows()
        val out = h2.orchestrator.run(Samples.request("Hi", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(1, midStream.calls)
        assertEquals(RequestState.FAILED, out.state)
        assertTrue(h2.data.conversations.get("conv-1")!!.turns.last().text.contains("Partial answer"))
        assertTrue(h2.backoffs.isEmpty())
    }

    @Test
    fun `the daily request limit blocks before any provider call`() = runBlocking {
        val openai = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(ScriptedAdapter.text("no")))
        val h = harness(openai)
        h.withShows()
        h.data.settings.update { it.copy(limits = it.limits.copy(maxRequestsPerDay = 2)) }
        repeat(2) { h.data.usage.record(UsageEntry("r$it", h.now, ProviderId.OPENAI, "gpt-6.1-sol", UsageSummary(inputTokens = 1))) }
        val out = h.orchestrator.run(Samples.request("Hi", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(TurnErrorKind.LIMIT_REACHED, out.error!!.kind)
        assertEquals(0, openai.calls)
    }

    @Test
    fun `test-mode answers are recorded as tests and never count against the daily request limit`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(ScriptedAdapter.text("Fake 1."), ScriptedAdapter.text("Fake 2."), ScriptedAdapter.text("Fake 3."), ScriptedAdapter.text("Real.")),
        )
        val h = harness(openai)
        h.withShows()
        h.data.settings.update { it.copy(limits = it.limits.copy(maxRequestsPerDay = 1), devMode = true) }
        repeat(3) { h.orchestrator.run(Samples.request("Hi $it", id = "t-$it", kind = TaskKind.FREE_CHAT)) { false } }
        assertEquals(listOf("test"), h.data.usage.state.value.entries.map { it.kind }.distinct())
        assertEquals("three test requests, none blocked by a limit of one", 3, h.data.usage.state.value.entries.size)

        // Leaving test mode, the real allowance is untouched: the first real request still goes through, the second is blocked.
        h.data.settings.update { it.copy(devMode = false) }
        val first = h.orchestrator.run(Samples.request("Real one", id = "r-1", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(RequestState.COMPLETED, first.state)
        val second = h.orchestrator.run(Samples.request("Real two", id = "r-2", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(TurnErrorKind.LIMIT_REACHED, second.error!!.kind)
    }

    @Test
    fun `a provider with no key gives an actionable setup state, never a fake answer`() = runBlocking {
        val h = harness() // no adapters registered => no key
        h.withShows()
        val out = h.orchestrator.run(Samples.request("Hi", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(TurnErrorKind.MISSING_KEY, out.error!!.kind)
        assertTrue(out.error.message.contains("Providers"))
    }

    @Test
    fun `the tool loop is bounded`() = runBlocking {
        val loops = MutableList<Any>(10) { ScriptedAdapter.tool("list_issues", "{}", "c$it") }
        val openai = ScriptedAdapter(ProviderId.OPENAI, loops)
        val h = harness(openai)
        h.withShows()
        val out = h.orchestrator.run(Samples.request("Hi", kind = TaskKind.FREE_CHAT)) { false }
        assertEquals(TurnErrorKind.TOOL_LOOP_LIMIT, out.error!!.kind)
        assertEquals(h.data.settings.value.limits.maxToolLoops, openai.calls)
    }

    @Test
    fun `cancelling keeps a record and says completed actions are not undone`() = runBlocking {
        val openai = ScriptedAdapter(
            ProviderId.OPENAI,
            mutableListOf(ScriptedAdapter.tool("log_issue", """{"description":"Second issue"}""", "c1"), ScriptedAdapter.text("never reached")),
        )
        val h = harness(openai)
        h.withShows()
        var checks = 0
        // Cancel arrives after the tool ran but before the next model call.
        val out = h.orchestrator.run(Samples.request(id = "req-9")) { ++checks > 2 }
        assertEquals(RequestState.CANCELLED, out.state)
        assertEquals(TurnErrorKind.CANCELLED, out.error!!.kind)
        assertTrue(out.error.message.contains("not undone"))
        assertEquals("the issue saved before cancel stays", 1, IssueLedger.views(h.data.issues.state.value).size)
        assertEquals(RequestState.CANCELLED, h.events.results.single().second)
    }
    @Test
    fun `inbox accepts a request id once and an interrupted worker is never silently re-run`() = runBlocking {
        val h = harness()
        val req = Samples.request()
        assertTrue(h.data.inbox.accept(req, "watch", h.now) is AcceptResult.New)
        assertTrue(h.data.inbox.accept(req, "watch", h.now) is AcceptResult.Duplicate)

        assertTrue(h.data.inbox.claim(req.requestId, h.now) is ClaimResult.Claimed)
        // A second claim means the previous worker died while RUNNING: interrupted, not re-run.
        assertTrue(h.data.inbox.claim(req.requestId, h.now) is ClaimResult.Interrupted)
        assertEquals(InboxState.INTERRUPTED, h.data.inbox.get(req.requestId)!!.state)
        assertTrue(h.data.inbox.claim(req.requestId, h.now) is ClaimResult.AlreadyFinished)
    }

    @Test
    fun `a request interrupted by a dead worker is recorded honestly, never re-run, and never left silent`() = runBlocking {
        val openai = ScriptedAdapter(ProviderId.OPENAI, mutableListOf(ScriptedAdapter.text("must never be requested")))
        val h = harness(openai)
        h.withShows()
        val req = Samples.request("Is the lav on mic 3 ringing?", id = "req-int", kind = TaskKind.FREE_CHAT)
        h.data.inbox.accept(req, "watch", h.now)
        h.data.inbox.claim(req.requestId, h.now) // worker 1 starts and dies
        val second = h.data.inbox.claim(req.requestId, h.now) as ClaimResult.Interrupted // worker 2 finds it RUNNING

        val out = h.orchestrator.recordInterrupted(second.request)

        assertEquals("no hidden second model call (and charge)", 0, openai.calls)
        assertEquals(RequestState.FAILED, out.state)
        assertEquals(InboxState.INTERRUPTED, h.data.inbox.get("req-int")!!.state)
        val turns = h.data.conversations.get("conv-1")!!.turns
        assertEquals("the person's own words are kept", "Is the lav on mic 3 ringing?", turns.first { it.role == TurnRole.USER }.text)
        val reply = turns.last { it.role == TurnRole.ASSISTANT }
        assertTrue(reply.error!!.retryable)
        assertTrue(reply.text.contains("Retry"))
        assertEquals(RequestState.FAILED, h.events.results.single().second)

        // Recording it again (e.g. a second worker wake-up) doesn't duplicate anything.
        h.orchestrator.recordInterrupted(second.request)
        assertEquals(2, h.data.conversations.get("conv-1")!!.turns.size)
    }

    @Test
    fun `the history sent to a provider is bounded and ends with the current request`() = runBlocking {
        val openai = ScriptedAdapter(ProviderId.OPENAI, MutableList(40) { ScriptedAdapter.text("answer $it") })
        val h = harness(openai)
        h.withShows()
        repeat(30) { i -> h.orchestrator.run(Samples.request("question $i", id = "r$i", kind = TaskKind.FREE_CHAT)) { false } }
        val last = openai.requests.last()
        assertTrue(last.history.size <= 20)
        assertTrue((last.history.last() as NeutralMessage.User).text.startsWith("question 29"))
        assertTrue(last.history.first().text.contains("summary") || last.history.first() is NeutralMessage.User)
    }
}
