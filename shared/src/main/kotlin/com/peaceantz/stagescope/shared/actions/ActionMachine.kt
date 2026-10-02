package com.peaceantz.stagescope.shared.actions

/** Everything that can happen to an action. UI taps, AI proposals and executors all go through these. */
sealed interface ActionEvent {
    /** A new or revised draft (AI proposal, voice edit, form edit). Always invalidates any confirmation. */
    data class DraftReplaced(
        val draft: ActionDraft,
        val missingInformation: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) : ActionEvent

    /** The person approved *this* revision/content/account in the UI. Never produced by a model. */
    data class Confirm(
        val opId: String,
        val revision: Int,
        val contentHash: String,
        val accountId: String?,
        val by: ConfirmSource,
    ) : ActionEvent

    /** The executor is about to make the external call. [currentAccountId] is the account it would use. */
    data class BeginExecution(val opId: String, val currentAccountId: String?) : ActionEvent

    data class ExecutionSucceeded(val receipt: Receipt) : ActionEvent

    /** The request definitely did not reach the service (auth failure, validation, connect failure). */
    data class ExecutionFailed(val error: ActionError) : ActionEvent

    /** Timeout/5xx/reset after the request may have been sent: unknown. Never auto-retried. */
    data class ExecutionUncertain(val error: ActionError) : ActionEvent

    data object Cancel : ActionEvent

    /** A prepared phone route (prefilled composer/editor, Keep text) now exists: "Ready on phone". */
    data object HandoffPrepared : ActionEvent
    data object HandoffOpened : ActionEvent

    /** The person says it is done. Recorded as user-marked, never as verified. */
    data object UserMarkedDone : ActionEvent

    /** After an uncertain send: the person checked and tells us what happened. */
    data class UncertainResolved(val wasDone: Boolean) : ActionEvent

    /** An executed-locally action (issue log) finished writing. */
    data class LocalWriteCompleted(val summary: String) : ActionEvent

    /** Undo of a local, reversible action (a logged issue). Never valid for email or calendar. */
    data object UndoLocal : ActionEvent

    /** Bring an old/changed action back to review (re-confirmation required). */
    data object ReopenForReview : ActionEvent
}

enum class RejectReason {
    NOT_ALLOWED_IN_STATE,
    STALE_REVISION,
    CONTENT_CHANGED,
    NOT_CONFIRMED,
    CONFIRMATION_EXPIRED,
    ACCOUNT_CHANGED,
    MISSING_INFORMATION,
    ALREADY_IN_PROGRESS,
    ALREADY_DONE,
    DUPLICATE_COMMAND,
    OUTCOME_UNKNOWN,
    NOT_CONFIRMABLE,
}

sealed interface ActionOutcome {
    val record: ActionRecord

    data class Ok(override val record: ActionRecord) : ActionOutcome

    /** The event was refused; [record] is the unchanged current state (so callers can report it). */
    data class Rejected(val reason: RejectReason, val message: String, override val record: ActionRecord) : ActionOutcome
}

/**
 * Pure reducer for [ActionRecord]. It is the single place that decides whether an external action
 * may run, so the rules the spec cares about live here and are unit tested:
 * - nothing executes without a UI [Confirmation] bound to the current revision, content hash,
 *   account and a freshness window;
 * - any content change bumps the revision and drops the confirmation;
 * - handoffs (Keep, prefilled composers) can finish only as user-marked, never API-confirmed;
 * - an uncertain outcome is never retried automatically and can't be "cancelled" away;
 * - duplicate confirmations (watch + phone, retransmits) cannot start a second execution.
 */
object ActionMachine {
    const val CONFIRMATION_TTL_MS = 10 * 60_000L
    private const val MAX_HISTORY = 40

    fun create(
        actionId: String,
        draft: ActionDraft,
        nowEpochMs: Long,
        conversationId: String? = null,
        requestId: String? = null,
        missingInformation: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
    ): ActionRecord {
        val kind = draft.kind
        val state = when {
            missingInformation.isNotEmpty() -> ActionState.AWAITING_INFORMATION
            kind == ActionKind.KEEP_ITEM -> ActionState.AWAITING_PHONE
            kind == ActionKind.LOG_ISSUE -> ActionState.DRAFT
            else -> ActionState.AWAITING_REVIEW
        }
        return ActionRecord(
            actionId = actionId, kind = kind, state = state, revision = 1, draft = draft,
            contentHash = ConfirmationBinding.contentHash(draft),
            conversationId = conversationId, requestId = requestId,
            missingInformation = missingInformation, warnings = warnings,
            createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
            history = listOf(ActionTransition(ActionState.DRAFT, state, "created", nowEpochMs)),
        )
    }

