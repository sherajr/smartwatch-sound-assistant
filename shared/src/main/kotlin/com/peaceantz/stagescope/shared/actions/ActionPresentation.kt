package com.peaceantz.stagescope.shared.actions

import com.peaceantz.stagescope.shared.util.StageScopeJson

/**
 * Turns an [ActionRecord] into what a person is told. The wording is part of the safety story: a
 * handoff says "Ready on phone" (never "Added"), a user-marked completion says so, and an uncertain
 * send says it is uncertain.
 */
object ActionPresentation {
    /** A watch can only honestly confirm what it can show in full. */
    const val MAX_WATCH_REVIEW_CHARS = 24_000
    private const val TRUNCATED_PREVIEW_CHARS = 1_500

    fun card(record: ActionRecord): ActionCard {
        val reviewableOnWatch = record.state == ActionState.AWAITING_REVIEW &&
            record.missingInformation.isEmpty() &&
            record.kind.needsConfirmation &&
            StageScopeJson.encodeToString(ActionDraft.serializer(), record.draft).length <= MAX_WATCH_REVIEW_CHARS
        val draftForCard = if (reviewableOnWatch || !isLarge(record.draft)) record.draft else previewOnly(record.draft)
        return ActionCard(
            actionId = record.actionId, kind = record.kind, state = record.state, revision = record.revision,
            contentHash = record.contentHash, title = title(record.draft), summary = summary(record.draft),
            draft = draftForCard, missingInformation = record.missingInformation, warnings = record.warnings,
            receiptSummary = record.receipt?.summary, errorMessage = record.error?.message,
            completionEvidence = record.completionEvidence, handoffOpened = record.handoffOpened,
            canConfirmOnWatch = reviewableOnWatch, statusLine = statusLine(record),
        )
    }

    fun title(draft: ActionDraft): String = when (draft) {
        is EmailDraft -> if (draft.purpose == EmailPurpose.PERFORMANCE_REPORT) "Performance report email" else "Issue email"
        is CalendarDraft -> "Calendar event"
        is KeepDraft -> "Keep item"
        is IssueLogDraft -> "Issue logged"
    }

    fun summary(draft: ActionDraft): String = when (draft) {
        is EmailDraft -> {
            val to = draft.to.joinToString { it.name ?: it.address }.ifEmpty { "no recipients yet" }
            "To $to · ${draft.subject.ifBlank { "(no subject)" }}"
        }
        is CalendarDraft -> "${draft.title} · ${draft.startLocal.replace('T', ' ')} ${draft.timezoneId}"
        is KeepDraft -> "${draft.itemText}${draft.listName?.let { " → $it" } ?: ""}"
        is IssueLogDraft -> draft.summary
    }

    fun statusLine(r: ActionRecord): String = when (r.state) {
        ActionState.DRAFT -> "Draft"
        ActionState.AWAITING_INFORMATION -> "Needs info: " + (r.missingInformation.firstOrNull() ?: "more details")
        ActionState.AWAITING_REVIEW -> when (r.kind) {
            ActionKind.EMAIL -> "Review before sending"
            ActionKind.CALENDAR_EVENT -> "Review before adding"
            else -> "Review"
        }
        ActionState.AWAITING_PHONE -> when {
            r.completionEvidence == CompletionEvidence.USER_MARKED -> "Marked done by you"
            r.handoffOpened -> "Opened on phone — not yet marked done"
            else -> "Ready on phone"
        }
        ActionState.EXECUTING -> if (r.kind == ActionKind.EMAIL) "Sending…" else "Adding…"
        ActionState.COMPLETED -> when (r.completionEvidence) {
            CompletionEvidence.API_CONFIRMED -> r.receipt?.summary ?: "Done"
            CompletionEvidence.LOCAL_WRITE -> r.receipt?.summary ?: "Saved"
            CompletionEvidence.USER_MARKED -> "Marked done by you (not verified by ${if (r.kind == ActionKind.KEEP_ITEM) "Keep" else "StageScope"})"
            CompletionEvidence.NONE -> "Done"
        }
        ActionState.FAILED -> "Didn't go through: " + (r.error?.message ?: "unknown error")
        ActionState.CANCELLED -> if (r.kind == ActionKind.LOG_ISSUE) "Undone — removed from the issue log" else "Cancelled"
        ActionState.OUTCOME_UNCERTAIN ->
            "Outcome unknown — ${if (r.kind == ActionKind.EMAIL) "check Gmail" else "check your calendar"}, then say what happened"
    }

    private fun isLarge(draft: ActionDraft): Boolean =
        StageScopeJson.encodeToString(ActionDraft.serializer(), draft).length > MAX_WATCH_REVIEW_CHARS

    private fun previewOnly(draft: ActionDraft): ActionDraft = when (draft) {
        is EmailDraft -> draft.copy(body = draft.body.take(TRUNCATED_PREVIEW_CHARS) + "\n…(open on your phone to read the full text)")
        is CalendarDraft -> draft.copy(description = draft.description?.take(TRUNCATED_PREVIEW_CHARS))
        else -> draft
    }
}
