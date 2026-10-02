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
    /** Longest a single dictation may run. Bounded so a stuck microphone can't listen on. */
    val listenSeconds: Int = 20,
    /** The performance this watch's questions and issue logs refer to. Null = follow the phone's selection. */
    val performanceOverrideId: String? = null,
)

@Serializable
enum class MemoState {
    /** Recorded on the watch; waiting for the phone to be reachable. */
    PENDING_PHONE,
    UPLOADING,
    TRANSCRIBING,
    TRANSCRIPT_READY,
    FAILED,
    SENT,
}

/**
 * A short recording kept on the watch (never a continuous recording): either a dictation the watch
 * couldn't transcribe itself, or a voice memo made while the phone was away. The measurement
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