    fun apply(record: ActionRecord, event: ActionEvent, nowEpochMs: Long): ActionOutcome = when (event) {
        is ActionEvent.DraftReplaced -> replaceDraft(record, event, nowEpochMs)
        is ActionEvent.Confirm -> confirm(record, event, nowEpochMs)
        is ActionEvent.BeginExecution -> begin(record, event, nowEpochMs)
        is ActionEvent.ExecutionSucceeded -> finish(record, nowEpochMs) { r ->
            r.moveTo(ActionState.COMPLETED, "executed", nowEpochMs).copy(
                receipt = event.receipt, error = null, completionEvidence = CompletionEvidence.API_CONFIRMED,
                attempts = r.attempts.closeLast(AttemptOutcome.SUCCEEDED, event.receipt.externalId),
            )
        }
        is ActionEvent.ExecutionFailed -> finish(record, nowEpochMs) { r ->
            r.moveTo(ActionState.FAILED, "failed: ${event.error.code}", nowEpochMs).copy(
                error = event.error, confirmation = null,
                attempts = r.attempts.closeLast(AttemptOutcome.FAILED_NOT_SUBMITTED, event.error.code),
            )
        }
        is ActionEvent.ExecutionUncertain -> finish(record, nowEpochMs) { r ->
            r.moveTo(ActionState.OUTCOME_UNCERTAIN, "uncertain: ${event.error.code}", nowEpochMs).copy(
                error = event.error, confirmation = null,
                attempts = r.attempts.closeLast(AttemptOutcome.AMBIGUOUS, event.error.code),
            )
        }
        ActionEvent.Cancel -> cancel(record, nowEpochMs)
        ActionEvent.HandoffPrepared -> handoffPrepared(record, nowEpochMs)
        ActionEvent.HandoffOpened -> when (record.state) {
            ActionState.AWAITING_PHONE -> ok(record.copy(handoffOpened = true, updatedAtEpochMs = nowEpochMs))
            else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "Nothing is waiting on the phone.")
        }
        ActionEvent.UserMarkedDone -> when (record.state) {
            ActionState.AWAITING_PHONE, ActionState.OUTCOME_UNCERTAIN -> ok(
                record.moveTo(ActionState.COMPLETED, "marked done by user", nowEpochMs)
                    .copy(completionEvidence = CompletionEvidence.USER_MARKED, error = null),
            )
            ActionState.COMPLETED -> rejected(record, RejectReason.ALREADY_DONE, "Already marked done.")
            else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "This can't be marked done yet.")
        }
        is ActionEvent.UncertainResolved -> when (record.state) {
            ActionState.OUTCOME_UNCERTAIN ->
                if (event.wasDone) {
                    ok(record.moveTo(ActionState.COMPLETED, "user verified it was done", nowEpochMs)
                        .copy(completionEvidence = CompletionEvidence.USER_MARKED, error = null))
                } else {
                    ok(record.moveTo(ActionState.AWAITING_REVIEW, "user says it was not done", nowEpochMs)
                        .copy(confirmation = null, error = null))
                }
            else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "There is no uncertain outcome to resolve.")
        }
        is ActionEvent.LocalWriteCompleted -> when {
            record.kind != ActionKind.LOG_ISSUE -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "Only local actions finish by a local write.")
            record.state.isTerminal -> rejected(record, RejectReason.ALREADY_DONE, "Already finished.")
            else -> ok(
                record.moveTo(ActionState.COMPLETED, "saved locally", nowEpochMs).copy(
                    completionEvidence = CompletionEvidence.LOCAL_WRITE,
                    receipt = Receipt(ActionKind.LOG_ISSUE, null, null, event.summary, nowEpochMs),
                ),
            )
        }
        ActionEvent.UndoLocal -> when {
            record.kind != ActionKind.LOG_ISSUE -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "Only a logged issue can be undone; a sent email or created event can't.")
            record.state == ActionState.COMPLETED -> ok(record.moveTo(ActionState.CANCELLED, "undone by user", nowEpochMs).copy(receipt = null, completionEvidence = CompletionEvidence.NONE))
            record.state == ActionState.CANCELLED -> ok(record)
            else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "Nothing to undo.")
        }
        ActionEvent.ReopenForReview -> when (record.state) {
            ActionState.FAILED, ActionState.AWAITING_PHONE ->
                if (record.kind.needsConfirmation) ok(record.moveTo(ActionState.AWAITING_REVIEW, "reopened for review", nowEpochMs).copy(confirmation = null, error = null))
                else rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "This action has nothing to review.")
            else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "This can't be reopened from here.")
        }
    }

    private fun replaceDraft(record: ActionRecord, e: ActionEvent.DraftReplaced, now: Long): ActionOutcome {
        if (e.draft.kind != record.kind) return rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "A draft can't change what kind of action it is.")
        return when (record.state) {
            ActionState.EXECUTING -> rejected(record, RejectReason.ALREADY_IN_PROGRESS, "It is being sent right now; it can't be edited.")
            ActionState.OUTCOME_UNCERTAIN -> rejected(record, RejectReason.OUTCOME_UNKNOWN, "Resolve the unknown outcome before editing.")
            ActionState.COMPLETED, ActionState.CANCELLED -> rejected(record, RejectReason.ALREADY_DONE, "It is already finished.")
            else -> {
                val next = when {
                    e.missingInformation.isNotEmpty() -> ActionState.AWAITING_INFORMATION
                    record.kind == ActionKind.KEEP_ITEM -> ActionState.AWAITING_PHONE
                    record.kind == ActionKind.LOG_ISSUE -> ActionState.DRAFT
                    else -> ActionState.AWAITING_REVIEW
                }
                val hash = ConfirmationBinding.contentHash(e.draft)
                val changed = hash != record.contentHash
                ok(
                    record.moveTo(next, if (changed) "draft revised" else "draft re-saved", now).copy(
                        draft = e.draft, contentHash = hash,
                        revision = if (changed) record.revision + 1 else record.revision,
                        // Any content change voids the approval -- even a one-word edit.
                        confirmation = if (changed) null else record.confirmation,
                        missingInformation = e.missingInformation, warnings = e.warnings, error = null,
                        handoffOpened = if (changed) false else record.handoffOpened,
                    ),
                )
            }
        }
    }

    private fun confirm(record: ActionRecord, e: ActionEvent.Confirm, now: Long): ActionOutcome {
        if (!record.kind.needsConfirmation) return rejected(record, RejectReason.NOT_CONFIRMABLE, "This action doesn't need a confirmation.")
        if (record.attempts.any { it.opId == e.opId }) return rejected(record, RejectReason.DUPLICATE_COMMAND, "That confirmation was already received.")
        when (record.state) {
            ActionState.EXECUTING -> return rejected(record, RejectReason.ALREADY_IN_PROGRESS, "It is already being sent.")
            ActionState.COMPLETED -> return rejected(record, RejectReason.ALREADY_DONE, "It was already completed.")
            ActionState.OUTCOME_UNCERTAIN -> return rejected(record, RejectReason.OUTCOME_UNKNOWN, "It may already have gone through. Check, then say what happened.")
            ActionState.AWAITING_REVIEW -> Unit
            else -> return rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "It isn't ready to confirm.")
        }
        if (record.missingInformation.isNotEmpty()) return rejected(record, RejectReason.MISSING_INFORMATION, "More information is needed first.")
        if (e.revision != record.revision) return rejected(record, RejectReason.STALE_REVISION, "The draft changed after you reviewed it. Review the new version.")
        if (e.contentHash != record.contentHash) return rejected(record, RejectReason.CONTENT_CHANGED, "The content changed after you reviewed it. Review the new version.")
        val confirmation = Confirmation(
            actionId = record.actionId, revision = record.revision, contentHash = record.contentHash,
            accountId = e.accountId, by = e.by, atEpochMs = now, expiresAtEpochMs = now + CONFIRMATION_TTL_MS,
        )
        return ok(record.copy(confirmation = confirmation, updatedAtEpochMs = now))
    }

    private fun begin(record: ActionRecord, e: ActionEvent.BeginExecution, now: Long): ActionOutcome {
        when (record.state) {
            ActionState.EXECUTING -> return rejected(record, RejectReason.ALREADY_IN_PROGRESS, "It is already being sent.")
            ActionState.COMPLETED -> return rejected(record, RejectReason.ALREADY_DONE, "It was already completed.")
            ActionState.OUTCOME_UNCERTAIN -> return rejected(record, RejectReason.OUTCOME_UNKNOWN, "It may already have gone through; it will not be sent again automatically.")
            ActionState.AWAITING_REVIEW -> Unit
            else -> return rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "It isn't ready to run.")
        }
        val c = record.confirmation ?: return rejected(record, RejectReason.NOT_CONFIRMED, "It hasn't been confirmed.")
        if (c.revision != record.revision || c.contentHash != record.contentHash || c.actionId != record.actionId) {
            return rejected(record, RejectReason.CONTENT_CHANGED, "It changed after it was confirmed. Review and confirm again.")
        }
        if (now > c.expiresAtEpochMs) return rejected(record, RejectReason.CONFIRMATION_EXPIRED, "The confirmation expired. Review and confirm again.")
        if (c.accountId != e.currentAccountId) return rejected(record, RejectReason.ACCOUNT_CHANGED, "The Google account changed since you confirmed. Review and confirm again.")
        return ok(
            record.moveTo(ActionState.EXECUTING, "executing", now).copy(
                attempts = record.attempts + ExecutionAttempt(e.opId, now, AttemptOutcome.STARTED),
                error = null,
            ),
        )
    }

    private inline fun finish(record: ActionRecord, now: Long, transform: (ActionRecord) -> ActionRecord): ActionOutcome =
        if (record.state != ActionState.EXECUTING) {
            rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "Nothing is executing.")
        } else {
            ok(transform(record).let { it.copy(updatedAtEpochMs = now) })
        }

    private fun cancel(record: ActionRecord, now: Long): ActionOutcome = when (record.state) {
        ActionState.EXECUTING -> rejected(record, RejectReason.ALREADY_IN_PROGRESS, "It is being sent right now and can't be undone. Its final result will be reported.")
        ActionState.OUTCOME_UNCERTAIN -> rejected(record, RejectReason.OUTCOME_UNKNOWN, "It may already have been sent, so cancelling can't undo it. Check, then say what happened.")
        ActionState.COMPLETED -> rejected(record, RejectReason.ALREADY_DONE, "It already completed; cancelling can't undo it.")
        ActionState.CANCELLED -> ok(record)
        else -> ok(record.moveTo(ActionState.CANCELLED, "cancelled", now).copy(confirmation = null))
    }

    private fun handoffPrepared(record: ActionRecord, now: Long): ActionOutcome = when (record.state) {
        ActionState.AWAITING_REVIEW, ActionState.FAILED, ActionState.DRAFT, ActionState.AWAITING_PHONE ->
            ok(record.moveTo(ActionState.AWAITING_PHONE, "ready on phone", now).copy(confirmation = null, handoffOpened = false))
        else -> rejected(record, RejectReason.NOT_ALLOWED_IN_STATE, "A phone handoff can't be prepared from here.")
    }

    private fun ActionRecord.moveTo(to: ActionState, reason: String, now: Long): ActionRecord =
        if (to == state) copy(updatedAtEpochMs = now)
        else copy(state = to, updatedAtEpochMs = now, history = (history + ActionTransition(state, to, reason, now)).takeLast(MAX_HISTORY))

    private fun List<ExecutionAttempt>.closeLast(outcome: AttemptOutcome, detail: String?): List<ExecutionAttempt> =
        if (isEmpty()) this else dropLast(1) + last().copy(outcome = outcome, detail = detail)

    private fun ok(record: ActionRecord): ActionOutcome = ActionOutcome.Ok(record)
    private fun rejected(record: ActionRecord, reason: RejectReason, message: String): ActionOutcome =
        ActionOutcome.Rejected(reason, message, record)
}

