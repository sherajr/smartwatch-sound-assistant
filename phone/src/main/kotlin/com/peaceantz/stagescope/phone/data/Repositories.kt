package com.peaceantz.stagescope.phone.data

import com.peaceantz.stagescope.shared.actions.ActionEvent
import com.peaceantz.stagescope.shared.actions.ActionMachine
import com.peaceantz.stagescope.shared.actions.ActionOutcome
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.actions.RejectReason
import com.peaceantz.stagescope.shared.assistant.ChatTurn
import com.peaceantz.stagescope.shared.assistant.Conversation
import com.peaceantz.stagescope.shared.assistant.ConversationWindow
import com.peaceantz.stagescope.shared.assistant.ConversationSummary
import com.peaceantz.stagescope.shared.assistant.MeasurementRef
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnError
import com.peaceantz.stagescope.shared.issues.IssueLedgerState
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.show.ShowLibrary
import com.peaceantz.stagescope.shared.show.ShowViews
import com.peaceantz.stagescope.shared.show.WatchShowView
import com.peaceantz.stagescope.shared.store.PersistentState
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Everything the phone keeps, as versioned JSON files with serial execution and atomic writes
 * ([PersistentState]). The phone owns the canonical ledger for conversations, actions and
 * requests; the watch keeps only caches and its own offline entries.
 */
class PhoneData(dir: File) {
    val conversations = ConversationRepository(dir)
    val actions = ActionRepository(dir)
    val inbox = RequestInbox(dir)
    val shows = ShowRepository(dir)
    val issues = PhoneIssueRepository(dir)
    val settings = PhoneSettingsRepository(dir)
    val usage = UsageRepository(dir)
    val measurements = MeasurementArchive(dir)
}

// ------------------------------------------------------------------------------------ conversations

@Serializable
data class ConversationsState(
    val conversations: Map<String, Conversation> = emptyMap(),
    /** Per-conversation revision for the watch's durable ThreadView (monotonic, not a clock). */
    val revisions: Map<String, Long> = emptyMap(),
)

class ConversationRepository(dir: File) {
    private val store = PersistentState(File(dir, "conversations.json"), ConversationsState.serializer(), 1, { ConversationsState() })
    val state get() = store.state

    fun get(id: String): Conversation? = store.value.conversations[id]
    fun revision(id: String): Long = store.value.revisions[id] ?: 0L
    fun recent(limit: Int = 30): List<Conversation> = store.value.conversations.values.sortedByDescending { it.updatedAtEpochMs }.take(limit)

    suspend fun createIfMissing(id: String, providerId: ProviderId, modelId: String, title: String, performanceId: String?, nowEpochMs: Long): Conversation =
        store.updateWithResult { s ->
            val existing = s.conversations[id]
            if (existing != null) return@updateWithResult s to existing
            val c = Conversation(id, title.take(60).ifBlank { "New conversation" }, nowEpochMs, nowEpochMs, providerId = providerId, modelId = modelId, performanceId = performanceId)
            prune(s.copy(conversations = s.conversations + (id to c), revisions = s.revisions + (id to 1L))) to c
        }

    /** Applies [transform] and bumps the revision (watch views refresh on revision change). */
    suspend fun update(id: String, nowEpochMs: Long, transform: (Conversation) -> Conversation): Conversation? =
        store.updateWithResult { s ->
            val c = s.conversations[id] ?: return@updateWithResult s to null
            val next = bound(transform(c).copy(updatedAtEpochMs = nowEpochMs))
            s.copy(conversations = s.conversations + (id to next), revisions = s.revisions + (id to ((s.revisions[id] ?: 0L) + 1L))) to next
        }

    suspend fun appendTurn(id: String, turn: ChatTurn, nowEpochMs: Long): Conversation? =
        update(id, nowEpochMs) { c -> c.copy(turns = c.turns + turn, nextSeq = maxOf(c.nextSeq, turn.seq + 1)) }

    suspend fun addMeasurementRef(id: String, ref: MeasurementRef, nowEpochMs: Long) =
        update(id, nowEpochMs) { c -> if (c.measurements.any { it.snapshotId == ref.snapshotId }) c else c.copy(measurements = (c.measurements + ref).takeLast(12)) }

    suspend fun delete(id: String) = store.update { s -> s.copy(conversations = s.conversations - id, revisions = s.revisions - id) }

    /** Old turns collapse into the deterministic summary instead of growing without bound. */
    private fun bound(c: Conversation): Conversation {
        if (c.turns.size <= MAX_TURNS) return c
        val dropped = c.turns.take(c.turns.size - MAX_TURNS)
        val summary = ConversationWindow.summarize(c.summary?.text, dropped)
        return c.copy(turns = c.turns.takeLast(MAX_TURNS), summary = ConversationSummary(summary, dropped.last().seq))
    }

    private fun prune(s: ConversationsState): ConversationsState {
        if (s.conversations.size <= MAX_CONVERSATIONS) return s
        val keep = s.conversations.values.sortedByDescending { it.updatedAtEpochMs }.take(MAX_CONVERSATIONS).map { it.id }.toSet()
        return s.copy(conversations = s.conversations.filterKeys { it in keep }, revisions = s.revisions.filterKeys { it in keep })
    }

