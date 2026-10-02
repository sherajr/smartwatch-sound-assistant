package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.audio.AudioMode
import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.show.WatchShowView

/** Something the person has to look at: a draft to review, a question that needs a decision, a transcript to check. */
sealed interface AttentionItem {
    val key: String

    data class Action(val conversationId: String, val conversationTitle: String, val card: ActionCard) : AttentionItem {
        override val key get() = "action:${card.actionId}"
    }

    /** A question that is stale (needs "send anyway?") or failed (needs retry or dismiss). */
    data class Question(val entry: OutboxEntry) : AttentionItem {
        override val key get() = "question:${entry.requestId}"
    }

    data class Memo(val memo: VoiceMemo) : AttentionItem {
        override val key get() = "memo:${memo.memoId}"
    }
}

/** Decides what belongs on the "needs you" list. Pure, so it is unit-tested. */
object AssistantAttention {
    private val ACTION_STATES = setOf(
        ActionState.AWAITING_REVIEW, ActionState.AWAITING_INFORMATION, ActionState.AWAITING_PHONE,
        ActionState.OUTCOME_UNCERTAIN, ActionState.FAILED,
    )

    fun build(threads: List<ThreadView>, outbox: List<OutboxEntry>, memos: List<VoiceMemo>): List<AttentionItem> = buildList {
        for (t in threads) for (card in t.actions) if (card.state in ACTION_STATES) add(AttentionItem.Action(t.conversationId, t.title, card))
        for (e in outbox) if (e.state == OutboxState.STALE || e.state == OutboxState.FAILED) add(AttentionItem.Question(e))
        for (m in memos) if (m.state == MemoState.TRANSCRIPT_READY || m.state == MemoState.FAILED) add(AttentionItem.Memo(m))
    }
}

enum class StatusSeverity { OK, NOTE, PROBLEM }

/** The one line under the microphone button on the Assistant page. */
data class PageStatus(val text: String, val severity: StatusSeverity)

/** Short, plain wording for the watch. Nothing here invents a status: it only restates what the data says. */
object AssistantFormatting {
    /**
     * What the Assistant page says it is doing / waiting for, in priority order: the audio hardware first (the person must
     * know why measurement paused), then work in progress, then whether the phone and provider are usable.
     */
    fun pageStatus(reachable: Boolean, providers: ProvidersView?, mode: AudioMode, working: OutboxEntry?): PageStatus {
        when (mode) {
            AudioMode.LISTENING -> return PageStatus("Listening…", StatusSeverity.OK)
            AudioMode.SPEAKING -> return PageStatus("Speaking… measurement paused", StatusSeverity.NOTE)
            AudioMode.PHONE_PLAYBACK -> return PageStatus("Phone is speaking… measurement paused", StatusSeverity.NOTE)
            AudioMode.IDLE -> Unit
        }
        if (working != null) return PageStatus(outboxLabel(working), StatusSeverity.OK)
        if (!reachable) return PageStatus("Phone not reachable. Questions wait up to 3 min.", StatusSeverity.NOTE)
        val (readiness, ready) = providerReadiness(providers)
        if (!ready) return PageStatus(readiness, StatusSeverity.PROBLEM)
        return PageStatus("Tap to ask", StatusSeverity.OK)
    }

    fun providerChip(view: ProvidersView?): String {
        val sel = view?.providers?.firstOrNull { it.providerId == view.selected } ?: return "Providers"
        val name = sel.providerId.shortLabel
        return when {
            view.devMode -> "$name · TEST MODE"
            !sel.hasKey -> "$name · no key"
            else -> "$name · ${sel.modelId}"
        }
    }

