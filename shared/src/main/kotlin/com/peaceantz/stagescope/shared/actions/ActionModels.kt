package com.peaceantz.stagescope.shared.actions

import com.peaceantz.stagescope.shared.show.EmailAddress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActionKind(val label: String, val needsConfirmation: Boolean, val executesOnPhoneApi: Boolean) {
    EMAIL("Email", needsConfirmation = true, executesOnPhoneApi = true),
    CALENDAR_EVENT("Calendar event", needsConfirmation = true, executesOnPhoneApi = true),
    /** There is no supported append-to-checklist API: Keep is always an honest phone handoff. */
    KEEP_ITEM("Keep item", needsConfirmation = false, executesOnPhoneApi = false),
    /** Local, reversible, explicitly requested: saved at once with a readback and undo. */
    LOG_ISSUE("Issue log", needsConfirmation = false, executesOnPhoneApi = false),
}

/**
 * The action state machine (see docs/AI_ARCHITECTURE.md). Transitions are persisted *before* any
 * external call, so a crash mid-execution can never leave "executing" invisible.
 */
@Serializable
enum class ActionState {
    DRAFT,
    AWAITING_INFORMATION,
    AWAITING_REVIEW,
    /** A prepared handoff exists on the phone ("Ready on phone"); this is NOT evidence of completion. */
    AWAITING_PHONE,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** The request may have been submitted but we cannot tell. Never retried automatically. */
    OUTCOME_UNCERTAIN;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED
}

/** How we know a COMPLETED action completed. A handoff can only ever be [USER_MARKED]. */
@Serializable
enum class CompletionEvidence { NONE, API_CONFIRMED, LOCAL_WRITE, USER_MARKED }

@Serializable
enum class EmailPurpose { PERFORMANCE_REPORT, ISSUE_HELP }

@Serializable
sealed interface ActionDraft {
    val kind: ActionKind
}

@Serializable
@SerialName("email")
data class EmailDraft(
    val purpose: EmailPurpose,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val performanceId: String? = null,
    val issueIds: List<String> = emptyList(),
    /** The Google account the email will be sent from, as shown at review. Part of the confirmation. */
    val senderAccount: String? = null,
) : ActionDraft {
    override val kind: ActionKind get() = ActionKind.EMAIL
}

@Serializable
@SerialName("calendar")
data class CalendarDraft(
    val title: String,
    /** Persisted before submission so a retry can reconcile instead of duplicating (base32hex, 5..1024). */
    val eventId: String,
    val timezoneId: String,
    /** Local date-time without offset: `yyyy-MM-ddTHH:mm`. */
    val startLocal: String,
    val endLocal: String,
    /** UTC offsets resolved for those local times (DST-aware) -- shown on the review card and hashed. */
    val startOffset: String,
    val endOffset: String,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
    val calendarId: String = "primary",
    val calendarLabel: String = "Primary calendar",
    val invitees: List<EmailAddress> = emptyList(),
    /** Any default that was applied (duration, AM/PM, DST shift) -- always shown to the person. */
    val assumptions: List<String> = emptyList(),
    val accountEmail: String? = null,
) : ActionDraft {
    override val kind: ActionKind get() = ActionKind.CALENDAR_EVENT
}

@Serializable
@SerialName("keep")
data class KeepDraft(
    val itemText: String,
    val listName: String? = null,
) : ActionDraft {
    override val kind: ActionKind get() = ActionKind.KEEP_ITEM

    /** The exact command a person can say to Gemini (which has a Keep connector) -- prepared, not sent. */
    fun geminiCommand(): String =
        if (listName.isNullOrBlank()) "Add \"$itemText\" to Keep" else "Add \"$itemText\" to my \"$listName\" list in Keep"
}

@Serializable
@SerialName("issue")
data class IssueLogDraft(
    val issueId: String,
    val summary: String,
) : ActionDraft {
    override val kind: ActionKind get() = ActionKind.LOG_ISSUE
}

@Serializable
enum class ConfirmSource { WATCH, PHONE }

/**
 * An approval that exists only because the *application UI* recorded one -- there is no tool
 * parameter, text answer or model output that can create it. It is bound to exactly this action,
 * revision, content and account: any change to those invalidates it.
 */
@Serializable
data class Confirmation(
    val actionId: String,
    val revision: Int,
    val contentHash: String,
    val accountId: String?,
    val by: ConfirmSource,
    val atEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Serializable
enum class AttemptOutcome { STARTED, SUCCEEDED, FAILED_NOT_SUBMITTED, AMBIGUOUS }

@Serializable
data class ExecutionAttempt(
    val opId: String,
    val startedAtEpochMs: Long,
    val outcome: AttemptOutcome,
    val detail: String? = null,
)

@Serializable
data class ActionError(val code: String, val message: String, val retryable: Boolean = false)

@Serializable
data class Receipt(
    val kind: ActionKind,
    /** Gmail message id / Calendar event id: a real, API-returned receipt. Absent for handoffs. */
    val externalId: String? = null,
    val link: String? = null,
    val summary: String,
    val atEpochMs: Long,
)

@Serializable
data class ActionTransition(val from: ActionState, val to: ActionState, val reason: String, val atEpochMs: Long)

@Serializable
data class ActionRecord(
    val actionId: String,
    val kind: ActionKind,
    val state: ActionState,
    /** Bumped on every content change; a confirmation only ever matches the revision it was given for. */
    val revision: Int,
    val draft: ActionDraft,
    /** SHA-256 of the canonical executable content ([ConfirmationBinding]). */
    val contentHash: String,
    val conversationId: String? = null,
    val requestId: String? = null,
    val confirmation: Confirmation? = null,
    val attempts: List<ExecutionAttempt> = emptyList(),
    val receipt: Receipt? = null,
    val error: ActionError? = null,
    /** Questions that must be answered before this can be reviewed ("AM or PM?"). */
    val missingInformation: List<String> = emptyList(),
    /** Provenance/ambiguity warnings shown at review (e.g. an equipment number nobody supplied). Not part of the hash. */
    val warnings: List<String> = emptyList(),
    val completionEvidence: CompletionEvidence = CompletionEvidence.NONE,
    /** Handoff bookkeeping: the phone-side route was opened (never "completed"). */
    val handoffOpened: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val history: List<ActionTransition> = emptyList(),
) {
    val needsConfirmation: Boolean get() = kind.needsConfirmation
}

/** Shown on both devices. [reviewable] is false when the content is too long to review honestly on a watch. */
@Serializable
data class ActionCard(
    val actionId: String,
    val kind: ActionKind,
    val state: ActionState,
    val revision: Int,
    val contentHash: String,
    val title: String,
    val summary: String,
    val draft: ActionDraft,
    val missingInformation: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val receiptSummary: String? = null,
    val errorMessage: String? = null,
    val completionEvidence: CompletionEvidence = CompletionEvidence.NONE,
    val handoffOpened: Boolean = false,
    val canConfirmOnWatch: Boolean = false,
    /** Honest one-line status: "Ready on phone", "Sent · receipt …", "Outcome unknown — check Gmail". */
    val statusLine: String,
)
