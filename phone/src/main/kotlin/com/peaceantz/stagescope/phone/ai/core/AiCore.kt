package com.peaceantz.stagescope.phone.ai.core

import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.SourceRef
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import kotlinx.serialization.json.JsonObject

/** A tool the model may *propose* to call. [parameters] is a JSON Schema whose root is an object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/**
 * One complete tool call. [argumentsJson] is the fully **assembled** argument text: streamed
 * fragments are joined by the adapter before this object exists, so nothing downstream ever parses
 * a half-received argument. It is *not yet validated* -- the tool registry does that.
 */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ToolResult(val callId: String, val name: String, val content: String, val isError: Boolean)

/** The visible conversation, provider-neutral: no vendor metadata ever lives here. */
sealed interface NeutralMessage {
    val text: String

    data class User(override val text: String) : NeutralMessage
    data class Assistant(override val text: String) : NeutralMessage
}

enum class Effort { FAST, BALANCED, THOROUGH }

/**
 * Adapter-private continuation of ONE tool-using turn (e.g. the model's own output items,
 * thought signatures, or encrypted reasoning that the provider requires to be echoed back
 * unchanged). Created and consumed only by the adapter that produced it, held in memory for the
 * duration of the turn, **never persisted and never handed to another provider** -- switching vendor
 * starts from the neutral conversation, which is how a Gemini thought signature or an OpenAI
 * response id can never be transplanted to Claude.
 */
interface ProviderLoopState

data class ProviderRequest(
    val model: String,
    val system: String,
    val history: List<NeutralMessage>,
    val tools: List<ToolSpec>,
    val maxOutputTokens: Int,
    val effort: Effort,
    val webSearch: Boolean,
    /** Present on the 2nd+ call of a turn: the state from the previous call plus its tool results. */
    val loop: ProviderLoopState? = null,
    val toolResults: List<ToolResult> = emptyList(),
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE, OTHER }

data class ProviderTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stop: StopReason,
    val usage: UsageSummary,
    val sources: List<SourceRef>,
    val webSearchUsed: Boolean,
    val loopState: ProviderLoopState?,
    /** The model id the provider says actually served the request, when it reports one. */
    val servedModel: String?,
)

sealed interface ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent
    data class ToolCallStarted(val name: String) : ProviderEvent
    data object Thinking : ProviderEvent
    data object WebSearching : ProviderEvent
}

typealias EventSink = (ProviderEvent) -> Unit

class ProviderException(
    val kind: TurnErrorKind,
    override val message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    /** True when a retry could plausibly succeed *and* nothing was submitted that a retry would repeat. */
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

data class KeyCheck(val ok: Boolean, val kind: TurnErrorKind? = null, val message: String? = null, val models: List<String> = emptyList())

/**
 * One implemented adapter per vendor. No fake/"coming soon" adapters: each speaks its vendor's real
 * wire format (see docs/AI_ARCHITECTURE.md for the contracts verified on 2026-10-01).
 */
interface ProviderAdapter {
    val id: ProviderId

    /** Runs ONE model call (one streamed response), assembling tool calls completely before returning. */
    suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn

    /** Cheap authenticated read (a model-list call): proves the key works without spending tokens. */
    suspend fun validateKey(): KeyCheck
}

/** Where an adapter reads its key from. Keys are fetched per call and never held by the adapter. */
fun interface KeySource {
    fun key(provider: ProviderId): String?
}