    companion object {
        const val MAX_TURNS = 120
        const val MAX_CONVERSATIONS = 40
    }
}

// ----------------------------------------------------------------------------------------- actions

@Serializable
data class ActionsState(val actions: Map<String, ActionRecord> = emptyMap())

/** Result of the atomic confirm-then-begin: exactly one caller gets [Began]. */
sealed interface ConfirmResult {
    data class Began(val record: ActionRecord) : ConfirmResult
    data class Refused(val reason: RejectReason, val message: String, val record: ActionRecord) : ConfirmResult
    data object NotFound : ConfirmResult
}

class ActionRepository(dir: File) {
    private val store = PersistentState(File(dir, "actions.json"), ActionsState.serializer(), 1, { ActionsState() })
    val state get() = store.state

    fun get(id: String): ActionRecord? = store.value.actions[id]
    fun forConversation(conversationId: String): List<ActionRecord> =
        store.value.actions.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAtEpochMs }
    fun all(): List<ActionRecord> = store.value.actions.values.sortedByDescending { it.updatedAtEpochMs }

    suspend fun put(record: ActionRecord) = store.update { s -> s.copy(actions = prune(s.actions + (record.actionId to record))) }

    /** Runs one event through the state machine under the ledger lock and persists the result. */
    suspend fun apply(actionId: String, event: ActionEvent, nowEpochMs: Long): ActionOutcome? =
        store.updateWithResult { s ->
            val rec = s.actions[actionId] ?: return@updateWithResult s to null
            val outcome = ActionMachine.apply(rec, event, nowEpochMs)
            val next = if (outcome is ActionOutcome.Ok) s.copy(actions = s.actions + (actionId to outcome.record)) else s
            next to outcome
        }

    /**
     * The execution ledger's critical section. A watch tap and a phone tap (or a retransmitted
     * message) both arrive here; because Confirm and BeginExecution run in ONE locked, persisted
     * step, exactly one of them moves the action to EXECUTING and every other caller is refused with
     * "already in progress". The state is on disk *before* the caller touches Gmail/Calendar.
     */
    suspend fun confirmAndBegin(
        actionId: String,
        opId: String,
        revision: Int,
        contentHash: String,
        accountId: String?,
        by: ConfirmSource,
        nowEpochMs: Long,
    ): ConfirmResult = store.updateWithResult { s ->
        val rec = s.actions[actionId] ?: return@updateWithResult s to ConfirmResult.NotFound
        val confirmed = ActionMachine.apply(rec, ActionEvent.Confirm(opId, revision, contentHash, accountId, by), nowEpochMs)
        if (confirmed is ActionOutcome.Rejected) return@updateWithResult s to ConfirmResult.Refused(confirmed.reason, confirmed.message, rec)
        val began = ActionMachine.apply(confirmed.record, ActionEvent.BeginExecution(opId, accountId), nowEpochMs)
        when (began) {
            is ActionOutcome.Rejected -> s to ConfirmResult.Refused(began.reason, began.message, rec)
            is ActionOutcome.Ok -> s.copy(actions = s.actions + (actionId to began.record)) to ConfirmResult.Began(began.record)
        }
    }

    private fun prune(m: Map<String, ActionRecord>): Map<String, ActionRecord> {
        if (m.size <= MAX_ACTIONS) return m
        // Never drop something that is still live (executing, uncertain, awaiting a person).
        val removable = m.values.filter { it.state.isTerminal }.sortedBy { it.updatedAtEpochMs }
        val drop = removable.take(m.size - MAX_ACTIONS).map { it.actionId }.toSet()
        return m - drop
    }

    companion object {
        const val MAX_ACTIONS = 300
    }
}

// ------------------------------------------------------------------------------------------ inbox

@Serializable
enum class InboxState { RECEIVED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

@Serializable
data class InboxEntry(
    val requestId: String,
    val request: AssistantRequest,
    val state: InboxState,
    val source: String,
    val receivedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    val error: TurnError? = null,
    val revision: Long? = null,
)

@Serializable
data class InboxFile(val entries: Map<String, InboxEntry> = emptyMap())

sealed interface AcceptResult {
    data object New : AcceptResult
    data class Duplicate(val existing: InboxEntry) : AcceptResult
}

class RequestInbox(dir: File) {
    private val store = PersistentState(File(dir, "inbox.json"), InboxFile.serializer(), 1, { InboxFile() })
    val state get() = store.state

    fun get(id: String): InboxEntry? = store.value.entries[id]

    /** Idempotent by request id: a retransmit is recognised, never run twice. */
    suspend fun accept(request: AssistantRequest, source: String, nowEpochMs: Long): AcceptResult =
        store.updateWithResult { s ->
            val existing = s.entries[request.requestId]
            if (existing != null) return@updateWithResult s to AcceptResult.Duplicate(existing)
            val entry = InboxEntry(request.requestId, request, InboxState.RECEIVED, source, nowEpochMs)
            prune(s.copy(entries = s.entries + (request.requestId to entry))) to AcceptResult.New
        }