/** Which operations may be retried automatically, and how. Mirrors docs/AI_ARCHITECTURE.md. */
object RetryPolicy {
    enum class Rule { NEVER_AUTOMATIC, SAME_IDEMPOTENCY_KEY, BOUNDED_BACKOFF_BEFORE_OUTPUT, IDEMPOTENT_BY_OP_ID, USER_ONLY }

    /** Provider (read-only) calls: retry only before any output was produced, so nothing is double-charged mid-stream. */
    val AI_REQUEST = Rule.BOUNDED_BACKOFF_BEFORE_OUTPUT
    const val AI_MAX_RETRIES = 2

    fun forAction(kind: ActionKind): Rule = when (kind) {
        ActionKind.EMAIL -> Rule.NEVER_AUTOMATIC
        ActionKind.CALENDAR_EVENT -> Rule.SAME_IDEMPOTENCY_KEY
        ActionKind.LOG_ISSUE -> Rule.IDEMPOTENT_BY_OP_ID
        ActionKind.KEEP_ITEM -> Rule.USER_ONLY
    }

    /** May the executor re-submit after [lastAttempt]? Email: never. Calendar: only with the same event id. */
    fun mayAutoRetry(kind: ActionKind, lastAttempt: AttemptOutcome): Boolean = when (forAction(kind)) {
        Rule.NEVER_AUTOMATIC, Rule.USER_ONLY, Rule.BOUNDED_BACKOFF_BEFORE_OUTPUT -> false
        Rule.SAME_IDEMPOTENCY_KEY -> lastAttempt == AttemptOutcome.AMBIGUOUS || lastAttempt == AttemptOutcome.FAILED_NOT_SUBMITTED
        Rule.IDEMPOTENT_BY_OP_ID -> true
    }
}
