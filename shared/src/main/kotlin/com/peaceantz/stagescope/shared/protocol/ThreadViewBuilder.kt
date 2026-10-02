package com.peaceantz.stagescope.shared.protocol

import com.peaceantz.stagescope.shared.actions.ActionPresentation
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.assistant.Conversation
import com.peaceantz.stagescope.shared.assistant.TurnRole

/** Builds the compact, durable [ThreadView] the watch reads. Pure so its truncation rules are testable. */
object ThreadViewBuilder {
    const val MAX_SUMMARY_CHARS = 280
    const val MAX_WATCH_DETAIL_CHARS = 6_000
    const val MAX_ACTION_CARDS = 6

    fun build(
        conversation: Conversation,
        actions: List<ActionRecord>,
        revision: Long,
        requestState: RequestState,
        nowEpochMs: Long,
        providerLabel: String,
    ): ThreadView {
        val lastAssistant = conversation.turns.lastOrNull { it.role == TurnRole.ASSISTANT }
        val referencedIds = conversation.turns.takeLast(8).flatMap { it.actionIds }.distinct()
        val cards = referencedIds.mapNotNull { id -> actions.firstOrNull { it.actionId == id } }
            .takeLast(MAX_ACTION_CARDS).map(ActionPresentation::card)

        val text = lastAssistant?.text.orEmpty()
        val summary = lastAssistant?.watchSummary?.takeIf { it.isNotBlank() } ?: derivedSummary(text)
        val truncated = text.length > MAX_WATCH_DETAIL_CHARS
        return ThreadView(
            conversationId = conversation.id,
            revision = revision,
            title = conversation.title,
            providerId = conversation.providerId,
            providerLabel = providerLabel,
            modelId = lastAssistant?.modelId ?: conversation.modelId,
            requestId = conversation.lastRequestId,
            requestState = requestState,
            summary = summary.takeIf { it.isNotBlank() },
            detail = text.take(MAX_WATCH_DETAIL_CHARS).takeIf { it.isNotBlank() },
            detailTruncated = truncated,
            sources = lastAssistant?.sources.orEmpty().take(6),
            actions = cards,
            error = lastAssistant?.error,
            usage = lastAssistant?.usage,
            webSearchUsed = lastAssistant?.webSearchUsed,
            measurementHeadline = conversation.measurements.lastOrNull()?.headline,
            updatedAtPhoneEpochMs = nowEpochMs,
        )
    }

    /** First sentence(s) up to [MAX_SUMMARY_CHARS], cut at a word/sentence boundary, never mid-word. */
    fun derivedSummary(text: String): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        if (flat.length <= MAX_SUMMARY_CHARS) return flat
        val window = flat.take(MAX_SUMMARY_CHARS)
        val sentenceEnd = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd >= MAX_SUMMARY_CHARS / 3) return window.take(sentenceEnd + 1)
        val wordEnd = window.lastIndexOf(' ')
        return (if (wordEnd > 0) window.take(wordEnd) else window) + "…"
    }

    /**
     * The model is asked to open its reply with `<watch_summary>…</watch_summary>`; this splits that
     * summary off the full answer. If it's missing the summary is derived locally -- the reasoning
     * itself is never shortened to fit a watch.
     */
    fun splitWatchSummary(modelText: String): Pair<String?, String> {
        val open = "<watch_summary>"
        val close = "</watch_summary>"
        val start = modelText.indexOf(open)
        val end = modelText.indexOf(close)
        if (start < 0 || end < start) return null to modelText.trim()
        val summary = modelText.substring(start + open.length, end).trim().replace(Regex("\\s+"), " ")
        val rest = (modelText.substring(0, start) + modelText.substring(end + close.length)).trim()
        return summary.take(MAX_SUMMARY_CHARS * 2).takeIf { it.isNotBlank() } to rest
    }
}
