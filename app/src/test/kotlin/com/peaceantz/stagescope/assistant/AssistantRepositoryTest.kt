package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.assistant.TestRequests.encode
import com.peaceantz.stagescope.assistant.TestRequests.request
import com.peaceantz.stagescope.assistant.measure.MeasurementSnapshotBuilder
import com.peaceantz.stagescope.assistant.measure.TestMeasurements
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.assistant.TurnError
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.measurement.HistoryPoint
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.protocol.Ack
import com.peaceantz.stagescope.shared.protocol.AckStatus
import com.peaceantz.stagescope.shared.protocol.ActionCommand
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.ActionReply
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.CancelRequest
import com.peaceantz.stagescope.shared.protocol.ContinueOnPhone
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.ContinueReply
import com.peaceantz.stagescope.shared.protocol.DeviceRole
import com.peaceantz.stagescope.shared.protocol.Hello
import com.peaceantz.stagescope.shared.protocol.PlaybackNotice
import com.peaceantz.stagescope.shared.protocol.PlaybackState
import com.peaceantz.stagescope.shared.protocol.Progress
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.ProviderSelect
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ProviderStatus
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.ResultReady
import com.peaceantz.stagescope.shared.protocol.StatusQuery
import com.peaceantz.stagescope.shared.protocol.StatusReply
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.TranscriptResult
import com.peaceantz.stagescope.shared.protocol.VoiceOffer
import com.peaceantz.stagescope.shared.protocol.VoicePurpose
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.show.WatchProduction
import com.peaceantz.stagescope.shared.show.WatchShowView
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssistantRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val link = FakePhoneLink()
    private var now = 10_000_000L
    private var idCounter = 0
    private val playback = mutableListOf<PlaybackNotice>()
    private var answers = 0
    private lateinit var repo: AssistantRepository

    @Before
    fun setUp() {
        dir = tmp.newFolder()
        repo = newRepo()
    }

    private fun newRepo() = AssistantRepository(
        dir, link, appVersionName = "0.2.0", appVersionCode = 2, clock = { now }, newId = { "id-" + (++idCounter) }, onPlayback = { playback += it },
        onAnswerReady = { answers++ },
    )

    private fun entry(id: String = "req-1") = repo.outbox.value.entries.single { it.requestId == id }
    private fun state(id: String = "req-1") = entry(id).state

    // ------------------------------------------------------------------------------------ sending

    @Test
    fun `a question is saved first and sent once when the phone is reachable`() = runBlocking {
        repo.submit(request())
        assertEquals(OutboxState.SENDING, state())
        assertEquals(1, entry().attempts)
        assertEquals(1, link.sentOf<AssistantRequest>().size)
        assertEquals("a hello went first so the phone knows who is asking", 1, link.sentOf<Hello>().size)
        assertEquals(DeviceRole.WATCH, link.sentOf<Hello>().single().role)
    }

    @Test
    fun `it survives a restart exactly as it was`() = runBlocking {
        repo.submit(request(measurement = null))
        val reborn = newRepo()
        assertEquals(repo.outbox.value, reborn.outbox.value)
        assertEquals(OutboxState.SENDING, reborn.outbox.value.entries.single().state)
    }

    @Test
    fun `with no phone the question waits, and is sent on reconnect while it is still fresh`() = runBlocking {
        link.phones = emptyList()
        repo.submit(request())
        assertEquals(OutboxState.QUEUED_OFFLINE, state())
        assertTrue(link.sentOf<AssistantRequest>().isEmpty())

        now += 2 * 60_000
        link.phones = listOf(PhoneNode("phone-1", "Pixel 10", true))
        repo.flushOutbox()
        assertEquals(OutboxState.SENDING, state())
        assertEquals(1, link.sentOf<AssistantRequest>().size)
    }

    @Test
    fun `an old queued question is never auto-sent - it goes stale and the person decides`() = runBlocking {
        link.phones = emptyList()
        repo.submit(request())
        now += 10 * 60_000
        link.phones = listOf(PhoneNode("phone-1", "Pixel 10", true))
        repo.flushOutbox()
        assertEquals(OutboxState.STALE, state())
        assertTrue("nothing was sent behind the person's back", link.sentOf<AssistantRequest>().isEmpty())
        assertTrue(entry().message!!.contains("Send it anyway"))

        repo.sendStale("req-1")
        assertEquals(OutboxState.SENDING, state())
        assertEquals(1, link.sentOf<AssistantRequest>().size)
    }

    @Test
    fun `a stale question that is ignored eventually expires, still unsent`() = runBlocking {
        link.phones = emptyList()
        repo.submit(request())
        now += 10 * 60_000
        repo.flushOutbox()
        assertEquals(OutboxState.STALE, state())
        now += 25 * 60_000
        repo.flushOutbox()
        assertEquals(OutboxState.EXPIRED, state())
        assertTrue(link.sentOf<AssistantRequest>().isEmpty())
    }

    @Test
    fun `an unacknowledged question is re-sent with the same id and then reported as failed`() = runBlocking {
        repo.submit(request())
        now += 9_000
        repo.flushOutbox()
        assertEquals(2, link.sentOf<AssistantRequest>().size)
        assertEquals(setOf("req-1"), link.sentOf<AssistantRequest>().map { it.requestId }.toSet())
        assertEquals(2, entry().attempts)

        now += 3 * 60_000
        repo.flushOutbox()
        assertEquals(OutboxState.FAILED, state())
        assertTrue(entry().message!!.contains("didn't confirm"))
    }

    @Test
    fun `the phone's acknowledgement is what confirms it, and a duplicate ack changes nothing`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        assertEquals(OutboxState.ACKED, state())
        assertNotNull(entry().ackedAtEpochMs)
        val ackedAt = entry().ackedAtEpochMs
        now += 1_000
        repo.handleBytes(encode(Ack("req-1", AckStatus.DUPLICATE)))
        assertEquals(ackedAt, entry().ackedAtEpochMs)
        now += 60_000
        repo.flushOutbox()
        assertEquals("once acknowledged the watch does not resend", 1, link.sentOf<AssistantRequest>().size)
    }

    @Test
    fun `a rejected request fails with the phone's own reason`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.REJECTED, "Daily limit reached")))
        assertEquals(OutboxState.FAILED, state())
        assertEquals("Daily limit reached", entry().message)
    }

    // --------------------------------------------------------------------------- state only moves forward

    @Test
    fun `progress and results move forward and late messages cannot drag it back`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        repo.handleBytes(encode(Progress("req-1", ProgressStage.THINKING)))
        assertEquals(OutboxState.RUNNING, state())
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 4, RequestState.COMPLETED)))
        assertEquals(OutboxState.RESULT_READY, state())
        assertEquals(4L, entry().resultRevision)

        // A delayed ack and progress arrive after the answer.
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        repo.handleBytes(encode(Progress("req-1", ProgressStage.WRITING)))
        assertEquals(OutboxState.RESULT_READY, state())
    }

    @Test
    fun `an arriving answer signals once, and a repeated or late result does not`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        assertEquals(0, answers)
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 2, RequestState.COMPLETED)))
        assertEquals(1, answers)
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 2, RequestState.COMPLETED)))
        repo.handleBytes(encode(StatusReply("req-1", RequestState.COMPLETED, "conv-1", 2)))
        assertEquals("the same answer is never announced twice", 1, answers)
    }

    @Test
    fun `a failure or a cancellation is not announced as an answer`() = runBlocking {
        repo.submit(request(id = "a"))
        repo.handleBytes(encode(ResultReady("a", "conv-1", 1, RequestState.FAILED)))
        repo.submit(request(id = "b"))
        repo.cancel("b")
        assertEquals(0, answers)
    }

    @Test
    fun `a delivered answer sheds the stored measurement`() = runBlocking {
        val ctx = MeasurementSnapshotBuilder.build(TestMeasurements.inputs(analyzer = TestMeasurements.analyzerSample(TestMeasurements.reading())))
        repo.submit(request(measurement = ctx))
        assertNotNull(entry().request.measurement)
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 2, RequestState.COMPLETED)))
        assertNull(entry().request.measurement)
    }

    @Test
    fun `an answer for a question the watch had given up on is still delivered`() = runBlocking {
        repo.submit(request())
        now += 3 * 60_000
        repo.flushOutbox()
        assertEquals(OutboxState.FAILED, state())
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 3, RequestState.COMPLETED)))
        assertEquals("the ack was lost but the phone worked anyway", OutboxState.RESULT_READY, state())
    }

    @Test
    fun `cancelling is the person's decision and a late answer does not undo it`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        repo.cancel("req-1")
        assertEquals(OutboxState.CANCELLED, state())
        assertEquals(1, link.sentOf<CancelRequest>().size)
        assertTrue(entry().message!!.contains("not undone"))
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 3, RequestState.COMPLETED)))
        assertEquals(OutboxState.CANCELLED, state())
    }

    @Test
    fun `cancelling a question that was never sent tells the phone nothing`() = runBlocking {
        link.phones = emptyList()
        repo.submit(request())
        repo.cancel("req-1")
        assertEquals(OutboxState.CANCELLED, state())
        assertTrue(link.sentOf<CancelRequest>().isEmpty())
        assertEquals("Cancelled before it was sent.", entry().message)
    }

    @Test
    fun `a phone that has never heard of the request gets it again`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        link.sent.clear()
        repo.handleBytes(encode(StatusReply("req-1", RequestState.UNKNOWN)))
        assertEquals(1, link.sentOf<AssistantRequest>().size)
        assertEquals("req-1", link.sentOf<AssistantRequest>().single().requestId)
    }

    @Test
    fun `a long-silent acknowledged request prompts a status query`() = runBlocking {
        repo.submit(request())
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        now += 100_000
        repo.flushOutbox()
        assertEquals(listOf("req-1"), link.sentOf<StatusQuery>().map { it.requestId })
    }

    // ---------------------------------------------------------------------------------------- retry

    @Test
    fun `retrying a question the phone never acknowledged re-sends the same id so it can't run twice`() = runBlocking {
        repo.submit(request())
        now += 3 * 60_000
        repo.flushOutbox()
        assertEquals(OutboxState.FAILED, state())
        link.sent.clear()

        val id = repo.retry("req-1")
        assertEquals("req-1", id)
        assertEquals(listOf("req-1"), link.sentOf<AssistantRequest>().map { it.requestId })
        assertEquals(OutboxState.SENDING, state())
        assertEquals(1, repo.outbox.value.entries.size)
    }

    @Test
    fun `retrying after the phone itself failed is a new request carrying the original measurement and its original time`() = runBlocking {
        val ctx = MeasurementSnapshotBuilder.build(TestMeasurements.inputs(analyzer = TestMeasurements.analyzerSample(TestMeasurements.reading()), nowEpoch = 1_800_000_000_000L))
        repo.submit(request(measurement = ctx))
        repo.handleBytes(encode(Ack("req-1", AckStatus.RECEIVED)))
        repo.handleBytes(encode(ResultReady("req-1", "conv-1", 2, RequestState.FAILED)))
        assertEquals(OutboxState.FAILED, state())
        assertNotNull("a failed question keeps its evidence for the retry", entry().request.measurement)

        val newId = repo.retry("req-1")!!
        assertNotEquals("req-1", newId)
        val resent = link.sentOf<AssistantRequest>().last()
        assertEquals(newId, resent.requestId)
        assertEquals(1_800_000_000_000L, resent.measurement!!.capturedAtEpochMs)
        assertEquals("the same snapshot id - the evidence is not relabelled as new", ctx.snapshotId, resent.measurement!!.snapshotId)
    }

    // ------------------------------------------------------------------------------ message size

    @Test
    fun `an oversize snapshot is shrunk to fit, and a question that still can't fit is sent without it`() = runBlocking {
        val history = (0 until 40).map { HistoryPoint(-(it * 250L), -30.0, -10.0, 2100.0, -20.0, 1) }
        val ctx = MeasurementSnapshotBuilder.build(
            TestMeasurements.inputs(analyzer = TestMeasurements.analyzerSample(TestMeasurements.reading()), history = history),
        )
        fun bytes(r: AssistantRequest) = StageScopeJson.encodeToString(
            com.peaceantz.stagescope.shared.protocol.Envelope.serializer(),
            com.peaceantz.stagescope.shared.protocol.Envelope(sender = "watch:test", seq = 1, message = r),
        ).toByteArray().size
        val full = bytes(request(id = "a", measurement = ctx))
        val trimmed = bytes(request(id = "a", measurement = MeasurementSnapshotBuilder.fitToBudget(ctx, 9_000)))
        assertTrue("precondition: trimming must actually shrink the message ($trimmed vs $full)", trimmed < full)

        // First: too big as built, but fits once trimmed -> the question still carries (trimmed) evidence.
        link.maxBytes = (full + trimmed) / 2
        repo.submit(request(id = "a", measurement = ctx))
        val a = link.sentOf<AssistantRequest>().single { it.requestId == "a" }
        assertNotNull(a.measurement)
        assertTrue(a.measurement!!.history.isEmpty())
        assertTrue(a.measurement!!.notes.last().contains("left out"))

        // Second: nothing fits -> the question goes without the snapshot rather than not at all.
        link.sent.clear()
        link.maxBytes = 1_200
        repo.submit(request(id = "b", measurement = ctx, text = "short"))
        val b = link.sentOf<AssistantRequest>().single { it.requestId == "b" }
        assertNull(b.measurement)

        // Third: the words alone are too long -> a clear failure.
        link.maxBytes = 300
        repo.submit(request(id = "c", text = "x".repeat(2_000)))
        assertEquals(OutboxState.FAILED, state("c"))
        assertTrue(entry("c").message!!.contains("too long"))
    }

    // --------------------------------------------------------------------------- views / providers

    @Test
    fun `a thread view completes the question it answers and the cache keeps the newest revision per conversation`() = runBlocking {
        repo.submit(request(id = "req-9", conversation = "conv-9"))
        val view = ThreadView("conv-9", revision = 5, title = "Ring at 2 kHz", providerId = ProviderId.OPENAI, providerLabel = "ChatGPT / OpenAI", modelId = "gpt-6.1-sol", requestId = "req-9", summary = "Likely a narrow ring.", updatedAtPhoneEpochMs = 100)
        repo.handleDataItems(listOf(Wire.threadPath("conv-9") to StageScopeJson.encodeToString(ThreadView.serializer(), view).toByteArray()))
        assertEquals(OutboxState.RESULT_READY, state("req-9"))
        assertEquals("Likely a narrow ring.", repo.cache.value.threads.single().summary)

        val older = view.copy(revision = 3, summary = "OLD")
        repo.handleDataItems(listOf(Wire.threadPath("conv-9") to StageScopeJson.encodeToString(ThreadView.serializer(), older).toByteArray()))
        assertEquals("Likely a narrow ring.", repo.cache.value.threads.single().summary)
    }

    @Test
    fun `a failed thread shows the phone's reason`() = runBlocking {
        repo.submit(request(id = "req-9", conversation = "conv-9"))
        val view = ThreadView("conv-9", 2, "t", ProviderId.GEMINI, "Google Gemini", "gemini-3.8-flash", requestId = "req-9", requestState = RequestState.FAILED, error = TurnError(TurnErrorKind.INVALID_KEY, "Your Gemini key was rejected."))
        repo.handleDataItems(listOf(Wire.threadPath("conv-9") to StageScopeJson.encodeToString(ThreadView.serializer(), view).toByteArray()))
        assertEquals(OutboxState.FAILED, state("req-9"))
        assertEquals("Your Gemini key was rejected.", entry("req-9").message)
    }

    @Test
    fun `only the newest eight threads are cached`() = runBlocking {
        repeat(12) { i ->
            val v = ThreadView("c$i", 1, "t$i", ProviderId.OPENAI, "OpenAI", "m", updatedAtPhoneEpochMs = i.toLong())
            repo.handleDataItems(listOf(Wire.threadPath("c$i") to StageScopeJson.encodeToString(ThreadView.serializer(), v).toByteArray()))
        }
        assertEquals(8, repo.cache.value.threads.size)
        assertEquals("c11", repo.cache.value.threads.first().conversationId)
    }

    @Test
    fun `providers and shows are cached and malformed items are ignored`() = runBlocking {
        val providers = ProvidersView(7, ProviderId.OPENAI, listOf(ProviderStatus(ProviderId.OPENAI, "ChatGPT / OpenAI", true, true, "gpt-6.1-sol", true, false, false)), phoneAppVersion = "0.2.0")
        val shows = WatchShowView(3, listOf(WatchProduction("p1", "Our Town", "Playhouse")), selectedProductionId = "p1")
        repo.handleDataItems(
            listOf(
                Wire.DATA_PROVIDERS to StageScopeJson.encodeToString(ProvidersView.serializer(), providers).toByteArray(),
                Wire.DATA_SHOWS to StageScopeJson.encodeToString(WatchShowView.serializer(), shows).toByteArray(),
                Wire.threadPath("junk") to "{ not json".toByteArray(),
                Wire.DATA_PROVIDERS to null,
            ),
        )
        assertEquals(providers, repo.cache.value.providers)
        assertEquals("Our Town", repo.cache.value.shows!!.productions.single().name)
        assertTrue(repo.cache.value.threads.isEmpty())
    }

    // ---------------------------------------------------------------------- hello / sync / misc

    @Test
    fun `hello is sent when a phone first appears and again only after it reconnects`() = runBlocking {
        repo.refreshReachability()
        repo.refreshReachability()
        assertEquals(1, link.sentOf<Hello>().size)
        link.phones = emptyList()
        repo.refreshReachability()
        assertFalse(repo.phoneReachable.value)
        link.phones = listOf(PhoneNode("phone-1", "Pixel 10", true))
        repo.refreshReachability()
        assertEquals(2, link.sentOf<Hello>().size)
    }

    @Test
    fun `the phone's hello records its version and flushes anything waiting`() = runBlocking {
        link.phones = emptyList()
        repo.submit(request())
        link.phones = listOf(PhoneNode("phone-1", "Pixel 10", true))
        repo.handleBytes(encode(Hello("phone-install", DeviceRole.PHONE, "0.2.0", 2, selectedVersion = 1)))
        assertEquals("0.2.0", repo.cache.value.phoneAppVersion)
        assertEquals(1, repo.cache.value.negotiatedProtocolVersion)
        assertEquals(OutboxState.SENDING, state())
    }

    @Test
    fun `garbage and unsupported-version bytes are ignored`() = runBlocking {
        repo.handleBytes("not json".toByteArray())
        repo.handleBytes("""{"v":99,"sender":"phone:x","seq":1,"message":{"type":"hello"}}""".toByteArray())
        assertTrue(repo.outbox.value.entries.isEmpty())
        assertNull(repo.cache.value.lastHeardFromPhoneEpochMs)
    }

    @Test
    fun `playback notices are passed to the audio coordinator`() = runBlocking {
        repo.handleBytes(encode(PlaybackNotice("u1", PlaybackState.STARTED)))
        assertEquals(listOf(PlaybackNotice("u1", PlaybackState.STARTED)), playback)
    }

    @Test
    fun `the outbox is bounded and never drops an unfinished question`() = runBlocking {
        link.phones = emptyList()
        repeat(25) { i ->
            now += 1
            repo.submit(request(id = "r$i"))
            if (i < 15) repo.cancel("r$i")
        }
        val entries = repo.outbox.value.entries
        assertTrue(entries.size <= AssistantRepository.MAX_OUTBOX)
        assertEquals("all ten waiting questions survive", 10, entries.count { it.state == OutboxState.QUEUED_OFFLINE })
    }

    @Test
    fun `preferences default to a silent theatre-safe assistant`() {
        val p = repo.prefs.value
        assertTrue(p.theatreMode)
        assertFalse(p.hapticsEnabled)
        assertEquals(ReplyMode.TEXT, p.outputMode)
        assertTrue(p.installId.isNotBlank())
    }

    @Test
    fun `preference changes persist`() = runBlocking {
        repo.updatePrefs { it.copy(outputMode = ReplyMode.BOTH, theatreMode = false) }
        val reborn = newRepo()
        assertEquals(ReplyMode.BOTH, reborn.prefs.value.outputMode)
        assertFalse(reborn.prefs.value.theatreMode)
        assertEquals(repo.installId, reborn.installId)
    }

    // ------------------------------------------------------------------------------------ actions

    @Test
    fun `an action command names the reviewed revision and hash, and needs a reachable phone`() = runBlocking {
        assertTrue(repo.actionCommand("a-1", ActionCommandKind.CONFIRM, 3, "hash-abc"))
        val cmd = link.sentOf<ActionCommand>().single()
        assertEquals(ActionCommandKind.CONFIRM, cmd.command)
        assertEquals(3, cmd.revision)
        assertEquals("hash-abc", cmd.contentHash)

        link.phones = emptyList()
        assertFalse(repo.actionCommand("a-1", ActionCommandKind.CONFIRM, 3, "hash-abc"))
        assertEquals(1, link.sentOf<ActionCommand>().size)
    }

    @Test
    fun `each command gets its own operation id`() = runBlocking {
        repo.actionCommand("a-1", ActionCommandKind.CANCEL)
        repo.actionCommand("a-1", ActionCommandKind.CANCEL)
        val ids = link.sentOf<ActionCommand>().map { it.requestId }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `the phone's reply to a command is surfaced and can be consumed`() = runBlocking {
        repo.handleBytes(encode(ActionReply("op-1", "a-1", true, ActionState.EXECUTING, "Received. Working on it.")))
        assertEquals("Received. Working on it.", repo.lastActionReply.value!!.message)
        repo.consumeActionReply()
        assertNull(repo.lastActionReply.value)
    }

    @Test
    fun `provider selection is sent only when a phone is reachable`() = runBlocking {
        assertTrue(repo.selectProvider(ProviderId.ANTHROPIC, modelId = "claude-sonnet-5-5", thorough = true))
        val sel = link.sentOf<ProviderSelect>().single()
        assertEquals(ProviderId.ANTHROPIC, sel.providerId)
        assertEquals("claude-sonnet-5-5", sel.modelId)
        link.phones = emptyList()
        assertFalse(repo.selectProvider(ProviderId.GEMINI))
    }

    // ----------------------------------------------------------------------------- continue on phone

    @Test
    fun `continue on phone says asking, then exactly what the phone answered`() = runBlocking {
        val id = repo.continueOnPhone("conv-1", null)
        assertEquals(ContinueUi.Asking, repo.continueStates.value[id])
        assertEquals("conv-1", link.sentOf<ContinueOnPhone>().single().conversationId)
        repo.handleBytes(encode(ContinueReply(id, ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED)))
        assertEquals(ContinueUi.Done(ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED), repo.continueStates.value[id])
    }

    @Test
    fun `continue on phone with no phone says there was no answer, not opened`() = runBlocking {
        link.phones = emptyList()
        val id = repo.continueOnPhone("conv-1", null)
        assertEquals(ContinueUi.NoAnswer, repo.continueStates.value[id])
    }

    @Test
    fun `an unanswered continue request can be marked as no answer, but an answered one is left alone`() = runBlocking {
        val id = repo.continueOnPhone("conv-1", null)
        repo.markContinueNoAnswer(id)
        assertEquals(ContinueUi.NoAnswer, repo.continueStates.value[id])
        val id2 = repo.continueOnPhone("conv-2", null)
        repo.handleBytes(encode(ContinueReply(id2, ContinueOutcome.OPENED)))
        repo.markContinueNoAnswer(id2)
        assertEquals(ContinueUi.Done(ContinueOutcome.OPENED), repo.continueStates.value[id2])
    }

    // -------------------------------------------------------------------------------------- memos

    private fun memo(id: String = "memo-1", state: MemoState = MemoState.PENDING_PHONE) = VoiceMemo(
        memoId = id, requestId = "req-$id", createdAtEpochMs = now, durationMs = 4_000, sampleRateHz = 16_000, byteCount = 128_000,
        purpose = VoicePurpose.DICTATION, taskKind = TaskKind.FREE_CHAT, state = state,
    )

    @Test
    fun `a recording made with no phone waits, then is offered and streamed when the phone appears`() = runBlocking {
        link.phones = emptyList()
        repo.saveMemo(memo(), ByteArray(128_000))
        repo.uploadPendingMemos()
        assertEquals(MemoState.PENDING_PHONE, repo.memos.value.memos.single().state)
        assertTrue(link.voice.isEmpty())

        link.phones = listOf(PhoneNode("phone-1", "Pixel 10", true))
        repo.uploadPendingMemos()
        assertEquals(MemoState.TRANSCRIBING, repo.memos.value.memos.single().state)
        assertEquals(1, link.sentOf<VoiceOffer>().size)
        assertEquals("memo-1", link.voice.single().first)
        assertEquals("phone-1", link.voice.single().third)
    }

    @Test
    fun `a transcript replaces the audio, which is then deleted`() = runBlocking {
        repo.saveMemo(memo(), ByteArray(1_000))
        repo.uploadPendingMemos()
        assertTrue(File(repo.memoDir, "memo-1.pcm").exists())
        repo.handleBytes(encode(TranscriptResult("req-memo-1", "memo-1", text = "Is the lav on mic three ringing?", engine = "On-device")))
        val m = repo.memos.value.memos.single()
        assertEquals(MemoState.TRANSCRIPT_READY, m.state)
        assertEquals("Is the lav on mic three ringing?", m.transcript)
        assertFalse("no audio is kept once it has been transcribed", File(repo.memoDir, "memo-1.pcm").exists())
    }

    @Test
    fun `a failed transcription is shown, not hidden`() = runBlocking {
        repo.saveMemo(memo(), ByteArray(1_000))
        repo.handleBytes(encode(TranscriptResult("req-memo-1", "memo-1", error = "No on-device speech recognition is installed on this phone.")))
        val m = repo.memos.value.memos.single()
        assertEquals(MemoState.FAILED, m.state)
        assertTrue(m.error!!.contains("No on-device speech recognition"))
    }

    @Test
    fun `uploads that keep failing end as failed after a bounded number of tries`() = runBlocking {
        link.voiceOk = false
        repo.saveMemo(memo(), ByteArray(1_000))
        repeat(AssistantRepository.MAX_MEMO_ATTEMPTS) { repo.uploadPendingMemos() }
        assertEquals(MemoState.FAILED, repo.memos.value.memos.single().state)
        assertEquals(AssistantRepository.MAX_MEMO_ATTEMPTS, repo.memos.value.memos.single().attempts)
    }

    @Test
    fun `a waiting recording or an unchecked transcript is never silently dropped to make room`() = runBlocking {
        val states = listOf(MemoState.PENDING_PHONE, MemoState.UPLOADING, MemoState.TRANSCRIBING, MemoState.TRANSCRIPT_READY, MemoState.PENDING_PHONE)
        assertEquals(AssistantRepository.MAX_MEMOS, states.size)
        states.forEachIndexed { i, s -> repo.saveMemo(memo("m$i", s), ByteArray(10)) }
        assertFalse("every slot holds something the person still needs", repo.canKeepAnotherMemo())

        // The watch refuses to start another recording, but even if one is saved anyway nothing the person needs is lost.
        repo.saveMemo(memo("extra"), ByteArray(10))
        assertEquals((states.indices.map { "m$it" } + "extra").toSet(), repo.memos.value.memos.map { it.memoId }.toSet())
    }

    @Test
    fun `only finished memos are dropped to stay within the limit, oldest first, and their audio goes too`() = runBlocking {
        repeat(AssistantRepository.MAX_MEMOS) { i ->
            repo.saveMemo(memo("m$i", if (i == 1 || i == 3) MemoState.FAILED else MemoState.PENDING_PHONE), ByteArray(10))
        }
        assertTrue("a failed memo can make room", repo.canKeepAnotherMemo())

        repo.saveMemo(memo("new"), ByteArray(10))
        val ids = repo.memos.value.memos.map { it.memoId }
        assertEquals(AssistantRepository.MAX_MEMOS, ids.size)
        assertFalse("the oldest finished memo was the one dropped", "m1" in ids)
        assertTrue("m3" in ids && "new" in ids && "m0" in ids)
        assertEquals("audio files of dropped memos are removed too", AssistantRepository.MAX_MEMOS, repo.memoDir.listFiles()!!.size)
    }

    @Test
    fun `deleting a memo deletes its audio`() = runBlocking {
        repo.saveMemo(memo("keep"), ByteArray(10))
        repo.saveMemo(memo("victim"), ByteArray(10))
        repo.deleteMemo("victim")
        assertFalse(File(repo.memoDir, "victim.pcm").exists())
        assertTrue(File(repo.memoDir, "keep.pcm").exists())
        assertEquals(listOf("keep"), repo.memos.value.memos.map { it.memoId })
    }

    @Test
    fun `consuming an action reply forgets only the reply it was given`() = runBlocking {
        repo.handleBytes(encode(ActionReply("c1", "action-1", true, ActionState.EXECUTING, "Received. Working on it.")))
        val first = repo.lastActionReply.value!!
        repo.handleBytes(encode(ActionReply("c2", "action-1", true, ActionState.COMPLETED, "Sent.")))
        repo.consumeActionReply(first)
        assertEquals("a newer reply is kept", "Sent.", repo.lastActionReply.value!!.message)
        repo.consumeActionReply(repo.lastActionReply.value)
        assertNull(repo.lastActionReply.value)
    }

    @Test
    fun `a memo whose audio went missing fails clearly`() = runBlocking {
        repo.saveMemo(memo(), ByteArray(10))
        File(repo.memoDir, "memo-1.pcm").delete()
        repo.uploadPendingMemos()
        assertEquals(MemoState.FAILED, repo.memos.value.memos.single().state)
    }

    @Test
    fun `a memo keeps the snapshot that was taken before it was recorded, with its original time`() = runBlocking {
        val ctx = MeasurementSnapshotBuilder.build(
            TestMeasurements.inputs(analyzer = TestMeasurements.analyzerSample(TestMeasurements.reading()), nowEpoch = 1_800_000_000_000L, runState = RunState.RUNNING),
        )
        repo.saveMemo(memo().copy(snapshot = ctx), ByteArray(10))
        val reborn = newRepo()
        assertEquals(1_800_000_000_000L, reborn.memos.value.memos.single().snapshot!!.capturedAtEpochMs)
        assertEquals(ctx, reborn.memos.value.memos.single().snapshot)
    }
}
