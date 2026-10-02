package com.peaceantz.stagescope.shared.issues

import kotlinx.serialization.Serializable

/**
 * One logged show issue, as replicated state. Every editable field is an [MVRegister], so two
 * devices can edit the same issue offline and nothing a person typed is ever silently lost.
 *
 * - [originalObservation] is the user's own words and never changes; AI wording lives separately
 *   in [aiWording] and never replaces it.
 * - [deleted] is a tombstone register, never a removal: a delete must survive sync (otherwise the
 *   other replica would resurrect the issue). A delete concurrent with an edit/restore resolves in
 *   favour of keeping the data (see [isDeleted]).
 * - [vv] is the issue-level version vector; [revision] is its total, a persistent monotonic count of
 *   edits this issue has seen across all replicas (not a timestamp).
 */
@Serializable
data class IssueState(
    val id: String,
    val createdBy: String,
    /** The creator's wall clock -- informational only, never used to order or resolve edits. */
    val createdAtEpochMs: Long,
    val originalObservation: String,
    val vv: VersionVector,
    val productionId: MVRegister<String?>,
    val performanceId: MVRegister<String?>,
    val occurredAtEpochMs: MVRegister<Long?>,
    val description: MVRegister<String>,
    val equipment: MVRegister<String?>,
    val channel: MVRegister<String?>,
    val attemptedFix: MVRegister<String?>,
    val severity: MVRegister<IssueSeverity?>,
    val status: MVRegister<IssueStatus>,
    val certainty: MVRegister<ResolutionCertainty>,
    val resolutionNote: MVRegister<String?>,
    /** Id of a measurement snapshot the person *intentionally* attached as evidence. */
    val evidenceSnapshotId: MVRegister<String?>,
    val aiWording: MVRegister<String?>,
    val deleted: MVRegister<Boolean>,
) {
    val revision: Long get() = vv.total

    private fun registers(): List<MVRegister<*>> = listOf(
        productionId, performanceId, occurredAtEpochMs, description, equipment, channel, attemptedFix,
        severity, status, certainty, resolutionNote, evidenceSnapshotId, aiWording,
    )

    /**
     * True when some field carries an edit that the deleting replica had not seen: a delete that
     * raced with an edit. Hiding such an issue would hide text a person typed, so it stays visible.
     */
    private val hasEditUnseenByDelete: Boolean
        get() = deleted.siblings.any { it.value } &&
            registers().any { reg -> reg.siblings.any { s -> deleted.siblings.none { d -> d.value && d.vv.dominates(s.vv) } } }

    /** Deleted only if every sibling agrees AND no concurrent edit would be hidden by it. */
    val isDeleted: Boolean get() = deleted.siblings.all { it.value } && !hasEditUnseenByDelete

    fun conflicts(): List<IssueFieldConflict> = buildList {
        if (deleted.siblings.any { it.value } && !isDeleted) {
            add(IssueFieldConflict(IssueField.DELETED, "deleted on one device", listOf("edited or restored on another device")))
        }
        fun <T> check(field: IssueField, reg: MVRegister<T>) {
            if (reg.hasConflict) add(IssueFieldConflict(field, reg.value.toString(), reg.alternatives().map { it.toString() }))
        }
        check(IssueField.PRODUCTION, productionId)
        check(IssueField.PERFORMANCE, performanceId)
        check(IssueField.OCCURRED_AT, occurredAtEpochMs)
        check(IssueField.DESCRIPTION, description)
        check(IssueField.EQUIPMENT, equipment)
        check(IssueField.CHANNEL, channel)
        check(IssueField.ATTEMPTED_FIX, attemptedFix)
        check(IssueField.SEVERITY, severity)
        check(IssueField.STATUS, status)
        check(IssueField.CERTAINTY, certainty)
        check(IssueField.RESOLUTION_NOTE, resolutionNote)
        check(IssueField.EVIDENCE, evidenceSnapshotId)
        check(IssueField.AI_WORDING, aiWording)
    }

    fun view(): IssueView = IssueView(
        id = id,
        revision = revision,
        createdAtEpochMs = createdAtEpochMs,
        originalObservation = originalObservation,
        productionId = productionId.value,
        performanceId = performanceId.value,
        occurredAtEpochMs = occurredAtEpochMs.value,
        description = description.value,
        equipment = equipment.value,
        channel = channel.value,
        attemptedFix = attemptedFix.value,
        severity = severity.value,
        status = status.value,
        certainty = certainty.value,
        resolutionNote = resolutionNote.value,
        evidenceSnapshotId = evidenceSnapshotId.value,
        aiWording = aiWording.value,
        deleted = isDeleted,
        conflicts = conflicts(),
    )
}

@Serializable
data class IssueFieldConflict(val field: IssueField, val shownValue: String, val otherValues: List<String>)

