package com.peaceantz.stagescope.shared.issues

import kotlinx.serialization.Serializable

/**
 * One replica's complete issue log (each device keeps its own copy and merges the other's).
 *
 * Local writes are idempotent by operation id: a retried "log this issue" that carries the same
 * [appliedOps] id is a no-op, which is what makes duplicate delivery and process-death retries
 * safe. [appliedOps] is a bounded FIFO -- old ids fall off, which is fine because an op older than
 * the window cannot still be in flight.
 */
@Serializable
data class IssueLedgerState(
    val replicaId: String = "",
    val issues: Map<String, IssueState> = emptyMap(),
    val appliedOps: List<String> = emptyList(),
)

data class IssueFilter(
    val productionId: String? = null,
    val performanceId: String? = null,
    val statuses: Set<IssueStatus>? = null,
    val includeDeleted: Boolean = false,
    val onlyWithConflicts: Boolean = false,
    val query: String? = null,
)

object IssueLedger {
    const val MAX_APPLIED_OPS = 500

    fun create(state: IssueLedgerState, opId: String, issue: IssueState): IssueLedgerState {
        if (opId in state.appliedOps) return state
        val existing = state.issues[issue.id]
        val merged = if (existing == null) issue else IssueOps.merge(existing, issue)
        return state.copy(issues = state.issues + (issue.id to merged)).recorded(opId)
    }

    fun edit(state: IssueLedgerState, opId: String, issueId: String, patch: IssuePatch): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.edit(it, state.replicaId, patch) }

    fun delete(state: IssueLedgerState, opId: String, issueId: String): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.delete(it, state.replicaId) }

    fun restore(state: IssueLedgerState, opId: String, issueId: String): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.restore(it, state.replicaId) }

    fun resolve(state: IssueLedgerState, opId: String, issueId: String, certainty: ResolutionCertainty, note: String?): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.resolve(it, state.replicaId, certainty, note) }

    fun reopen(state: IssueLedgerState, opId: String, issueId: String, note: String?): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.reopen(it, state.replicaId, note) }

    /** Resolves a conflict by choosing one sibling value (index 0 = the value currently shown). */
    fun chooseSibling(state: IssueLedgerState, opId: String, issueId: String, field: IssueField, index: Int): IssueLedgerState =
        mutate(state, opId, issueId) { IssueOps.chooseSibling(it, state.replicaId, field, index) }

    /** Folds in another replica's state for one issue. Safe to call any number of times, in any order. */
    fun mergeRemote(state: IssueLedgerState, remote: IssueState): IssueLedgerState {
        val existing = state.issues[remote.id]
        val merged = if (existing == null) remote else IssueOps.merge(existing, remote)
        return if (merged == existing) state else state.copy(issues = state.issues + (remote.id to merged))
    }

    fun views(state: IssueLedgerState, filter: IssueFilter = IssueFilter()): List<IssueView> =
        state.issues.values.asSequence()
            .map { it.view() }
            .filter { filter.includeDeleted || !it.deleted }
            .filter { filter.productionId == null || it.productionId == filter.productionId }
            .filter { filter.performanceId == null || it.performanceId == filter.performanceId }
            .filter { filter.statuses == null || it.status in filter.statuses }
            .filter { !filter.onlyWithConflicts || it.conflicts.isNotEmpty() }
            .filter { v ->
                val q = filter.query?.trim()?.lowercase().orEmpty()
                q.isEmpty() || listOf(v.description, v.originalObservation, v.equipment, v.channel, v.attemptedFix, v.resolutionNote)
                    .any { it?.lowercase()?.contains(q) == true }
            }
            .sortedWith(compareByDescending<IssueView> { it.occurredAtEpochMs ?: it.createdAtEpochMs }.thenBy { it.id })
            .toList()

    private fun mutate(
        state: IssueLedgerState,
        opId: String,
        issueId: String,
        change: (IssueState) -> IssueState,
    ): IssueLedgerState {
        if (opId in state.appliedOps) return state
        val existing = state.issues[issueId] ?: return state.recorded(opId)
        val next = change(existing)
        return state.copy(issues = if (next == existing) state.issues else state.issues + (issueId to next)).recorded(opId)
    }

    private fun IssueLedgerState.recorded(opId: String): IssueLedgerState =
        copy(appliedOps = (appliedOps + opId).takeLast(MAX_APPLIED_OPS))
}
