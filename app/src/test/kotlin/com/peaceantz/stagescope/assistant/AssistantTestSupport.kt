package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.Envelope
import com.peaceantz.stagescope.shared.protocol.WireCodec
import com.peaceantz.stagescope.shared.protocol.WireMessage
import com.peaceantz.stagescope.shared.util.StageScopeJson

/** Records everything the watch would hand to the Data Layer; can simulate "nobody there" and "too big". */
class FakePhoneLink : PhoneLink {
    var phones: List<PhoneNode> = listOf(PhoneNode("phone-1", "Pixel 10", nearby = true))
    val sent = mutableListOf<WireMessage>()

    /** Messages over this many bytes are refused like the real codec would (the Data Layer's own cap is 60 KB). */
    var maxBytes: Int = Int.MAX_VALUE
    var dataItems: List<Pair<String, ByteArray>> = emptyList()

    override suspend fun reachablePhones(): List<PhoneNode> = phones

    override suspend fun send(message: WireMessage): Int {
        val size = StageScopeJson.encodeToString(Envelope.serializer(), Envelope(sender = "watch:test", seq = 1, message = message)).toByteArray().size
        if (size > maxBytes) throw WireCodec.PayloadTooLarge(size)
        if (phones.isEmpty()) return 0
        sent += message
        return phones.size
    }

    override suspend fun currentDataItems() = dataItems

    inline fun <reified T : WireMessage> sentOf(): List<T> = sent.filterIsInstance<T>()
}

object TestRequests {
    fun request(
        id: String = "req-1", conversation: String = "conv-1", text: String = "Is that a ring at two kilohertz?",
        kind: TaskKind = TaskKind.ANALYZE_SOUND, measurement: MeasurementContext? = null, createdAt: Long = 1_000L,
    ) = AssistantRequest(
        requestId = id, conversationId = conversation, taskKind = kind, userText = text, inputOrigin = InputOrigin.SPEECH_WATCH,
        transcriptReviewed = true, replyMode = ReplyMode.TEXT, measurement = measurement, createdAtWatchEpochMs = createdAt,
    )

    fun encode(message: WireMessage, sender: String = "phone:p-1", seq: Long = 1): ByteArray =
        WireCodec.encode(Envelope(sender = sender, seq = seq, message = message))
}
