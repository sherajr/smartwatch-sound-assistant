package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.assistant.ActionExecutor
import com.peaceantz.stagescope.phone.assistant.ConfirmOutcome
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.google.CalendarClient
import com.peaceantz.stagescope.phone.google.GmailClient
import com.peaceantz.stagescope.phone.google.GoogleAuthorizer
import com.peaceantz.stagescope.phone.google.GoogleIdentity
import com.peaceantz.stagescope.phone.google.TokenResult
import com.peaceantz.stagescope.shared.actions.ActionEvent
import com.peaceantz.stagescope.shared.actions.ActionMachine
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.AttemptOutcome
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.actions.CompletionEvidence
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.EmailPurpose
import com.peaceantz.stagescope.shared.actions.RejectReason
import com.peaceantz.stagescope.shared.show.EmailAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.TimeUnit

class ActionExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var data: PhoneData
    private lateinit var executor: ActionExecutor
    private var now = 1_800_000_000_000L
    private var tokenResult: TokenResult = TokenResult.Token("tok", GoogleIdentity("acct-1", "sound@example.com"))
    private val changes = mutableListOf<ActionState>()

    private val auth = object : GoogleAuthorizer {
        override suspend fun accessToken(scopes: List<String>): TokenResult = tokenResult
        override suspend fun revoke(scopes: List<String>) = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        data = PhoneData(tmp.newFolder())
        val http = ProviderHttp(OkHttpClient(), setOf(server.hostName), requireHttps = false)
        executor = ActionExecutor(
            data, GmailClient(http, server.url("/").toString()), CalendarClient(http, server.url("/").toString(), sleeper = {}),
            auth, clock = { now }, onChanged = { changes += it.state },
        )
        runBlocking { data.settings.update { it.copy(gmailGranted = true, calendarGranted = true, googleAccountId = "acct-1", googleAccountEmail = "sound@example.com") } }
    }

    @After
    fun tearDown() { runCatching { server.shutdown() } }

    private val emailDraft = EmailDraft(
        EmailPurpose.PERFORMANCE_REPORT, to = listOf(EmailAddress("dana@theatre.example", "Dana")),
        subject = "Sound report", body = "Dialogue clarity was good.", senderAccount = "sound@example.com",
    )

    private suspend fun newEmail(draft: EmailDraft = emailDraft): ActionRecord =
        ActionMachine.create("action-1", draft, now, "conv-1").also { data.actions.put(it) }

    private suspend fun confirm(r: ActionRecord, opId: String = "op-1", by: ConfirmSource = ConfirmSource.WATCH) =
        executor.confirm(r.actionId, opId, r.revision, r.contentHash, by)

    private fun sentOk() = server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"msg-777","threadId":"th-1"}"""))

    @Test
    fun `a confirmed email is sent once from the confirmed account and records the real receipt`() = runBlocking {
        val r = newEmail()
        sentOk()
        val out = confirm(r) as ConfirmOutcome.Ran
        assertEquals(ActionState.COMPLETED, out.record.state)
        assertEquals(CompletionEvidence.API_CONFIRMED, out.record.completionEvidence)
        assertEquals("msg-777", out.record.receipt!!.externalId)
        assertEquals(1, server.requestCount)
        val raw = com.peaceantz.stagescope.phone.ai.core.VendorJson.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            .let { (it as kotlinx.serialization.json.JsonObject)["raw"] as kotlinx.serialization.json.JsonPrimitive }.content
        val mime = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(mime.contains("From: sound@example.com"))
        assertTrue(mime.contains("To: Dana <dana@theatre.example>"))
        assertTrue(changes.contains(ActionState.EXECUTING) && changes.contains(ActionState.COMPLETED))
    }

    @Test
    fun `simultaneous confirmations from the watch and the phone send exactly once`() = runBlocking {
        val r = newEmail()
        sentOk()
        val results = listOf(
            async(Dispatchers.Default) { confirm(r, "watch-op", ConfirmSource.WATCH) },
            async(Dispatchers.Default) { confirm(r, "phone-op", ConfirmSource.PHONE) },
            async(Dispatchers.Default) { confirm(r, "watch-op-retry", ConfirmSource.WATCH) },
        ).awaitAll()
        assertEquals("only one Gmail call may ever be made", 1, server.requestCount)
        assertEquals(1, results.count { it is ConfirmOutcome.Ran })
        assertEquals(1, data.actions.get("action-1")!!.attempts.size)
        assertTrue(results.filterIsInstance<ConfirmOutcome.Refused>().all { it.reason == RejectReason.ALREADY_IN_PROGRESS || it.reason == RejectReason.ALREADY_DONE })
    }

    @Test
    fun `a retransmitted confirm after completion does not send again`() = runBlocking {
        val r = newEmail()
        sentOk()
        confirm(r, "op-1")
        val again = confirm(r, "op-1") as ConfirmOutcome.Refused
        assertEquals(RejectReason.DUPLICATE_COMMAND, again.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `changing the draft after review makes the old confirmation fail and sends nothing`() = runBlocking {
        val r = newEmail()
        executor.replaceDraft(r.actionId, emailDraft.copy(body = "A different body"))
        val refused = confirm(r) as ConfirmOutcome.Refused // r still carries the OLD revision/hash
        assertTrue(refused.reason == RejectReason.STALE_REVISION || refused.reason == RejectReason.CONTENT_CHANGED)
        assertEquals(0, server.requestCount)
        assertNull(data.actions.get(r.actionId)!!.confirmation)
    }

    @Test
    fun `an ambiguous send is never retried and the person resolves it`() = runBlocking {
        val r = newEmail()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val out = confirm(r, "op-1") as ConfirmOutcome.Ran
        assertEquals(ActionState.OUTCOME_UNCERTAIN, out.record.state)
        assertEquals(AttemptOutcome.AMBIGUOUS, out.record.attempts.single().outcome)
        assertTrue(out.message.contains("Outcome unknown"))

        val retry = confirm(data.actions.get(r.actionId)!!, "op-2") as ConfirmOutcome.Refused
        assertEquals(RejectReason.OUTCOME_UNKNOWN, retry.reason)
        assertEquals("no automatic or accidental re-send", 1, server.requestCount)

        // The person checks Gmail: "it was not sent" -> back to review, and a NEW confirmation sends it.
        executor.resolveUncertain(r.actionId, wasDone = false)
        val reopened = data.actions.get(r.actionId)!!
        assertEquals(ActionState.AWAITING_REVIEW, reopened.state)
        sentOk()
        val second = confirm(reopened, "op-3") as ConfirmOutcome.Ran
        assertEquals(ActionState.COMPLETED, second.record.state)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a definite rejection leaves the action failed and unsent`() = runBlocking {
        val r = newEmail()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"insufficient scope"}}"""))
        val out = confirm(r) as ConfirmOutcome.Ran
        assertEquals(ActionState.FAILED, out.record.state)
        assertTrue(out.message.contains("Didn't go through"))
        assertEquals(CompletionEvidence.NONE, out.record.completionEvidence)
    }

    @Test
    fun `revoked consent fails before any request and a different signed-in account is refused`() = runBlocking {
        val r = newEmail()
        tokenResult = TokenResult.NeedsConsent(null)
        val revoked = confirm(r, "op-1") as ConfirmOutcome.Ran
        assertEquals(ActionState.FAILED, revoked.record.state)
        assertEquals("auth", revoked.record.error!!.code)
        assertEquals(0, server.requestCount)

        val r2 = ActionMachine.create("action-2", emailDraft, now, "conv-1").also { data.actions.put(it) }
        tokenResult = TokenResult.Token("tok", GoogleIdentity("someone-else", "other@example.com"))
        val mismatch = confirm(r2, "op-2") as ConfirmOutcome.Ran
        assertEquals("account_changed", mismatch.record.error!!.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unconnected account needs setup and changes nothing`() = runBlocking {
        data.settings.update { it.copy(gmailGranted = false) }
        val r = newEmail()
        val out = confirm(r)
        assertTrue(out is ConfirmOutcome.NeedsSetup)
        assertEquals(ActionState.AWAITING_REVIEW, data.actions.get(r.actionId)!!.state)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `development mode can never execute an external action`() = runBlocking {
        data.settings.update { it.copy(devMode = true) }
        val r = newEmail()
        val out = confirm(r) as ConfirmOutcome.Refused
        assertTrue(out.message.contains("Development mode"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expired confirmation is refused at execution time`() = runBlocking {
        val r = newEmail()
        // Simulate: confirm accepted, but the clock jumps beyond the TTL before execution begins.
        val confirmed = (ActionMachine.apply(r, ActionEvent.Confirm("op-9", r.revision, r.contentHash, "acct-1", ConfirmSource.WATCH), now) as com.peaceantz.stagescope.shared.actions.ActionOutcome.Ok).record
        data.actions.put(confirmed)
        now += ActionMachine.CONFIRMATION_TTL_MS + 1
        val begin = data.actions.apply(r.actionId, ActionEvent.BeginExecution("op-9", "acct-1"), now) as com.peaceantz.stagescope.shared.actions.ActionOutcome.Rejected
        assertEquals(RejectReason.CONFIRMATION_EXPIRED, begin.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a confirmed calendar event is created with its persisted id and the real receipt`() = runBlocking {
        val draft = CalendarDraft(
            title = "Sound check", eventId = "ss0123456789abcdef0123456789abcd", timezoneId = "America/New_York",
            startLocal = "2026-03-14T16:00", endLocal = "2026-03-14T17:00", startOffset = "-04:00", endOffset = "-04:00", accountEmail = "sound@example.com",
        )
        val r = ActionMachine.create("cal-1", draft, now, "conv-1").also { data.actions.put(it) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ss0123456789abcdef0123456789abcd","htmlLink":"https://calendar.example/e/9"}"""))
        val out = executor.confirm(r.actionId, "op-c", r.revision, r.contentHash, ConfirmSource.PHONE) as ConfirmOutcome.Ran
        assertEquals(ActionState.COMPLETED, out.record.state)
        assertEquals("https://calendar.example/e/9", out.record.receipt!!.link)
        assertNotNull(out.record.receipt!!.externalId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `keep handoffs are not confirmable and can only be marked done by the person`() = runBlocking {
        val keep = ActionMachine.create("keep-1", com.peaceantz.stagescope.shared.actions.KeepDraft("tape", "Theatre Supplies"), now, "conv-1").also { data.actions.put(it) }
        val refused = executor.confirm(keep.actionId, "op", keep.revision, keep.contentHash, ConfirmSource.WATCH) as ConfirmOutcome.Refused
        assertEquals(RejectReason.NOT_CONFIRMABLE, refused.reason)
        executor.handoffOpened(keep.actionId)
        val done = executor.markDone(keep.actionId) as ConfirmOutcome.Ran
        assertEquals(CompletionEvidence.USER_MARKED, done.record.completionEvidence)
        assertNull(done.record.receipt)
        assertTrue(done.message.contains("not verified"))
    }
}
