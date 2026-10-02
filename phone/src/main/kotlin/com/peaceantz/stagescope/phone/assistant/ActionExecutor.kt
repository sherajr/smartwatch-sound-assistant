package com.peaceantz.stagescope.phone.assistant

import com.peaceantz.stagescope.phone.data.ConfirmResult
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.google.CalendarClient
import com.peaceantz.stagescope.phone.google.CalendarCreateResult
import com.peaceantz.stagescope.phone.google.GmailClient
import com.peaceantz.stagescope.phone.google.GmailSendResult
import com.peaceantz.stagescope.phone.google.GoogleAuthorizer
import com.peaceantz.stagescope.phone.google.GoogleScopes
import com.peaceantz.stagescope.phone.google.MimeBuilder
import com.peaceantz.stagescope.phone.google.TokenResult
import com.peaceantz.stagescope.shared.actions.ActionError
import com.peaceantz.stagescope.shared.actions.ActionEvent
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionOutcome
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.Receipt
import com.peaceantz.stagescope.shared.actions.RejectReason
import com.peaceantz.stagescope.shared.show.EmailValidator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a confirm attempt did, in words a UI (watch or phone) can show as-is. */
sealed interface ConfirmOutcome {
    val record: ActionRecord?
    val message: String

    /** The action now has its final or in-flight state; [record] is the persisted truth. */
    data class Ran(override val record: ActionRecord, override val message: String) : ConfirmOutcome

    /** Refused with a reason; nothing external happened. */
    data class Refused(val reason: RejectReason?, override val message: String, override val record: ActionRecord?) : ConfirmOutcome

    /** Can't run until the person finishes a setup step (connect Gmail/Calendar). */
    data class NeedsSetup(override val message: String, override val record: ActionRecord?) : ConfirmOutcome
}

/**
 * Runs confirmed actions against Gmail and Calendar. Order of operations is the safety property:
 *
 *  1. refuse in dev mode (the fake provider may never execute an external action);
 *  2. `confirmAndBegin` -- ONE locked, persisted step that validates the confirmation against the
 *     exact revision, content hash and Google account, and moves the action to EXECUTING;
 *  3. only then obtain a token and call the API, re-checking the token's account identity;
 *  4. persist the real outcome: Sent/Created with the API-returned id, Failed (definitely not
 *     submitted), or Uncertain (possibly submitted -- never retried automatically).
 *
 * A watch tap and a phone tap, or a retransmitted message, all enter at step 2 and exactly one wins.
 */
