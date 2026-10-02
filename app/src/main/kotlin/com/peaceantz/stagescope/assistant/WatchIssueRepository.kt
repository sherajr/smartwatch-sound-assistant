package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.shared.issues.IssueFilter
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueLedgerState
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.issues.IssueTextParser
import com.peaceantz.stagescope.shared.issues.IssueView
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.store.PersistentState
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** What was just logged, so the screen can read it back and offer Undo. */
data class LoggedIssue(val issueId: String, val summary: String, val tentativelyResolved: Boolean, val loggedAtEpochMs: Long)

/**
 * The watch's own copy of the show issue log. Logging works with no phone, no network and no AI: the
 * person's words are kept verbatim as the original observation and only split into fields by the
 * conservative offline parser ([IssueTextParser]). Each device edits its own replica and merges the
 * other's (see :shared `IssueSync`), so edits made while apart are never lost; the Data Layer queues
 * the published copy until the phone is back.
 */
class WatchIssueRepository(
    file: File,
    private val sink: IssueDataSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val store = PersistentState(
        file, IssueLedgerState.serializer(), 1,
        { IssueLedgerState(replicaId = "w-" + UUID.randomUUID().toString().take(8)) },
    )

    val state: StateFlow<IssueLedgerState> = store.state
    val replicaId: String get() = store.value.replicaId

    fun views(filter: IssueFilter = IssueFilter()): List<IssueView> = IssueLedger.views(store.value, filter)

    fun view(issueId: String): IssueView? = store.value.issues[issueId]?.view()?.takeIf { !it.deleted }

    /** Logs [text] exactly as said. [evidenceSnapshotId] is set only when the person chose to attach a measurement. */
    suspend fun log(text: String, productionId: String?, performanceId: String?, evidenceSnapshotId: String? = null): LoggedIssue {
        val parsed = IssueTextParser.parse(text)
        val now = clock()
        val issueId = newId()
        val opId = newId()
        var created: IssueState? = null
        store.update { s ->
            val issue = IssueOps.create(
                id = issueId, replica = s.replicaId, nowEpochMs = now, originalObservation = parsed.observation,
                description = parsed.description, productionId = productionId, performanceId = performanceId,
                equipment = parsed.equipment, channel = parsed.channel, attemptedFix = parsed.attemptedFix,
                severity = parsed.severity, status = parsed.status, certainty = parsed.certainty,
                resolutionNote = parsed.resolutionNote, evidenceSnapshotId = evidenceSnapshotId,
            )
            created = issue
            IssueLedger.create(s, opId, issue)
        }
        created?.let { sink.putIssue(replicaId, store.value.issues[it.id] ?: it) }
        return LoggedIssue(
            issueId, parsed.description.ifBlank { parsed.observation }.take(80),
            tentativelyResolved = parsed.certainty == ResolutionCertainty.TENTATIVE && parsed.status == com.peaceantz.stagescope.shared.issues.IssueStatus.RESOLVED,
            loggedAtEpochMs = now,
        )
    }

    /** Undo is a tombstone, not a removal: the delete has to sync, or the phone's copy would bring the issue back. */
    suspend fun undo(issueId: String) = mutate(issueId) { IssueLedger.delete(it, newId(), issueId) }

    suspend fun resolve(issueId: String, certainty: ResolutionCertainty) = mutate(issueId) { IssueLedger.resolve(it, newId(), issueId, certainty, null) }

    suspend fun reopen(issueId: String) = mutate(issueId) { IssueLedger.reopen(it, newId(), issueId, null) }

    suspend fun restore(issueId: String) = mutate(issueId) { IssueLedger.restore(it, newId(), issueId) }

    private suspend fun mutate(issueId: String, change: (IssueLedgerState) -> IssueLedgerState) {
        store.update(change)
        store.value.issues[issueId]?.let { sink.putIssue(replicaId, it) }
    }

    /**
     * Folds in the phone's replica ([items]: data item path + payload). Our own replica's items and malformed ones are
     * ignored. Returns true if anything changed; the changed issues are re-published so the phone sees the converged copy.
     */
    suspend fun mergeFromPhone(items: List<Pair<String, ByteArray?>>): Boolean {
        val own = replicaId
        val changedIds = LinkedHashSet<String>()
        for ((path, bytes) in items) {
            if (bytes == null || !path.startsWith(Wire.DATA_ISSUES_PREFIX + "/")) continue
            val replica = path.removePrefix(Wire.DATA_ISSUES_PREFIX + "/").substringBefore('/')
            if (replica == own) continue
            val remote = runCatching { StageScopeJson.decodeFromString(IssueState.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            store.update { current ->
                val next = IssueLedger.mergeRemote(current, remote)
                if (next != current) changedIds += remote.id
                next
            }
        }
        changedIds.forEach { id -> store.value.issues[id]?.let { sink.putIssue(own, it) } }
        return changedIds.isNotEmpty()
    }

    /** Re-publishes every issue (e.g. after the phone app is installed later); identical data causes no new sync. */
    suspend fun publishAll() {
        store.value.issues.values.forEach { sink.putIssue(replicaId, it) }
    }
}
