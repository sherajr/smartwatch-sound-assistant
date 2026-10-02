package com.peaceantz.stagescope.shared.protocol

import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.SourceRef
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.assistant.TurnError
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * StageScope's Wear Data Layer protocol (watch <-> phone). Everything rides the supported
 * Data Layer clients -- no custom Bluetooth socket:
 *  - MessageClient: reachable-device commands, application-level acks, cancellation, compact progress;
 *  - DataClient: durable synchronized *views* (thread, shows, providers, per-replica issue state);
 *  - ChannelClient: voice audio too large for a message.
 * A successful `sendMessage` only means "handed to the transport"; the receiver's [Ack] (and, for
 * work, a durable result the watch can re-read after a reconnect) is what confirms anything.
 */
object Wire {
    const val PROTOCOL_VERSION = 1
    const val MIN_SUPPORTED_VERSION = 1

    /** Capability names advertised by each app (res/values/wear.xml) so the other side can find it. */
    const val CAPABILITY_PHONE = "stagescope_phone_companion"
    const val CAPABILITY_WATCH = "stagescope_watch_app"

    const val ROOT = "/stagescope/v1"

    // MessageClient paths.
    const val MSG = "$ROOT/msg"

    // DataClient paths.
    const val DATA_THREAD_PREFIX = "$ROOT/thread"
    const val DATA_SHOWS = "$ROOT/shows"
    const val DATA_PROVIDERS = "$ROOT/providers"
    const val DATA_ISSUES_PREFIX = "$ROOT/issues"

    // ChannelClient path for voice audio.
    const val CHANNEL_VOICE = "$ROOT/voice"

    /** Conservative cap for one message/data item (platform limit is ~100 KB; leave headroom). */
    const val MAX_PAYLOAD_BYTES = 60_000

    fun threadPath(conversationId: String) = "$DATA_THREAD_PREFIX/$conversationId"
    fun issuePath(replicaId: String, issueId: String) = "$DATA_ISSUES_PREFIX/$replicaId/$issueId"
}

@Serializable
enum class DeviceRole { WATCH, PHONE }

@Serializable
enum class AckStatus {
    /** Persisted durably by the receiver; work will proceed. */
    RECEIVED,
    /** The receiver already had this request id (retransmission); nothing new started. */
    DUPLICATE,
    /** Refused, with a reason the sender can show (no key, limit reached, bad payload...). */
    REJECTED,
}

@Serializable
enum class RequestState { QUEUED, RUNNING, WAITING_FOR_ACTION, COMPLETED, FAILED, CANCELLED, UNKNOWN }

@Serializable
enum class ProgressStage { QUEUED, THINKING, USING_TOOL, WRITING, FINISHING }

@Serializable
enum class ActionCommandKind { CONFIRM, CANCEL, MARK_DONE, HANDOFF_OPENED, REOPEN, UNCERTAIN_WAS_DONE, UNCERTAIN_NOT_DONE, UNDO }

@Serializable
enum class ContinueOutcome {
    /** The phone confirmed the exact task is on screen. */
    OPENED,
    /** A notification that opens the exact saved task was posted. */
    NOTIFICATION_POSTED,
    /** Notifications are disabled; the task is saved under Recent on the phone. */
    SAVED_NOTIFICATIONS_DISABLED,
    NOT_FOUND,
}

@Serializable
enum class PlaybackState { STARTED, STOPPED }

@Serializable
enum class VoicePurpose { DICTATION, MEMO }

@Serializable
sealed interface WireMessage

@Serializable
@SerialName("hello")
data class Hello(
    val installId: String,
    val role: DeviceRole,
    val appVersionName: String,
    val appVersionCode: Long,
    val minVersion: Int = Wire.MIN_SUPPORTED_VERSION,
    val maxVersion: Int = Wire.PROTOCOL_VERSION,
    /** Set on a reply: the version both sides will speak. */
    val selectedVersion: Int? = null,
    val features: Set<String> = emptySet(),
    val timezoneId: String? = null,
) : WireMessage