/** Flattened, display-ready issue. Read-only: edits go through [IssueOps]. */
@Serializable
data class IssueView(
    val id: String,
    val revision: Long,
    val createdAtEpochMs: Long,
    val originalObservation: String,
    val productionId: String?,
    val performanceId: String?,
    val occurredAtEpochMs: Long?,
    val description: String,
    val equipment: String?,
    val channel: String?,
    val attemptedFix: String?,
    val severity: IssueSeverity?,
    val status: IssueStatus,
    val certainty: ResolutionCertainty,
    val resolutionNote: String?,
    val evidenceSnapshotId: String?,
    val aiWording: String?,
    val deleted: Boolean,
    val conflicts: List<IssueFieldConflict>,
) {
    val isOpen: Boolean get() = status != IssueStatus.RESOLVED

    /** "Seems resolved" stays tentative on screen and in exports. */
    fun statusLabel(): String = when {
        status == IssueStatus.OPEN -> "Open"
        status == IssueStatus.REOPENED -> "Reopened"
        certainty == ResolutionCertainty.TENTATIVE -> "Seems resolved"
        else -> "Resolved"
    }
}

/** "Not changing this field" is the default; [Change] with a null value means "clear it". */
data class Change<T>(val value: T)

data class IssuePatch(
    val productionId: Change<String?>? = null,
    val performanceId: Change<String?>? = null,
    val occurredAtEpochMs: Change<Long?>? = null,
    val description: Change<String>? = null,
    val equipment: Change<String?>? = null,
    val channel: Change<String?>? = null,
    val attemptedFix: Change<String?>? = null,
    val severity: Change<IssueSeverity?>? = null,
    val status: Change<IssueStatus>? = null,
    val certainty: Change<ResolutionCertainty>? = null,
    val resolutionNote: Change<String?>? = null,
    val evidenceSnapshotId: Change<String?>? = null,
    val aiWording: Change<String?>? = null,
)

/** Pure state transitions. Nothing here reads a clock except what the caller passes in. */
object IssueOps {

    fun create(
        id: String,
        replica: String,
        nowEpochMs: Long,
        originalObservation: String,
        description: String = originalObservation,
        productionId: String? = null,
        performanceId: String? = null,
        occurredAtEpochMs: Long? = nowEpochMs,
        equipment: String? = null,
        channel: String? = null,
        attemptedFix: String? = null,
        severity: IssueSeverity? = null,
        status: IssueStatus = IssueStatus.OPEN,
        certainty: ResolutionCertainty = ResolutionCertainty.CONFIRMED,
        resolutionNote: String? = null,
        evidenceSnapshotId: String? = null,
    ): IssueState {
        val vv = VersionVector().increment(replica)
        fun <T> reg(v: T) = MVRegister.of(v, vv, replica)
        return IssueState(
            id = id, createdBy = replica, createdAtEpochMs = nowEpochMs,
            originalObservation = originalObservation, vv = vv,
            productionId = reg(productionId), performanceId = reg(performanceId),
            occurredAtEpochMs = reg(occurredAtEpochMs), description = reg(description),
            equipment = reg(equipment), channel = reg(channel), attemptedFix = reg(attemptedFix),
            severity = reg(severity), status = reg(status), certainty = reg(certainty),
            resolutionNote = reg(resolutionNote), evidenceSnapshotId = reg(evidenceSnapshotId),
            aiWording = reg<String?>(null), deleted = reg(false),
        )
    }

    /** Applies [patch] as ONE edit (one version-vector tick). Returns the same state if nothing changes. */
    fun edit(state: IssueState, replica: String, patch: IssuePatch): IssueState {
        val vv = state.vv.increment(replica)
        var changed = false
        fun <T> apply(reg: MVRegister<T>, change: Change<T>?): MVRegister<T> {
            if (change == null) return reg
            if (!reg.hasConflict && reg.value == change.value) return reg
            changed = true
            return MVRegister.of(change.value, vv, replica)
        }
        val next = state.copy(
            productionId = apply(state.productionId, patch.productionId),
            performanceId = apply(state.performanceId, patch.performanceId),
            occurredAtEpochMs = apply(state.occurredAtEpochMs, patch.occurredAtEpochMs),
            description = apply(state.description, patch.description),
            equipment = apply(state.equipment, patch.equipment),
            channel = apply(state.channel, patch.channel),
            attemptedFix = apply(state.attemptedFix, patch.attemptedFix),
            severity = apply(state.severity, patch.severity),
            status = apply(state.status, patch.status),
            certainty = apply(state.certainty, patch.certainty),
            resolutionNote = apply(state.resolutionNote, patch.resolutionNote),
            evidenceSnapshotId = apply(state.evidenceSnapshotId, patch.evidenceSnapshotId),
            aiWording = apply(state.aiWording, patch.aiWording),
        )
        return if (changed) next.copy(vv = vv) else state
    }

    fun delete(state: IssueState, replica: String): IssueState = setDeleted(state, replica, true)

    fun restore(state: IssueState, replica: String): IssueState = setDeleted(state, replica, false)

