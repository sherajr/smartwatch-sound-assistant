package com.peaceantz.stagescope.shared.actions

import com.peaceantz.stagescope.shared.show.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionMachineTest {
    private val t0 = 1_000_000L

    private fun email(body: String = "Hello") = EmailDraft(
        purpose = EmailPurpose.PERFORMANCE_REPORT,
        to = listOf(EmailAddress("pm@example.com", "PM")),
        subject = "Sound report", body = body, senderAccount = "sound@example.com",
    )

    private fun record(draft: ActionDraft = email()) = ActionMachine.create("action-1", draft, t0)

    private fun ActionRecord.confirmed(opId: String = "op-1", account: String? = "acct-1", at: Long = t0 + 1) =
        (ActionMachine.apply(this, ActionEvent.Confirm(opId, revision, contentHash, account, ConfirmSource.WATCH), at) as ActionOutcome.Ok).record

    private fun ActionRecord.ok(e: ActionEvent, at: Long = t0 + 2) = (ActionMachine.apply(this, e, at) as ActionOutcome.Ok).record
    private fun ActionRecord.rejection(e: ActionEvent, at: Long = t0 + 2) = (ActionMachine.apply(this, e, at) as ActionOutcome.Rejected)

    @Test
    fun `an email draft starts awaiting review and cannot execute without a confirmation`() {
        val r = record()
        assertEquals(ActionState.AWAITING_REVIEW, r.state)
        val e = r.rejection(ActionEvent.BeginExecution("op", "acct-1"))
        assertEquals(RejectReason.NOT_CONFIRMED, e.reason)
    }

    @Test
    fun `a confirmation bound to this revision executes exactly once`() {
        val r = record().confirmed()
        val running = r.ok(ActionEvent.BeginExecution("op-1", "acct-1"))
        assertEquals(ActionState.EXECUTING, running.state)
        // A second confirm (the other device, a retransmit) cannot start a second execution.
        val dup = running.rejection(ActionEvent.Confirm("op-2", running.revision, running.contentHash, "acct-1", ConfirmSource.PHONE))
        assertEquals(RejectReason.ALREADY_IN_PROGRESS, dup.reason)
        val again = running.rejection(ActionEvent.BeginExecution("op-3", "acct-1"))
        assertEquals(RejectReason.ALREADY_IN_PROGRESS, again.reason)
    }

    @Test
    fun `a retransmitted confirm with the same op id is recognised as a duplicate`() {
        val running = record().confirmed("op-1").ok(ActionEvent.BeginExecution("op-1", "acct-1"))
        val done = running.ok(ActionEvent.ExecutionSucceeded(Receipt(ActionKind.EMAIL, "msg-9", null, "sent", t0 + 3)))
        val dup = done.rejection(ActionEvent.Confirm("op-1", done.revision, done.contentHash, "acct-1", ConfirmSource.WATCH))
        assertEquals(RejectReason.DUPLICATE_COMMAND, dup.reason)
        assertEquals(1, done.attempts.size)
    }

    @Test
    fun `editing any content invalidates the confirmation and bumps the revision`() {
        val confirmed = record().confirmed()
        assertTrue(confirmed.confirmation != null)
        val edited = confirmed.ok(ActionEvent.DraftReplaced(email(body = "Hello, shorter")))
        assertEquals(2, edited.revision)
        assertNull(edited.confirmation)
        assertEquals(ActionState.AWAITING_REVIEW, edited.state)
        assertNotEquals(confirmed.contentHash, edited.contentHash)
        assertEquals(RejectReason.NOT_CONFIRMED, edited.rejection(ActionEvent.BeginExecution("op", "acct-1")).reason)
    }

    @Test
    fun `changing recipient, subject, account, or body each changes the content hash`() {
        val base = email()
        val hashes = setOf(
            ConfirmationBinding.contentHash(base),
            ConfirmationBinding.contentHash(base.copy(to = listOf(EmailAddress("other@example.com")))),
            ConfirmationBinding.contentHash(base.copy(cc = listOf(EmailAddress("cc@example.com")))),
            ConfirmationBinding.contentHash(base.copy(bcc = listOf(EmailAddress("bcc@example.com")))),
            ConfirmationBinding.contentHash(base.copy(subject = "Different")),
            ConfirmationBinding.contentHash(base.copy(body = "Different")),
            ConfirmationBinding.contentHash(base.copy(senderAccount = "other-account@example.com")),
        )
        assertEquals(7, hashes.size)
    }

    @Test
    fun `reordering or re-casing recipients is not a content change`() {
        val a = email().copy(to = listOf(EmailAddress("A@example.com"), EmailAddress("b@example.com")))
        val b = email().copy(to = listOf(EmailAddress("b@EXAMPLE.com", "Bee"), EmailAddress("a@example.com")))
        assertEquals(ConfirmationBinding.contentHash(a), ConfirmationBinding.contentHash(b))
    }

    @Test
    fun `calendar time zone start end and calendar changes void a confirmation`() {
        val c = CalendarDraft(
            title = "Sound check", eventId = "ss0123456789abcdef", timezoneId = "America/New_York",
            startLocal = "2026-03-14T16:00", endLocal = "2026-03-14T17:00", startOffset = "-04:00", endOffset = "-04:00",
        )
        val hashes = setOf(
            ConfirmationBinding.contentHash(c),
            ConfirmationBinding.contentHash(c.copy(timezoneId = "America/Chicago")),
            ConfirmationBinding.contentHash(c.copy(startLocal = "2026-03-14T16:30")),
            ConfirmationBinding.contentHash(c.copy(endLocal = "2026-03-14T18:00")),
            ConfirmationBinding.contentHash(c.copy(calendarId = "work@example.com")),
            ConfirmationBinding.contentHash(c.copy(invitees = listOf(EmailAddress("guest@example.com")))),
            ConfirmationBinding.contentHash(c.copy(accountEmail = "me@example.com")),
        )
        assertEquals(7, hashes.size)
        // Assumption notes are informational: editing one must not void a review.
        assertEquals(ConfirmationBinding.contentHash(c), ConfirmationBinding.contentHash(c.copy(assumptions = listOf("Duration assumed 60 minutes"))))
    }

    @Test
    fun `a confirm for a stale revision or stale content is refused`() {
        val r = record()
        val edited = r.ok(ActionEvent.DraftReplaced(email("v2")))
        val staleRev = edited.rejection(ActionEvent.Confirm("op", 1, edited.contentHash, "a", ConfirmSource.WATCH))
        assertEquals(RejectReason.STALE_REVISION, staleRev.reason)
        val staleHash = edited.rejection(ActionEvent.Confirm("op", edited.revision, r.contentHash, "a", ConfirmSource.WATCH))
        assertEquals(RejectReason.CONTENT_CHANGED, staleHash.reason)
    }

    @Test
    fun `a confirmation expires and a changed account invalidates it`() {
        val c = record().confirmed(at = t0)
        val late = c.rejection(ActionEvent.BeginExecution("op", "acct-1"), at = t0 + ActionMachine.CONFIRMATION_TTL_MS + 1)
        assertEquals(RejectReason.CONFIRMATION_EXPIRED, late.reason)
        val otherAccount = c.rejection(ActionEvent.BeginExecution("op", "acct-OTHER"), at = t0 + 5)
        assertEquals(RejectReason.ACCOUNT_CHANGED, otherAccount.reason)
    }

    @Test
    fun `missing information blocks review and confirmation`() {
        val r = ActionMachine.create("a", email(), t0, missingInformation = listOf("Who should receive this?"))
        assertEquals(ActionState.AWAITING_INFORMATION, r.state)
        assertEquals(RejectReason.NOT_ALLOWED_IN_STATE, r.rejection(ActionEvent.Confirm("o", 1, r.contentHash, "a", ConfirmSource.WATCH)).reason)
        val answered = r.ok(ActionEvent.DraftReplaced(email(), emptyList()))
        assertEquals(ActionState.AWAITING_REVIEW, answered.state)
    }

    @Test
    fun `an ambiguous outcome is never retried automatically and cannot be cancelled away`() {
        val running = record().confirmed().ok(ActionEvent.BeginExecution("op-1", "acct-1"))
        val uncertain = running.ok(ActionEvent.ExecutionUncertain(ActionError("timeout", "No response after sending")))
        assertEquals(ActionState.OUTCOME_UNCERTAIN, uncertain.state)
        assertEquals(AttemptOutcome.AMBIGUOUS, uncertain.attempts.last().outcome)
        assertEquals(RejectReason.OUTCOME_UNKNOWN, uncertain.rejection(ActionEvent.Cancel).reason)
        assertEquals(RejectReason.OUTCOME_UNKNOWN, uncertain.rejection(ActionEvent.Confirm("op-9", uncertain.revision, uncertain.contentHash, "acct-1", ConfirmSource.WATCH)).reason)
        assertEquals(RejectReason.OUTCOME_UNKNOWN, uncertain.rejection(ActionEvent.BeginExecution("op-9", "acct-1")).reason)
        assertFalse(RetryPolicy.mayAutoRetry(ActionKind.EMAIL, AttemptOutcome.AMBIGUOUS))
        // The person checks Gmail and says what happened.
        val sent = uncertain.ok(ActionEvent.UncertainResolved(wasDone = true))
        assertEquals(ActionState.COMPLETED, sent.state)
        assertEquals(CompletionEvidence.USER_MARKED, sent.completionEvidence)
        val notSent = uncertain.ok(ActionEvent.UncertainResolved(wasDone = false))
        assertEquals(ActionState.AWAITING_REVIEW, notSent.state)
        assertNull(notSent.confirmation)
    }

    @Test
    fun `a definite failure needs a fresh confirmation to try again`() {
        val failed = record().confirmed().ok(ActionEvent.BeginExecution("op-1", "acct-1"))
            .ok(ActionEvent.ExecutionFailed(ActionError("auth", "Authorization expired")))
        assertEquals(ActionState.FAILED, failed.state)
        assertNull(failed.confirmation)
        val reopened = failed.ok(ActionEvent.ReopenForReview)
        assertEquals(ActionState.AWAITING_REVIEW, reopened.state)
        assertEquals(RejectReason.NOT_CONFIRMED, reopened.rejection(ActionEvent.BeginExecution("op-2", "acct-1")).reason)
    }

    @Test
    fun `cancelling an executing action is refused and its real result is reported`() {
        val running = record().confirmed().ok(ActionEvent.BeginExecution("op-1", "acct-1"))
        assertEquals(RejectReason.ALREADY_IN_PROGRESS, running.rejection(ActionEvent.Cancel).reason)
        val done = running.ok(ActionEvent.ExecutionSucceeded(Receipt(ActionKind.EMAIL, "m-1", null, "sent", t0 + 3)))
        assertEquals(CompletionEvidence.API_CONFIRMED, done.completionEvidence)
        assertEquals("m-1", done.receipt?.externalId)
    }

    @Test
    fun `keep and prefilled-composer handoffs can only ever be user-marked never verified`() {
        val keep = ActionMachine.create("k", KeepDraft("Spare mic tape", "Theatre Supplies"), t0)
        assertEquals(ActionState.AWAITING_PHONE, keep.state)
        val opened = keep.ok(ActionEvent.HandoffOpened)
        assertTrue(opened.handoffOpened)
        assertEquals(ActionState.AWAITING_PHONE, opened.state)
        val done = opened.ok(ActionEvent.UserMarkedDone)
        assertEquals(ActionState.COMPLETED, done.state)
        assertEquals(CompletionEvidence.USER_MARKED, done.completionEvidence)
        assertNull(done.receipt)
        // An email handoff is the same: "Ready on phone" is not "sent".
        val emailHandoff = record().ok(ActionEvent.HandoffPrepared)
        assertEquals(ActionState.AWAITING_PHONE, emailHandoff.state)
        assertEquals(CompletionEvidence.NONE, emailHandoff.completionEvidence)
        assertEquals(RejectReason.NOT_ALLOWED_IN_STATE, emailHandoff.rejection(ActionEvent.BeginExecution("op", null)).reason)
    }

    @Test
    fun `keep needs no confirmation and says so`() {
        val keep = ActionMachine.create("k", KeepDraft("Tape"), t0)
        assertEquals(RejectReason.NOT_CONFIRMABLE, keep.rejection(ActionEvent.Confirm("o", 1, keep.contentHash, null, ConfirmSource.WATCH)).reason)
        assertEquals(
            "Add \"Spare mic tape\" to my \"Theatre Supplies\" list in Keep",
            KeepDraft("Spare mic tape", "Theatre Supplies").geminiCommand(),
        )
    }

    @Test
    fun `issue logging completes by a local write with its own evidence kind`() {
        val log = ActionMachine.create("l", IssueLogDraft("issue-1", "Mic 12 crackled"), t0)
        assertEquals(ActionState.DRAFT, log.state)
        val done = log.ok(ActionEvent.LocalWriteCompleted("Saved: Mic 12 crackled"))
        assertEquals(ActionState.COMPLETED, done.state)
        assertEquals(CompletionEvidence.LOCAL_WRITE, done.completionEvidence)
    }

    @Test
    fun `a completed action cannot be edited or cancelled`() {
        val done = record().confirmed().ok(ActionEvent.BeginExecution("o1", "acct-1"))
            .ok(ActionEvent.ExecutionSucceeded(Receipt(ActionKind.EMAIL, "m", null, "sent", t0)))
        assertEquals(RejectReason.ALREADY_DONE, done.rejection(ActionEvent.DraftReplaced(email("late edit"))).reason)
        assertEquals(RejectReason.ALREADY_DONE, done.rejection(ActionEvent.Cancel).reason)
    }

    @Test
    fun `retry policy matches the spec's per-operation rules`() {
        assertEquals(RetryPolicy.Rule.NEVER_AUTOMATIC, RetryPolicy.forAction(ActionKind.EMAIL))
        assertEquals(RetryPolicy.Rule.SAME_IDEMPOTENCY_KEY, RetryPolicy.forAction(ActionKind.CALENDAR_EVENT))
        assertEquals(RetryPolicy.Rule.IDEMPOTENT_BY_OP_ID, RetryPolicy.forAction(ActionKind.LOG_ISSUE))
        assertTrue(RetryPolicy.mayAutoRetry(ActionKind.CALENDAR_EVENT, AttemptOutcome.AMBIGUOUS))
        assertFalse(RetryPolicy.mayAutoRetry(ActionKind.KEEP_ITEM, AttemptOutcome.AMBIGUOUS))
    }
}