@Serializable
@SerialName("request")
data class AssistantRequest(
    val requestId: String,
    val conversationId: String,
    val taskKind: TaskKind,
    val userText: String,
    val inputOrigin: InputOrigin,
    /** The person reviewed/corrected the transcript before sending. */
    val transcriptReviewed: Boolean,
    val replyMode: ReplyMode,
    /** Snapshot taken BEFORE dictation started; its capture time is preserved. */
    val measurement: MeasurementContext? = null,
    /** For before/after questions: the earlier snapshot. */
    val comparisonBefore: MeasurementContext? = null,
    val performanceId: String? = null,
    val createdAtWatchEpochMs: Long,
    /** Set when this request continues an earlier one (e.g. a voice edit of a draft). */
    val editsActionId: String? = null,
) : WireMessage

@Serializable
@SerialName("ack")
data class Ack(
    val requestId: String,
    val status: AckStatus,
    val detail: String? = null,
) : WireMessage

@Serializable
@SerialName("cancel")
data class CancelRequest(val requestId: String) : WireMessage

@Serializable
@SerialName("progress")
data class Progress(
    val requestId: String,
    val stage: ProgressStage,
    val text: String? = null,
) : WireMessage

@Serializable
@SerialName("result_ready")
data class ResultReady(
    val requestId: String,
    val conversationId: String,
    /** Revision of the durable [ThreadView] to read; lets a reconnecting watch skip stale ones. */
    val revision: Long,
    val state: RequestState,
) : WireMessage

@Serializable
@SerialName("status_query")
data class StatusQuery(val requestId: String) : WireMessage

@Serializable
@SerialName("status_reply")
data class StatusReply(
    val requestId: String,
    val state: RequestState,
    val conversationId: String? = null,
    val revision: Long? = null,
) : WireMessage

@Serializable
@SerialName("action_command")
data class ActionCommand(
    /** Idempotency key: a retransmit of the same command is recognised and ignored. */
    val requestId: String,
    val actionId: String,
    val command: ActionCommandKind,
    /** Revision/hash the person was looking at; the phone refuses a mismatch. */
    val revision: Int? = null,
    val contentHash: String? = null,
) : WireMessage

@Serializable
@SerialName("action_reply")
data class ActionReply(
    val requestId: String,
    val actionId: String,
    val accepted: Boolean,
    val state: ActionState,
    val message: String,
) : WireMessage

@Serializable
@SerialName("continue")
data class ContinueOnPhone(
    val requestId: String,
    val conversationId: String? = null,
    val actionId: String? = null,
) : WireMessage

@Serializable
@SerialName("continue_reply")
data class ContinueReply(
    val requestId: String,
    val outcome: ContinueOutcome,
    val detail: String? = null,
) : WireMessage

@Serializable
@SerialName("provider_select")
data class ProviderSelect(
    val requestId: String,
    val providerId: ProviderId,
    val modelId: String? = null,
    val thorough: Boolean? = null,
    val webSearch: Boolean? = null,
) : WireMessage

@Serializable
@SerialName("playback")
data class PlaybackNotice(val utteranceId: String, val state: PlaybackState) : WireMessage

@Serializable
@SerialName("sync_nudge")
data class SyncNudge(val reason: String) : WireMessage

@Serializable
@SerialName("voice_offer")
data class VoiceOffer(
    val requestId: String,
    val memoId: String,
    val purpose: VoicePurpose,
    val sampleRateHz: Int,
    val durationMs: Long,
    val byteCount: Long,
) : WireMessage

@Serializable
@SerialName("transcript")
data class TranscriptResult(
    val requestId: String,
    val memoId: String,
    val text: String? = null,
    val engine: String? = null,
    val error: String? = null,
) : WireMessage

