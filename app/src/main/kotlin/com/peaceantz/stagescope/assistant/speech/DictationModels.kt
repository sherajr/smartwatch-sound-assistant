package com.peaceantz.stagescope.assistant.speech

import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import kotlinx.serialization.Serializable

/**
 * How the person gives the assistant its words. Both ways open a *system* screen that hands back text, so StageScope never
 * records audio itself: [SPEECH] is the watch's own dictation screen (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`), [KEYBOARD] is
 * the watch's text-input screen (`RemoteInput`).
 */
@Serializable
enum class InputMethod { SPEECH, KEYBOARD }

/**
 * Everything about one question that must stay exactly as it was *before* the person spoke. It is captured once, kept intact
 * through the system screen (even if the app is recreated or the process dies), and becomes the [AssistantRequest] on Send.
 * It is never re-snapshotted after speech and never attached to a different task.
 */
@Serializable
data class DictationRequest(
    /** Doubles as the request id the eventual [AssistantRequest] carries, so a retransmit is recognised as the same question. */
    val sessionId: String,
    val task: TaskKind,
    val origin: SnapshotOrigin,
    /** The measurement as it was when the person asked -- with its original capture time. */
    val snapshot: MeasurementContext? = null,
    val conversationId: String,
    val editsActionId: String? = null,
    /** The performance this question refers to, chosen when it was asked (not when it is sent). */
    val performanceId: String? = null,
    val inputOrigin: InputOrigin = InputOrigin.SPEECH_WATCH,
    val startedAtEpochMs: Long,
    /** Whether measurement was running when the person asked (diagnostics; resuming is decided by [com.peaceantz.stagescope.audio.AudioCoordinator]). */
    val measurementWasRunning: Boolean = false,
    /** Set when this opened a transcript made by an older version's phone transcription, so that memo goes with it. */
    val legacyMemoId: String? = null,
)

/** Words the person has been given back and has not yet sent or discarded. One is kept, so closing the app doesn't lose it. */
@Serializable
data class DictationDraft(
    val request: DictationRequest,
    val text: String,
    val method: InputMethod,
    /** Plain wording for the screen: "Watch dictation", "Typed on this watch", or the older engine's own label. */
    val source: String,
    val savedAtEpochMs: Long,
) {
    /** Typing replaces dictation, so a typed draft is labelled as typed on the wire too. */
    val inputOrigin: InputOrigin get() = if (method == InputMethod.KEYBOARD) InputOrigin.TYPED else request.inputOrigin
}

/** A system input screen that was launched and has not reported back. Persisted *before* the launch so a killed process can still recover. */
@Serializable
data class InFlightDictation(
    val request: DictationRequest,
    val method: InputMethod,
    val launchedAtEpochMs: Long,
    /** Unique per attempt (a retry reuses the request id, so the id alone can't tell attempts apart). */
    val token: String,
)

@Serializable
data class DictationFile(
    val inFlight: InFlightDictation? = null,
    val draft: DictationDraft? = null,
)

/** Why a dictation did not produce words. Deliberately coarse: the platform does not tell us more, and nothing here guesses. */
enum class DictationFailureKind {
    /** The screen closed with nothing usable (or the recognizer reported no match). */
    NO_SPEECH,
    NETWORK,
    SERVER,
    AUDIO,
    CLIENT,

    /** A result code this version does not know. */
    UNKNOWN_RESULT,

    /** No installed activity handles the request. */
    NO_HANDLER,

    /** The system refused or failed to open the screen, or it never opened. */
    LAUNCH_FAILED,

    /** The phone is speaking a reply; dictating now would record it. */
    PHONE_SPEAKING,

    /** The system screen never reported back within the bound. It may still be open, so measurement is not restarted. */
    ABANDONED,
}

/** What a system input screen reported, after [DictationResults.interpret]. */
sealed interface DictationOutcome {
    data class Text(val text: String) : DictationOutcome

    /** The person backed out. Not an error and not "no speech". */
    data object Cancelled : DictationOutcome

    data class Failed(val kind: DictationFailureKind) : DictationOutcome
}

/** Pure construction of the request that goes to the phone, so the rule "Send uses the pre-speech context" is testable. */
object DictationRequests {
    fun toAssistantRequest(draft: DictationDraft, replyMode: ReplyMode, nowEpochMs: Long): AssistantRequest {
        val r = draft.request
        return AssistantRequest(
            requestId = r.sessionId,
            conversationId = r.conversationId,
            taskKind = r.task,
            userText = draft.text.trim(),
            inputOrigin = draft.inputOrigin,
            transcriptReviewed = true,
            replyMode = replyMode,
            measurement = r.snapshot,
            performanceId = r.performanceId,
            createdAtWatchEpochMs = nowEpochMs,
            editsActionId = r.editsActionId,
        )
    }
}