    private fun setDeleted(state: IssueState, replica: String, value: Boolean): IssueState {
        // A no-op only when the visible outcome already matches. After a delete-vs-edit race the
        // register can read "deleted" while the issue is still shown ([IssueState.isDeleted] is false
        // because an edit was unseen by the delete): a fresh delete must still be recorded, with a
        // version vector that dominates that edit, or the person could never confirm the delete.
        if (!state.deleted.hasConflict && state.deleted.value == value && state.isDeleted == value) return state
        val vv = state.vv.increment(replica)
        return state.copy(vv = vv, deleted = MVRegister.of(value, vv, replica))
    }

    /**
     * Resolves a conflicted [field] by choosing one of its sibling values ([index] into the register's
     * siblings, 0 = the value currently shown). The choice is an ordinary edit stamped from the merged
     * version vector, so it dominates every sibling and the conflict is gone on every replica once synced.
     */
    fun chooseSibling(state: IssueState, replica: String, field: IssueField, index: Int): IssueState {
        fun <T> pick(reg: MVRegister<T>, build: (T) -> IssuePatch): IssuePatch? = reg.siblings.getOrNull(index)?.let { build(it.value) }
        val patch: IssuePatch? = when (field) {
            IssueField.PRODUCTION -> pick(state.productionId) { IssuePatch(productionId = Change(it)) }
            IssueField.PERFORMANCE -> pick(state.performanceId) { IssuePatch(performanceId = Change(it)) }
            IssueField.OCCURRED_AT -> pick(state.occurredAtEpochMs) { IssuePatch(occurredAtEpochMs = Change(it)) }
            IssueField.DESCRIPTION -> pick(state.description) { IssuePatch(description = Change(it)) }
            IssueField.EQUIPMENT -> pick(state.equipment) { IssuePatch(equipment = Change(it)) }
            IssueField.CHANNEL -> pick(state.channel) { IssuePatch(channel = Change(it)) }
            IssueField.ATTEMPTED_FIX -> pick(state.attemptedFix) { IssuePatch(attemptedFix = Change(it)) }
            IssueField.SEVERITY -> pick(state.severity) { IssuePatch(severity = Change(it)) }
            IssueField.STATUS -> pick(state.status) { IssuePatch(status = Change(it)) }
            IssueField.CERTAINTY -> pick(state.certainty) { IssuePatch(certainty = Change(it)) }
            IssueField.RESOLUTION_NOTE -> pick(state.resolutionNote) { IssuePatch(resolutionNote = Change(it)) }
            IssueField.EVIDENCE -> pick(state.evidenceSnapshotId) { IssuePatch(evidenceSnapshotId = Change(it)) }
            IssueField.AI_WORDING -> pick(state.aiWording) { IssuePatch(aiWording = Change(it)) }
            // index 0 = keep the issue (restore), anything else = confirm the delete.
            IssueField.DELETED -> return if (index == 0) restore(state, replica) else delete(state, replica)
        }
        return if (patch == null) state else edit(state, replica, patch)
    }

    /** Marks an issue resolved, preserving whether the person was sure. */
    fun resolve(state: IssueState, replica: String, certainty: ResolutionCertainty, note: String? = null): IssueState =
        edit(
            state, replica,
            IssuePatch(
                status = Change(IssueStatus.RESOLVED),
                certainty = Change(certainty),
                resolutionNote = if (note != null) Change(note) else null,
            ),
        )

    fun reopen(state: IssueState, replica: String, note: String? = null): IssueState =
        edit(
            state, replica,
            IssuePatch(
                status = Change(IssueStatus.REOPENED),
                certainty = Change(ResolutionCertainty.CONFIRMED),
                resolutionNote = if (note != null) Change(note) else null,
            ),
        )

    /** State-based merge: commutative, associative, idempotent. Throws only on a programming error (id mismatch). */
    fun merge(a: IssueState, b: IssueState): IssueState {
        require(a.id == b.id) { "Cannot merge different issues (${a.id} vs ${b.id})" }
        // Immutable fields should be identical on both sides; if they ever differ, pick the same
        // winner on every replica (smallest creator id) so replicas still converge.
        val base = if (a.createdBy <= b.createdBy) a else b
        return base.copy(
            vv = a.vv.merge(b.vv),
            productionId = a.productionId.merge(b.productionId),
            performanceId = a.performanceId.merge(b.performanceId),
            occurredAtEpochMs = a.occurredAtEpochMs.merge(b.occurredAtEpochMs),
            description = a.description.merge(b.description),
            equipment = a.equipment.merge(b.equipment),
            channel = a.channel.merge(b.channel),
            attemptedFix = a.attemptedFix.merge(b.attemptedFix),
            severity = a.severity.merge(b.severity),
            status = a.status.merge(b.status),
            certainty = a.certainty.merge(b.certainty),
            resolutionNote = a.resolutionNote.merge(b.resolutionNote),
            evidenceSnapshotId = a.evidenceSnapshotId.merge(b.evidenceSnapshotId),
            aiWording = a.aiWording.merge(b.aiWording),
            deleted = a.deleted.merge(b.deleted),
        )
    }
}