@Serializable
data class Envelope(
    val v: Int = Wire.PROTOCOL_VERSION,
    /** `watch:<installId>` or `phone:<installId>`. */
    val sender: String,
    /** Per-sender counter (not a clock): lets a receiver notice gaps/duplicates. */
    val seq: Long,
    val message: WireMessage,
)

sealed interface Decoded {
    data class Ok(val envelope: Envelope) : Decoded
    data class UnsupportedVersion(val version: Int) : Decoded
    data class Malformed(val reason: String) : Decoded
}

object WireCodec {
    class PayloadTooLarge(val bytes: Int) : IllegalArgumentException("Payload of $bytes bytes exceeds ${Wire.MAX_PAYLOAD_BYTES}")

    fun encode(envelope: Envelope): ByteArray {
        val bytes = StageScopeJson.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        if (bytes.size > Wire.MAX_PAYLOAD_BYTES) throw PayloadTooLarge(bytes.size)
        return bytes
    }

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size > 4 * Wire.MAX_PAYLOAD_BYTES) return Decoded.Malformed("payload too large")
        val text = bytes.toString(Charsets.UTF_8)
        // Peek at the version first so a *newer* peer's message is reported as unsupported, not as junk.
        val version = Regex("\"v\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (version != null && version !in Wire.MIN_SUPPORTED_VERSION..Wire.PROTOCOL_VERSION) {
            return Decoded.UnsupportedVersion(version)
        }
        return try {
            Decoded.Ok(StageScopeJson.decodeFromString(Envelope.serializer(), text))
        } catch (e: Exception) {
            Decoded.Malformed(e.message?.take(160) ?: "undecodable")
        }
    }

    /** Both sides advertise [min, max]; the highest common version, or null if the ranges don't overlap. */
    fun negotiate(localMin: Int, localMax: Int, remoteMin: Int, remoteMax: Int): Int? {
        val hi = minOf(localMax, remoteMax)
        val lo = maxOf(localMin, remoteMin)
        return if (hi >= lo) hi else null
    }
}

// ---------------------------------------------------------------------------------------------
// Durable synchronized views (DataClient). Written by the phone; read, cached and rendered by the
// watch. A watch that was offline when a result finished simply reads the latest revision on
// reconnect -- nothing depends on having been listening at the moment it was produced.
// ---------------------------------------------------------------------------------------------

@Serializable
data class ThreadView(
    val conversationId: String,
    val revision: Long,
    val title: String,
    val providerId: ProviderId,
    val providerLabel: String,
    /** The actual API model id in use -- always visible, never silently substituted. */
    val modelId: String,
    val requestId: String? = null,
    val requestState: RequestState = RequestState.COMPLETED,
    /** Compact summary suitable for a wrist; the full answer is on the phone. */
    val summary: String? = null,
    /** Bounded detail text the watch can scroll; null when only the phone should show it. */
    val detail: String? = null,
    val detailTruncated: Boolean = false,
    val sources: List<SourceRef> = emptyList(),
    val actions: List<ActionCard> = emptyList(),
    val error: TurnError? = null,
    val usage: UsageSummary? = null,
    val webSearchUsed: Boolean? = null,
    val measurementHeadline: String? = null,
    val updatedAtPhoneEpochMs: Long = 0,
)

@Serializable
data class ProviderStatus(
    val providerId: ProviderId,
    val label: String,
    val hasKey: Boolean,
    val keyValidated: Boolean,
    val modelId: String,
    val webSearchAvailable: Boolean,
    val webSearchEnabled: Boolean,
    val thorough: Boolean,
)

@Serializable
data class ProvidersView(
    val revision: Long,
    val selected: ProviderId,
    val providers: List<ProviderStatus>,
    val googleConnected: Boolean = false,
    val gmailReady: Boolean = false,
    val calendarReady: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val phoneAppVersion: String = "",
    val devMode: Boolean = false,
    val monthlyBudgetStatus: String? = null,
)
