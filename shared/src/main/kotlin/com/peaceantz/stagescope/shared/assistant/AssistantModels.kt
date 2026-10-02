package com.peaceantz.stagescope.shared.assistant

import kotlinx.serialization.Serializable

/** The four selectable AI providers. The id is stable; [label] is what people read. */
@Serializable
enum class ProviderId(val label: String, val shortLabel: String) {
    OPENAI("ChatGPT / OpenAI", "OpenAI"),
    GEMINI("Google Gemini", "Gemini"),
    XAI("xAI Grok", "Grok"),
    ANTHROPIC("Anthropic Claude", "Claude"),
}

/** What the person asked StageScope to do. [FREE_CHAT] is the general-purpose assistant. */
@Serializable
enum class TaskKind(val label: String) {
    FREE_CHAT("Ask anything"),
    ANALYZE_SOUND("Analyze sound / rings"),
    EMAIL_REPORT("Email performance report"),
    EMAIL_ISSUE("Email about an issue"),
    LOG_ISSUE("Log an issue"),
    KEEP_ITEM("Add to Keep list"),
    CALENDAR_EVENT("Add calendar event"),
}

@Serializable
enum class InputOrigin {
    TYPED,
    SPEECH_WATCH,
    SPEECH_PHONE,
    VOICE_MEMO_TRANSCRIBED,
    /** A tap on a task shortcut with no free text yet. */
    QUICK_ACTION,
}

@Serializable
enum class ReplyMode { TEXT, VOICE, BOTH }

@Serializable
enum class TurnRole { USER, ASSISTANT, NOTE }

@Serializable
data class SourceRef(val title: String, val url: String)

@Serializable
enum class ToolStatus { OK, ERROR, REJECTED }

/**
 * What a tool call did, kept as visible conversation context. This is how a *provider switch* keeps
 * its memory without replaying tool calls: the new provider is shown "StageScope already did X"
 * as text, never the old vendor's tool-call blocks, so a confirmed external action can never be
 * resent by another model.
 */
@Serializable
data class ToolOutcome(
    val callId: String,
    val toolName: String,
    val argsSummary: String,
    val resultSummary: String,
    val status: ToolStatus,
    val producedActionId: String? = null,
)

@Serializable
data class UsageSummary(
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val webSearchCalls: Int = 0,
    /** Estimated cost in micro-USD from a dated price table; null when no reliable price is known. */
    val estimatedCostMicros: Long? = null,
    /** Provider-reported exact cost in micro-USD when the API returns one (e.g. xAI). */
    val reportedCostMicros: Long? = null,
)

@Serializable
enum class TurnErrorKind {
    MISSING_KEY, INVALID_KEY, PERMISSION_DENIED, RATE_LIMITED, QUOTA_EXHAUSTED, MODEL_UNAVAILABLE,
    CONTEXT_TOO_LONG, OVERLOADED, TIMEOUT, NETWORK, CONTENT_REFUSED, BAD_REQUEST, SERVER_ERROR,
    CANCELLED, LIMIT_REACHED, TOOL_LOOP_LIMIT, MALFORMED_RESPONSE, UNKNOWN,
}

@Serializable
data class TurnError(
    val kind: TurnErrorKind,
    /** Plain-language, actionable, and free of credentials or raw payloads. */
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class ChatTurn(
    val id: String,
    val role: TurnRole,
    val text: String,
    /** Ledger-local sequence (not a wall clock): ordering inside one conversation. */
    val seq: Long,
    val atEpochMs: Long,
    val providerId: ProviderId? = null,
    val modelId: String? = null,
    val taskKind: TaskKind? = null,
    val inputOrigin: InputOrigin? = null,
    val requestId: String? = null,
    val measurementIds: List<String> = emptyList(),
    val toolOutcomes: List<ToolOutcome> = emptyList(),
    val actionIds: List<String> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    /** Compact summary for the watch; the full [text] stays available on the phone. */
    val watchSummary: String? = null,
    val usage: UsageSummary? = null,
    val error: TurnError? = null,
    /** true = a web search ran; false = search was on but the model did not use it; null = not offered. */
    val webSearchUsed: Boolean? = null,
)

/** Deterministic, local summary of turns that fell out of the bounded history window. */
@Serializable
data class ConversationSummary(
    val text: String,
    val coversTurnsThroughSeq: Long,
)

@Serializable
data class MeasurementRef(
    val snapshotId: String,
    val capturedAtEpochMs: Long,
    val origin: String,
    val isDemo: Boolean,
    val headline: String,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val nextSeq: Long = 1,
    val turns: List<ChatTurn> = emptyList(),
    val summary: ConversationSummary? = null,
    val providerId: ProviderId,
    val modelId: String,
    val performanceId: String? = null,
    val measurements: List<MeasurementRef> = emptyList(),
    /** Last request still in flight / finished; lets a reconnecting watch ask "what happened to it". */
    val lastRequestId: String? = null,
)

/**
 * Builds the bounded, provider-neutral history a provider is shown. Older turns collapse into a
 * deterministic [ConversationSummary]; recent turns are kept verbatim up to a character budget.
 */
object ConversationWindow {
    const val MAX_RECENT_TURNS = 14
    const val MAX_RECENT_CHARS = 12_000
    const val MAX_SUMMARY_CHARS = 1_800

    data class Window(val summaryText: String?, val turns: List<ChatTurn>)

    fun build(conversation: Conversation): Window {
        val visible = conversation.turns.filter { it.role != TurnRole.NOTE || it.text.isNotBlank() }
        var kept = visible.takeLast(MAX_RECENT_TURNS)
        var chars = kept.sumOf { it.text.length }
        while (chars > MAX_RECENT_CHARS && kept.size > 2) {
            chars -= kept.first().text.length
            kept = kept.drop(1)
        }
        val dropped = visible.take(visible.size - kept.size)
        val summary = if (dropped.isEmpty()) conversation.summary?.text else summarize(conversation.summary?.text, dropped)
        return Window(summary, kept)
    }

    /** Extractive and deterministic: no model call, no cost, same input -> same output. */
    fun summarize(existing: String?, dropped: List<ChatTurn>): String {
        val lines = buildList {
            existing?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            for (t in dropped) {
                val who = if (t.role == TurnRole.USER) "User" else "Assistant"
                val body = t.text.replace(Regex("\\s+"), " ").trim().take(160)
                if (body.isNotEmpty()) add("$who: $body")
                for (o in t.toolOutcomes) add("StageScope already did: ${o.toolName} -> ${o.resultSummary.take(100)}")
            }
        }
        val joined = lines.joinToString("\n")
        return if (joined.length <= MAX_SUMMARY_CHARS) joined else "…" + joined.takeLast(MAX_SUMMARY_CHARS - 1)
    }
}
