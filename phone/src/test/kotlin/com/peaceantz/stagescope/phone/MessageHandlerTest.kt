package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.assistant.ActionExecutor
import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.google.CalendarClient
import com.peaceantz.stagescope.phone.google.GmailClient
import com.peaceantz.stagescope.phone.google.GoogleAuthorizer
import com.peaceantz.stagescope.phone.google.TokenResult
import com.peaceantz.stagescope.phone.link.PhoneMessageHandler
import com.peaceantz.stagescope.phone.link.ThreadPublisher
import com.peaceantz.stagescope.shared.actions.ActionEvent
import com.peaceantz.stagescope.shared.actions.ActionMachine
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.EmailPurpose
import com.peaceantz.stagescope.shared.actions.IssueLogDraft
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.protocol.Ack
import com.peaceantz.stagescope.shared.protocol.AckStatus
import com.peaceantz.stagescope.shared.protocol.ActionCommand
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.ActionReply
import com.peaceantz.stagescope.shared.protocol.CancelRequest
import com.peaceantz.stagescope.shared.protocol.ContinueOnPhone
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.ContinueReply
import com.peaceantz.stagescope.shared.protocol.DeviceRole
import com.peaceantz.stagescope.shared.protocol.Envelope
import com.peaceantz.stagescope.shared.protocol.Hello
import com.peaceantz.stagescope.shared.protocol.ProviderSelect
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.StatusQuery
import com.peaceantz.stagescope.shared.protocol.StatusReply
import com.peaceantz.stagescope.shared.protocol.SyncNudge
import com.peaceantz.stagescope.shared.protocol.VoiceOffer
import com.peaceantz.stagescope.shared.protocol.VoicePurpose
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.protocol.WireCodec
import com.peaceantz.stagescope.shared.show.EmailAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageHandlerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var data: PhoneData
    private val link = FakeWatchLink()
    private val scheduler = FakeScheduler()
    private val continuations = FakeContinuations()
    private val voiceOffers = mutableListOf<VoiceOffer>()
    private lateinit var handler: PhoneMessageHandler
    private var now = 1_800_000_000_000L
    private var seq = 0L

    private val auth = object : GoogleAuthorizer {
        override suspend fun accessToken(scopes: List<String>): TokenResult = TokenResult.Unavailable("not used in these tests")
        override suspend fun revoke(scopes: List<String>) = Unit
    }

    @Before
    fun setUp() {
        data = PhoneData(tmp.newFolder())
        val publisher = ThreadPublisher(data, link, clock = { now }, notificationsEnabled = { true }, appVersion = "0.2.0")
        val http = ProviderHttp()
        val executor = ActionExecutor(data, GmailClient(http), CalendarClient(http), auth, clock = { now }, onChanged = { publisher.actionChanged(it) })
        handler = PhoneMessageHandler(
            data = data, link = link, publisher = publisher, scheduler = scheduler, executor = executor, continuations = continuations,
            phoneInstallId = { "phone-install" }, appVersionName = "0.2.0", appVersionCode = 2, hasKey = { it == ProviderId.OPENAI },
            onVoiceOffer = { voiceOffers += it }, clock = { now },
        )
    }

    private suspend fun deliver(message: com.peaceantz.stagescope.shared.protocol.WireMessage) =
        handler.handle(Envelope(sender = "watch:w-1", seq = ++seq, message = message))

    private fun hello(min: Int = 1, max: Int = 1) = Hello("w-1", DeviceRole.WATCH, "0.2.0", 2, minVersion = min, maxVersion = max)

    // ------------------------------------------------------------------------------------- hello

    @Test
    fun `hello negotiates a version, remembers the watch, answers, and publishes the views`() = runBlocking {
        deliver(hello())
        val s = data.settings.value
        assertEquals("w-1", s.watchInstallId)
        assertEquals("0.2.0", s.watchAppVersion)
        assertEquals(Wire.PROTOCOL_VERSION, s.negotiatedProtocolVersion)
        assertNotNull(s.lastWatchContactEpochMs)
        val reply = link.sentOf<Hello>().single()
        assertEquals(DeviceRole.PHONE, reply.role)
        assertEquals(Wire.PROTOCOL_VERSION, reply.selectedVersion)
        assertTrue(link.providers.isNotEmpty() && link.shows.isNotEmpty())
        // Keys never travel: the view only says whether one exists.
        assertTrue(link.providers.last().providers.single { it.providerId == ProviderId.OPENAI }.hasKey)
        assertFalse(link.providers.last().providers.single { it.providerId == ProviderId.GEMINI }.hasKey)
    }

    @Test
    fun `a watch whose protocol range doesn't overlap gets a reply with no version and no views`() = runBlocking {
        deliver(hello(min = 7, max = 9))
        assertNull(data.settings.value.negotiatedProtocolVersion)
        assertNull(link.sentOf<Hello>().single().selectedVersion)
        assertTrue(link.providers.isEmpty())
    }

    @Test
    fun `garbage and newer-version bytes are ignored without throwing`() = runBlocking {
        handler.handleBytes("not json at all".toByteArray())
        handler.handleBytes("""{"v":99,"sender":"watch:w","seq":1,"message":{"type":"hello"}}""".toByteArray())
        assertTrue(link.sent.isEmpty())
        assertEquals(0, scheduler.assistant.size)
    }

    @Test
    fun `bytes produced by the real codec are decoded and handled`() = runBlocking {
        val bytes = WireCodec.encode(Envelope(sender = "watch:w-1", seq = 1, message = Samples.request(id = "via-bytes")))
        handler.handleBytes(bytes)
        assertEquals(listOf("via-bytes"), scheduler.assistant)
    }

    // ----------------------------------------------------------------------------------- requests

    @Test
    fun `a new request is persisted before it is acknowledged and is scheduled exactly once`() = runBlocking {
        deliver(Samples.request(id = "r-1"))
        val entry = data.inbox.get("r-1")
        assertNotNull("durable before anything else", entry)
        assertEquals(InboxState.RECEIVED, entry!!.state)
        assertEquals("watch", entry.source)
        assertEquals(AckStatus.RECEIVED, link.sentOf<Ack>().single().status)
        assertEquals(listOf("r-1"), scheduler.assistant)
    }

    @Test
    fun `a retransmitted request is answered as a duplicate and never scheduled twice`() = runBlocking {
        deliver(Samples.request(id = "r-1"))
        deliver(Samples.request(id = "r-1"))
        deliver(Samples.request(id = "r-1"))
        assertEquals(listOf("r-1"), scheduler.assistant)
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.DUPLICATE, AckStatus.DUPLICATE), link.sentOf<Ack>().map { it.status })
    }

    @Test
    fun `cancel marks the request, unschedules its work, and says honestly that done work is not undone`() = runBlocking {
        deliver(Samples.request(id = "r-1"))
        deliver(CancelRequest("r-1"))
        assertEquals(InboxState.CANCELLED, data.inbox.get("r-1")!!.state)
        assertEquals(listOf("r-1"), scheduler.cancelled)
        assertTrue(link.sentOf<Ack>().last().detail.orEmpty().contains("not undone"))
    }

    @Test
    fun `a status query reports the durable state of a request`() = runBlocking {
        deliver(StatusQuery("never-seen"))
        assertEquals(RequestState.UNKNOWN, link.sentOf<StatusReply>().last().state)
        deliver(Samples.request(id = "r-1", conversation = "conv-9"))
        deliver(StatusQuery("r-1"))
        val reply = link.sentOf<StatusReply>().last()
        assertEquals(RequestState.QUEUED, reply.state)
        assertEquals("conv-9", reply.conversationId)
    }

    // ------------------------------------------------------------------------------------ actions

    private suspend fun emailAction(id: String = "a-1") =
        ActionMachine.create(
            id, EmailDraft(EmailPurpose.PERFORMANCE_REPORT, to = listOf(EmailAddress("dana@theatre.example")), subject = "Report", body = "Hello"),
            now, "conv-1",
        ).also { data.actions.put(it) }

    @Test
    fun `a confirm must name the exact version that was reviewed`() = runBlocking {
        val r = emailAction()
        deliver(ActionCommand("op-1", r.actionId, ActionCommandKind.CONFIRM, revision = null, contentHash = null))
        assertTrue(scheduler.confirms.isEmpty())
        val reply = link.sentOf<ActionReply>().single()
        assertFalse(reply.accepted)
        assertTrue(reply.message.contains("exact version"))
    }

    @Test
    fun `a confirm is handed to durable work as a watch confirmation of that revision and hash`() = runBlocking {
        val r = emailAction()
        deliver(ActionCommand("op-1", r.actionId, ActionCommandKind.CONFIRM, r.revision, r.contentHash))
        val cmd = scheduler.confirms.single()
        assertEquals(r.actionId, cmd.actionId)
        assertEquals("op-1", cmd.opId)
        assertEquals(r.revision, cmd.revision)
        assertEquals(r.contentHash, cmd.contentHash)
        assertEquals(ConfirmSource.WATCH, cmd.by)
        assertTrue("the reply only says it was received; the send itself is not claimed", link.sentOf<ActionReply>().single().message.startsWith("Received"))
        assertEquals("nothing executed in the message callback", ActionState.AWAITING_REVIEW, data.actions.get(r.actionId)!!.state)
    }

    @Test
    fun `an action that no longer exists is reported, not silently ignored`() = runBlocking {
        deliver(ActionCommand("op-1", "ghost", ActionCommandKind.CANCEL))
        val reply = link.sentOf<ActionReply>().single()
        assertFalse(reply.accepted)
        assertTrue(reply.message.contains("no longer exists"))
    }

    @Test
    fun `cancel and mark-done go through the state machine`() = runBlocking {
        val r = emailAction()
        deliver(ActionCommand("op-1", r.actionId, ActionCommandKind.CANCEL))
        assertEquals(ActionState.CANCELLED, data.actions.get(r.actionId)!!.state)
        assertTrue(link.sentOf<ActionReply>().last().accepted)

        // A cancelled email can't be "marked done".
        deliver(ActionCommand("op-2", r.actionId, ActionCommandKind.MARK_DONE))
        assertFalse(link.sentOf<ActionReply>().last().accepted)
        assertEquals(ActionState.CANCELLED, data.actions.get(r.actionId)!!.state)
    }

    @Test
    fun `undoing a logged issue leaves a tombstone so the delete syncs to the watch`() = runBlocking {
        val replica = data.issues.replicaId
        val issue = IssueOps.create("issue-1", replica, now, "Mic 12 crackled")
        data.issues.update { IssueLedger.create(it, "create-op", issue) }
        val logged = ActionMachine.create("a-issue", IssueLogDraft("issue-1", "Mic 12 crackled"), now, "conv-1")
            .let { (ActionMachine.apply(it, ActionEvent.LocalWriteCompleted("Logged"), now) as com.peaceantz.stagescope.shared.actions.ActionOutcome.Ok).record }
        data.actions.put(logged)

        deliver(ActionCommand("undo-1", "a-issue", ActionCommandKind.UNDO))

        assertEquals(ActionState.CANCELLED, data.actions.get("a-issue")!!.state)
        val state = data.issues.state.value.issues["issue-1"]!!
        assertTrue("a tombstone, not a removal", state.view().deleted)
        assertTrue("the delete was published so the watch's copy can't bring it back", link.issues.any { it.second.id == "issue-1" && it.second.view().deleted })
        assertTrue(link.sentOf<ActionReply>().last().accepted)
    }

    @Test
    fun `only a logged issue can be undone`() = runBlocking {
        val r = emailAction()
        deliver(ActionCommand("undo-1", r.actionId, ActionCommandKind.UNDO))
        val reply = link.sentOf<ActionReply>().last()
        assertFalse(reply.accepted)
        assertEquals(ActionState.AWAITING_REVIEW, data.actions.get(r.actionId)!!.state)
    }

    // -------------------------------------------------------------------- providers/continue/voice

    @Test
    fun `selecting a provider applies only known models and republishes the providers view`() = runBlocking {
        deliver(ProviderSelect("p-1", ProviderId.ANTHROPIC, modelId = "claude-sonnet-5-5", thorough = true, webSearch = true))
        var s = data.settings.value
        assertEquals(ProviderId.ANTHROPIC, s.selectedProvider)
        assertEquals("claude-sonnet-5-5", s.modelFor(ProviderId.ANTHROPIC))
        assertTrue(s.thorough[ProviderId.ANTHROPIC] == true && s.webSearchFor(ProviderId.ANTHROPIC))
        assertEquals(ProviderId.ANTHROPIC, link.providers.last().selected)

        // An unknown model id is ignored (never invented); the provider choice itself still applies.
        deliver(ProviderSelect("p-2", ProviderId.GEMINI, modelId = "gemini-9-ultra"))
        s = data.settings.value
        assertEquals(ProviderId.GEMINI, s.selectedProvider)
        assertEquals("gemini-3.8-flash", s.modelFor(ProviderId.GEMINI))
    }

    @Test
    fun `continue on phone relays the real outcome`() = runBlocking {
        continuations.outcome = ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED
        deliver(ContinueOnPhone("c-1", conversationId = "conv-1"))
        assertEquals("conv-1", continuations.calls.single().second.conversationId)
        val reply = link.sentOf<ContinueReply>().single()
        assertEquals("c-1", reply.requestId)
        assertEquals(ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED, reply.outcome)
    }

    @Test
    fun `a voice offer is handed to the receiver and acknowledged as received, not transcribed`() = runBlocking {
        val offer = VoiceOffer("r-v", "memo-1", VoicePurpose.DICTATION, 16_000, 4_000, 128_000)
        deliver(offer)
        assertEquals(listOf(offer), voiceOffers)
        assertEquals(AckStatus.RECEIVED, link.sentOf<Ack>().single().status)
    }

    @Test
    fun `a sync nudge republishes everything`() = runBlocking {
        deliver(SyncNudge("watch reconnected"))
        assertTrue(link.providers.isNotEmpty() && link.shows.isNotEmpty())
    }
}