    /** What the setup checklist says about the active provider, and whether it is a problem. */
    fun providerReadiness(view: ProvidersView?): Pair<String, Boolean> {
        val sel = view?.providers?.firstOrNull { it.providerId == view.selected } ?: return "Waiting for your phone" to false
        return when {
            view.devMode -> "Test mode (no real AI)" to false
            !sel.hasKey -> "${sel.providerId.shortLabel} needs a key on your phone" to false
            !sel.keyValidated -> "${sel.providerId.shortLabel} key not checked yet" to true
            else -> "${sel.providerId.shortLabel} is ready" to true
        }
    }

    fun showChip(shows: WatchShowView?, overrideId: String?): String {
        val perfId = overrideId ?: shows?.selectedPerformanceId
        val perf = shows?.performances?.firstOrNull { it.id == perfId }
        val prod = shows?.productions?.firstOrNull { it.id == (perf?.productionId ?: shows.selectedProductionId) }
        return when {
            perf != null && prod != null -> "${prod.name} · ${perf.label.substringBefore(" · ")}"
            prod != null -> prod.name
            else -> "No show selected"
        }
    }

    fun progressText(stage: ProgressStage?, text: String?): String = when (stage) {
        null, ProgressStage.QUEUED -> "Waiting for your phone…"
        ProgressStage.THINKING -> "Thinking…"
        ProgressStage.USING_TOOL -> if (text.isNullOrBlank()) "Working…" else "Using ${text.take(24)}…"
        ProgressStage.WRITING -> "Writing…"
        ProgressStage.FINISHING -> "Finishing…"
    }

    fun outboxLabel(e: OutboxEntry): String = when (e.state) {
        OutboxState.QUEUED_OFFLINE -> "Waiting for your phone"
        OutboxState.SENDING -> "Sending…"
        OutboxState.ACKED -> "Your phone has it"
        OutboxState.RUNNING -> progressText(e.progress, e.progressText)
        OutboxState.RESULT_READY -> "Answer ready"
        OutboxState.FAILED -> e.message ?: "Didn't go through"
        OutboxState.CANCELLED -> e.message ?: "Cancelled"
        OutboxState.STALE -> "Old question — not sent"
        OutboxState.EXPIRED -> "Expired"
    }

    /** "3 s ago", "4 min ago", "2 h ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val s = ((nowMs - thenMs).coerceAtLeast(0L)) / 1000L
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** Provider-reported cost wins; otherwise the app's dated estimate; otherwise honestly unknown. */
    fun usageLine(u: UsageSummary?): String? {
        u ?: return null
        if (u.inputTokens == 0L && u.outputTokens == 0L) return null
        val cost = u.reportedCostMicros?.let { usd(it) + " reported" } ?: u.estimatedCostMicros?.let { usd(it) + " est." } ?: "cost unknown"
        return "${compact(u.inputTokens)} in · ${compact(u.outputTokens)} out · $cost"
    }

    fun usd(micros: Long): String {
        val d = micros / 1_000_000.0
        return "$" + if (d < 0.1) "%.4f".format(d) else "%.2f".format(d)
    }

    private fun compact(n: Long): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

    fun spokenSummary(view: ThreadView): String = (view.summary ?: view.detail.orEmpty()).take(600)

    /**
     * Long text cut into list-item-sized pieces: at blank lines first, then (for a paragraph longer than [maxChunk]) at sentence
     * ends, packing whole sentences together. Wear's scrolling list shrinks and fades each *item* near the round screen's edge, so
     * one very tall item is clipped by the bezel as a block -- characters vanish at both ends of every line near the edge --
     * where several short items scale away cleanly. Nothing is dropped or reworded: joining the pieces with a space gives back the
     * same words.
     */
    fun paragraphs(text: String, maxChunk: Int = 220): List<String> =
        text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { p ->
            if (p.length <= maxChunk) return@flatMap listOf(p)
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in p.split(Regex("(?<=[.!?])\\s+"))) {
                if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChunk) {
                    chunks += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
            if (current.isNotEmpty()) chunks += current.toString()
            chunks
        }

    fun providerLabel(p: ProviderId) = p.shortLabel
}
