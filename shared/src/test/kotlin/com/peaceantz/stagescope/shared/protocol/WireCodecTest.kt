package com.peaceantz.stagescope.shared.protocol

import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.EmailPurpose
import com.peaceantz.stagescope.shared.show.EmailAddress
import com.peaceantz.stagescope.shared.util.StageScopeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireCodecTest {

    private fun request() = AssistantRequest(
        requestId = "req-1", conversationId = "conv-1", taskKind = TaskKind.ANALYZE_SOUND,
        userText = "Analyze these rings", inputOrigin = InputOrigin.SPEECH_WATCH, transcriptReviewed = true,
        replyMode = ReplyMode.TEXT, createdAtWatchEpochMs = 123L,
    )

    @Test
    fun `every message type round-trips with its envelope`() {
        val messages: List<WireMessage> = listOf(
            Hello("install", DeviceRole.WATCH, "0.2.0", 2),
            request(),
            Ack("req-1", AckStatus.RECEIVED),
            CancelRequest("req-1"),
            Progress("req-1", ProgressStage.THINKING, "Working"),
            ResultReady("req-1", "conv-1", 4, RequestState.COMPLETED),
            StatusQuery("req-1"),
            StatusReply("req-1", RequestState.RUNNING),
            ActionCommand("op-1", "act-1", ActionCommandKind.CONFIRM, 2, "hash"),
            ActionReply("op-1", "act-1", true, ActionState.EXECUTING, "ok"),
            ContinueOnPhone("op-2", "conv-1"),
            ContinueReply("op-2", ContinueOutcome.NOTIFICATION_POSTED),
            ProviderSelect("op-3", ProviderId.ANTHROPIC, "claude-opus-5-5", thorough = true),
            PlaybackNotice("utt-1", PlaybackState.STARTED),
            SyncNudge("issues"),
            VoiceOffer("req-2", "memo-1", VoicePurpose.MEMO, 16_000, 8_000, 256_000),
            TranscriptResult("req-2", "memo-1", text = "hello", engine = "on-device"),
        )
        messages.forEachIndexed { i, m ->
            val bytes = WireCodec.encode(Envelope(sender = "watch:abc", seq = i.toLong(), message = m))
            val decoded = WireCodec.decode(bytes)
            assertTrue("$m should decode", decoded is Decoded.Ok)
            assertEquals(m, (decoded as Decoded.Ok).envelope.message)
            assertEquals(i.toLong(), decoded.envelope.seq)
        }
    }

    @Test
    fun `a newer peer's protocol version is reported as unsupported, not as garbage`() {
        val future = """{"v":${Wire.PROTOCOL_VERSION + 1},"sender":"phone:x","seq":1,"message":{"type":"unknown_future","foo":1}}"""
        val d = WireCodec.decode(future.toByteArray())
        assertTrue(d is Decoded.UnsupportedVersion)
        assertEquals(Wire.PROTOCOL_VERSION + 1, (d as Decoded.UnsupportedVersion).version)
    }

    @Test
    fun `junk and unknown message types are malformed rather than crashing`() {
        assertTrue(WireCodec.decode("not json".toByteArray()) is Decoded.Malformed)
        val unknownType = """{"v":1,"sender":"p","seq":1,"message":{"type":"does_not_exist"}}"""
        assertTrue(WireCodec.decode(unknownType.toByteArray()) is Decoded.Malformed)
        assertTrue(WireCodec.decode(ByteArray(10 * Wire.MAX_PAYLOAD_BYTES)) is Decoded.Malformed)
    }

    @Test
    fun `unknown extra fields from a newer peer are ignored`() {
        val withExtra = """{"v":1,"sender":"p","seq":2,"message":{"type":"ack","requestId":"r","status":"RECEIVED","addedLater":"x"},"alsoNew":true}"""
        val d = WireCodec.decode(withExtra.toByteArray())
        assertEquals(Ack("r", AckStatus.RECEIVED), (d as Decoded.Ok).envelope.message)
    }

    @Test
    fun `an oversized payload is refused before it reaches the transport`() {
        val huge = request().copy(userText = "x".repeat(Wire.MAX_PAYLOAD_BYTES))
        try {
            WireCodec.encode(Envelope(sender = "w", seq = 1, message = huge))
            fail("expected PayloadTooLarge")
        } catch (e: WireCodec.PayloadTooLarge) {
            assertTrue(e.bytes > Wire.MAX_PAYLOAD_BYTES)
        }
    }

    @Test
    fun `version negotiation picks the highest common version or none`() {
        assertEquals(2, WireCodec.negotiate(1, 3, 2, 2))
        assertEquals(1, WireCodec.negotiate(1, 1, 1, 4))
        assertNull(WireCodec.negotiate(1, 1, 2, 3))
    }

    @Test
    fun `thread views carry the review payload an action needs and round-trip`() {
        val draft = EmailDraft(EmailPurpose.ISSUE_HELP, to = listOf(EmailAddress("a@example.com", "A")), subject = "S", body = "B", senderAccount = "me@example.com")
        val card = ActionCard(
            actionId = "act", kind = ActionKind.EMAIL, state = ActionState.AWAITING_REVIEW, revision = 3, contentHash = "h",
            title = "Email", summary = "To A", draft = draft, statusLine = "Review before sending", canConfirmOnWatch = true,
        )
        val view = ThreadView("conv", 9, "Title", ProviderId.OPENAI, ProviderId.OPENAI.label, "gpt-6.1-sol", actions = listOf(card))
        val text = StageScopeJson.encodeToString(ThreadView.serializer(), view)
        val back = StageScopeJson.decodeFromString(ThreadView.serializer(), text)
        assertEquals(view, back)
        assertEquals("gpt-6.1-sol", back.modelId)
        assertTrue((back.actions.first().draft as EmailDraft).to.first().address == "a@example.com")
    }

    @Test
    fun `provider labels match the spec`() {
        assertEquals("ChatGPT / OpenAI", ProviderId.OPENAI.label)
        assertEquals("Google Gemini", ProviderId.GEMINI.label)
        assertEquals("xAI Grok", ProviderId.XAI.label)
        assertEquals("Anthropic Claude", ProviderId.ANTHROPIC.label)
    }
}