    /**
     * Claims a request for execution. Returns false when it is not claimable -- already finished,
     * cancelled, or *already RUNNING*, which means a previous worker process died mid-request: that
     * request is marked INTERRUPTED instead of being re-run, so a restart can never silently repeat a
     * model call (and its charge) or a tool's side effect.
     */
    suspend fun claim(id: String, nowEpochMs: Long): ClaimResult = store.updateWithResult { s ->
        val e = s.entries[id] ?: return@updateWithResult s to ClaimResult.Unknown
        when (e.state) {
            InboxState.RECEIVED -> s.copy(entries = s.entries + (id to e.copy(state = InboxState.RUNNING, startedAtEpochMs = nowEpochMs))) to ClaimResult.Claimed(e.request)
            InboxState.RUNNING -> {
                val interrupted = e.copy(state = InboxState.INTERRUPTED, finishedAtEpochMs = nowEpochMs)
                s.copy(entries = s.entries + (id to interrupted)) to ClaimResult.Interrupted(e.request)
            }
            else -> s to ClaimResult.AlreadyFinished(e.state)
        }
    }

    suspend fun finish(id: String, state: InboxState, nowEpochMs: Long, error: TurnError? = null, revision: Long? = null) =
        store.update { s ->
            val e = s.entries[id] ?: return@update s
            s.copy(entries = s.entries + (id to e.copy(state = state, finishedAtEpochMs = nowEpochMs, error = error, revision = revision ?: e.revision)))
        }

    /** Cancels a request that has not finished. Returns the state it was in. */
    suspend fun cancel(id: String, nowEpochMs: Long): InboxState? = store.updateWithResult { s ->
        val e = s.entries[id] ?: return@updateWithResult s to null
        if (e.state == InboxState.RECEIVED || e.state == InboxState.RUNNING) {
            s.copy(entries = s.entries + (id to e.copy(state = InboxState.CANCELLED, finishedAtEpochMs = nowEpochMs))) to e.state
        } else {
            s to e.state
        }
    }

    fun isCancelled(id: String): Boolean = store.value.entries[id]?.state == InboxState.CANCELLED

    private fun prune(s: InboxFile): InboxFile {
        if (s.entries.size <= MAX_ENTRIES) return s
        val drop = s.entries.values.filter { it.state != InboxState.RECEIVED && it.state != InboxState.RUNNING }
            .sortedBy { it.receivedAtEpochMs }.take(s.entries.size - MAX_ENTRIES).map { it.requestId }.toSet()
        return s.copy(entries = s.entries - drop)
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}

sealed interface ClaimResult {
    data class Claimed(val request: AssistantRequest) : ClaimResult
    data class Interrupted(val request: AssistantRequest) : ClaimResult
    data class AlreadyFinished(val state: InboxState) : ClaimResult
    data object Unknown : ClaimResult
}

// ------------------------------------------------------------------------------------- shows/issues

@Serializable
data class ShowState(val library: ShowLibrary = ShowLibrary(), val revision: Long = 0)

class ShowRepository(dir: File) {
    private val store = PersistentState(File(dir, "shows.json"), ShowState.serializer(), 1, { ShowState() })
    val state get() = store.state
    val library: ShowLibrary get() = store.value.library

    suspend fun update(transform: (ShowLibrary) -> ShowLibrary) =
        store.update { s -> val next = transform(s.library); if (next == s.library) s else ShowState(next, s.revision + 1) }

    fun watchView(): WatchShowView = ShowViews.toWatchView(store.value.library, store.value.revision)
}

class PhoneIssueRepository(dir: File) {
    private val store = PersistentState(
        File(dir, "issues.json"), IssueLedgerState.serializer(), 1,
        { IssueLedgerState(replicaId = "p-" + UUID.randomUUID().toString().take(8)) },
    )
    val state get() = store.state
    val replicaId: String get() = store.value.replicaId

    suspend fun update(transform: (IssueLedgerState) -> IssueLedgerState) = store.update(transform)
}

// ------------------------------------------------------------------------------------- measurements

@Serializable
data class MeasurementArchiveState(val items: List<MeasurementContext> = emptyList())

/** Recent snapshots by id, so a later turn ("compare with the one from before the EQ change") can find them. */
class MeasurementArchive(dir: File) {
    private val store = PersistentState(File(dir, "measurements.json"), MeasurementArchiveState.serializer(), 1, { MeasurementArchiveState() })
    fun get(id: String): MeasurementContext? = store.value.items.firstOrNull { it.snapshotId == id }
    fun all(): List<MeasurementContext> = store.value.items

    suspend fun put(ctx: MeasurementContext) =
        store.update { s -> if (s.items.any { it.snapshotId == ctx.snapshotId }) s else MeasurementArchiveState((s.items + ctx).takeLast(MAX_ITEMS)) }

    companion object {
        const val MAX_ITEMS = 24
    }
}