class ActionExecutor(
    private val data: PhoneData,
    private val gmail: GmailClient,
    private val calendar: CalendarClient,
    private val auth: GoogleAuthorizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: suspend (ActionRecord) -> Unit = {},
) {

    suspend fun confirm(actionId: String, opId: String, revision: Int, contentHash: String, by: ConfirmSource): ConfirmOutcome {
        val settings = data.settings.value
        val record = data.actions.get(actionId) ?: return ConfirmOutcome.Refused(null, "That action no longer exists.", null)
        if (settings.devMode) {
            return ConfirmOutcome.Refused(null, "Development mode is on: the test provider can never send email or create events. Turn it off in Setup to use the real assistant.", record)
        }
        if (!record.kind.executesOnPhoneApi) return ConfirmOutcome.Refused(RejectReason.NOT_CONFIRMABLE, "This action doesn't need a confirmation.", record)
        val granted = when (record.kind) {
            ActionKind.EMAIL -> settings.gmailGranted
            ActionKind.CALENDAR_EVENT -> settings.calendarGranted
            else -> false
        }
        val accountId = settings.googleAccountId
        if (!granted || accountId == null) {
            return ConfirmOutcome.NeedsSetup(
                "${if (record.kind == ActionKind.EMAIL) "Gmail" else "Calendar"} isn't connected. Connect it in StageScope's Setup on your phone, or open a prefilled ${if (record.kind == ActionKind.EMAIL) "email" else "event"} on the phone.",
                record,
            )
        }

        val began = data.actions.confirmAndBegin(actionId, opId, revision, contentHash, accountId, by, clock())
        val executing = when (began) {
            ConfirmResult.NotFound -> return ConfirmOutcome.Refused(null, "That action no longer exists.", null)
            is ConfirmResult.Refused -> return ConfirmOutcome.Refused(began.reason, began.message, began.record)
            is ConfirmResult.Began -> began.record
        }
        onChanged(executing)
        // From here the transition is on disk; whatever happens next is recorded even if we are cancelled.
        return withContext(NonCancellable) { execute(executing, opId, accountId) }
    }

    private suspend fun execute(record: ActionRecord, opId: String, confirmedAccountId: String): ConfirmOutcome {
        val scopes = if (record.kind == ActionKind.EMAIL) GoogleScopes.GMAIL_FEATURE else GoogleScopes.CALENDAR_FEATURE
        val token = when (val t = auth.accessToken(scopes)) {
            is TokenResult.Token -> t
            is TokenResult.NeedsConsent -> return finish(record, ActionEvent.ExecutionFailed(ActionError("auth", "Google access was revoked or expired. Reconnect ${if (record.kind == ActionKind.EMAIL) "Gmail" else "Calendar"} in Setup, then confirm again.")))
            is TokenResult.Unavailable -> return finish(record, ActionEvent.ExecutionFailed(ActionError("auth_unavailable", t.reason)))
        }
        // The token must belong to the very account the person confirmed.
        if (token.identity != null && token.identity.accountId != confirmedAccountId) {
            return finish(record, ActionEvent.ExecutionFailed(ActionError("account_changed", "Google is signed in as a different account than the one you confirmed. Nothing was sent. Review and confirm again.")))
        }
        return try {
            when (val draft = record.draft) {
                is EmailDraft -> runEmail(record, draft, token.accessToken, token.identity?.email)
                is CalendarDraft -> runCalendar(record, draft, token.accessToken)
                else -> finish(record, ActionEvent.ExecutionFailed(ActionError("unsupported", "This kind of action can't be run here.")))
            }
        } catch (e: MimeBuilder.InvalidMessage) {
            finish(record, ActionEvent.ExecutionFailed(ActionError("invalid_message", e.message ?: "The email isn't valid.")))
        }
    }

    private suspend fun runEmail(record: ActionRecord, draft: EmailDraft, accessToken: String, tokenEmail: String?): ConfirmOutcome {
        val from = (draft.senderAccount ?: tokenEmail)?.takeIf { EmailValidator.isValid(it) }
            ?: return finish(record, ActionEvent.ExecutionFailed(ActionError("no_sender", "The sending account is unknown. Reconnect Gmail in Setup.")))
        if (tokenEmail != null && !tokenEmail.equals(from, ignoreCase = true)) {
            return finish(record, ActionEvent.ExecutionFailed(ActionError("account_changed", "The Gmail account changed since you reviewed this email. Nothing was sent. Review and confirm again.")))
        }
        val message = MimeBuilder.build(from, draft.to, draft.cc, draft.bcc, draft.subject, draft.body)
        return when (val r = gmail.send(accessToken, MimeBuilder.toGmailRaw(message))) {
            is GmailSendResult.Sent -> finish(
                record,
                ActionEvent.ExecutionSucceeded(Receipt(ActionKind.EMAIL, r.messageId, r.threadId?.let { "https://mail.google.com/mail/u/0/#sent/$it" }, "Sent · Gmail message ${r.messageId}", clock())),
            )
            is GmailSendResult.NotSubmitted -> finish(record, ActionEvent.ExecutionFailed(ActionError(r.code, r.message, retryable = r.code == "rate_limit" || r.code == "network")))
            is GmailSendResult.Uncertain -> finish(record, ActionEvent.ExecutionUncertain(ActionError("uncertain", r.message)))
        }
    }

    private suspend fun runCalendar(record: ActionRecord, draft: CalendarDraft, accessToken: String): ConfirmOutcome =
        when (val r = calendar.create(accessToken, draft, record.actionId)) {
            is CalendarCreateResult.Created -> finish(
                record,
                ActionEvent.ExecutionSucceeded(Receipt(ActionKind.CALENDAR_EVENT, r.eventId, r.htmlLink, "Added · ${draft.title}${if (r.reconciled) " (confirmed with Calendar)" else ""}", clock())),
            )
            is CalendarCreateResult.NotCreated -> finish(record, ActionEvent.ExecutionFailed(ActionError(r.code, r.message, retryable = r.code == "rate_limit")))
            is CalendarCreateResult.Uncertain -> finish(record, ActionEvent.ExecutionUncertain(ActionError("uncertain", r.message)))
        }

    private suspend fun finish(record: ActionRecord, event: ActionEvent): ConfirmOutcome {
        val outcome = data.actions.apply(record.actionId, event, clock())
        val updated = (outcome as? ActionOutcome.Ok)?.record ?: data.actions.get(record.actionId) ?: record
        onChanged(updated)
        return ConfirmOutcome.Ran(updated, com.peaceantz.stagescope.shared.actions.ActionPresentation.statusLine(updated))
    }

    // ------------------------------------------------------------------- other person-driven events

    suspend fun cancel(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.Cancel)
    suspend fun markDone(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.UserMarkedDone)
    suspend fun handoffPrepared(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.HandoffPrepared)
    suspend fun handoffOpened(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.HandoffOpened)
    suspend fun reopen(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.ReopenForReview)
    suspend fun undoLocal(actionId: String): ConfirmOutcome = simple(actionId, ActionEvent.UndoLocal)
    suspend fun resolveUncertain(actionId: String, wasDone: Boolean): ConfirmOutcome = simple(actionId, ActionEvent.UncertainResolved(wasDone))

    /** The person edits the draft directly (phone form) -- same state machine path as an AI revision. */
    suspend fun replaceDraft(actionId: String, draft: com.peaceantz.stagescope.shared.actions.ActionDraft, missing: List<String> = emptyList(), warnings: List<String> = emptyList()): ConfirmOutcome =
        simple(actionId, ActionEvent.DraftReplaced(draft, missing, warnings))

    private suspend fun simple(actionId: String, event: ActionEvent): ConfirmOutcome {
        val outcome = data.actions.apply(actionId, event, clock()) ?: return ConfirmOutcome.Refused(null, "That action no longer exists.", null)
        val record = outcome.record
        if (outcome is ActionOutcome.Ok) onChanged(record)
        return when (outcome) {
            is ActionOutcome.Ok -> ConfirmOutcome.Ran(record, com.peaceantz.stagescope.shared.actions.ActionPresentation.statusLine(record))
            is ActionOutcome.Rejected -> ConfirmOutcome.Refused(outcome.reason, outcome.message, record)
        }
    }
}
