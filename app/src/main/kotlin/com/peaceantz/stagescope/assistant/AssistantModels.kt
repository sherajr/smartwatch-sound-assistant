package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.VoicePurpose
import com.peaceantz.stagescope.shared.show.WatchShowView
import kotlinx.serialization.Serializable
import java.util.UUID

/** Where a question the person asked is in its trip to the phone and back. Persisted, so a restart loses nothing. */
@Serializable
enum class OutboxState {
    /** Saved; the phone wasn't reachable. Sent automatically only while it's still fresh (see [OutboxPolicy]). */
    QUEUED_OFFLINE,

    /** Handed to the transport; waiting for the phone's own "I have it". A successful send is not an ack. */
    SENDING,

    /** The phone persisted it (and will work on it even if the watch goes away). */
    ACKED,

    /** The phone reports it is working on it. */
    RUNNING,

    /** The phone's answer is available (a durable thread view). */
    RESULT_READY,

    FAILED,
    CANCELLED,

    /** Queued so long ago that sending it now would be sending an old question about an old moment: ask first. */
    STALE,

    EXPIRED;

    val isFinal: Boolean get() = this == RESULT_READY || this == FAILED || this == CANCELLED || this == EXPIRED
    val isWaitingOnPhone: Boolean get() = this == SENDING || this == ACKED || this == RUNNING
}

@Serializable
data class OutboxEntry(
    val request: AssistantRequest,
    val state: OutboxState,
    val createdAtEpochMs: Long,
    val lastAttemptAtEpochMs: Long? = null,
    val attempts: Int = 0,
    val ackedAtEpochMs: Long? = null,
    val progress: ProgressStage? = null,
    val progressText: String? = null,
    val resultRevision: Long? = null,
    /** Words for the screen: why it failed, why it's stale, what the phone said. */
    val message: String? = null,
) {
    val requestId: String get() = request.requestId
    val conversationId: String get() = request.conversationId
}

@Serializable
data class OutboxFile(val entries: List<OutboxEntry> = emptyList())

/** Compact copies of what the phone last told us. Cached so the watch shows something useful offline. */
@Serializable
data class PhoneCache(
    val threads: List<ThreadView> = emptyList(),
    val providers: ProvidersView? = null,
    val shows: WatchShowView? = null,
    val phoneAppVersion: String? = null,
    val negotiatedProtocolVersion: Int? = null,
    val lastHeardFromPhoneEpochMs: Long? = null,
)

@Serializable
data class AssistantPrefs(
    val installId: String = UUID.randomUUID().toString(),
    /** How replies are delivered. Text by default: a theatre is not a place for a talking watch. */
    val outputMode: ReplyMode = ReplyMode.TEXT,
    /** Theatre mode (default ON): nothing is ever spoken or vibrated unless the person asks for it right then. */
    val theatreMode: Boolean = true,
    val hapticsEnabled: Boolean = false,
    /**
     * Unused since dictation moved to the watch's own dictation screen (which decides when it stops). Kept only so an existing
     * prefs.json decodes and round-trips; nothing reads or shows it.
     */
    val listenSeconds: Int = 20,
    /** The performance this watch's questions and issue logs refer to. Null = follow the phone's selection. */
    val performanceOverrideId: String? = null,
)

@Serializable
enum class MemoState {
    // PENDING_PHONE / UPLOADING / TRANSCRIBING are what an earlier version wrote while a recording waited for the phone. They stay
    // here so an existing memos.json still decodes; AssistantRepository.migrateLegacyMemos turns them into LEGACY_RECORDING.
    PENDING_PHONE,
    UPLOADING,
    TRANSCRIBING,

    /** Words made by an earlier version's phone transcription. They can still be reviewed as text. */
    TRANSCRIPT_READY,
    FAILED,
    SENT,

    /**
     * A recording an earlier version made. Phone transcription is turned off, so it is never uploaded: it stays on this watch,
     * untouched, until the person deletes it.
     */
    LEGACY_RECORDING,
}

/**
 * A short recording an earlier version of StageScope made (either a dictation its watch couldn't transcribe, or a voice memo made
 * while the phone was away), or the transcript the phone made from one. **New versions never create these.** The measurement
 * [snapshot] was taken *before* the recording started and keeps its own capture time.
 */
@Serializable
data class VoiceMemo(
    val memoId: String,
    val requestId: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val byteCount: Long,
    val purpose: VoicePurpose,
    val taskKind: TaskKind,
    val state: MemoState,
    val snapshot: MeasurementContext? = null,
    val performanceId: String? = null,
    val transcript: String? = null,
    val engine: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class MemoFile(val memos: List<VoiceMemo> = emptyList())
