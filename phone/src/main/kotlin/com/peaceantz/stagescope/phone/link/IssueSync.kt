package com.peaceantz.stagescope.phone.link

import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.util.StageScopeJson

/**
 * Folds the watch's issue replica into the phone's, and republishes the phone's own copy. Issues are
 * state-based replicated (see `IssueSync.kt` in :shared): each replica publishes ONLY its own state
 * under `/issues/<replicaId>/<issueId>` -- never overwriting the other's item -- and merges what it
 * reads, so concurrent edits survive as visible conflicts instead of one silently clobbering the
 * other, and duplicate or reordered deliveries are harmless.
 */
class IssueSync(private val data: PhoneData, private val publisher: ThreadPublisher) {

    /** [items]: (data item path, payload). Malformed or own-replica items are ignored. */
    suspend fun mergeFromWatch(items: List<Pair<String, ByteArray?>>) {
        val ownReplica = data.issues.replicaId
        var changed = false
        for ((path, bytes) in items) {
            val replica = path.removePrefix("/stagescope/v1/issues/").substringBefore('/')
            if (replica == ownReplica || bytes == null) continue
            val remote = runCatching { StageScopeJson.decodeFromString(IssueState.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            data.issues.update { current ->
                val next = IssueLedger.mergeRemote(current, remote)
                if (next != current) changed = true
                next
            }
        }
        // Publish our (possibly merged) copy so the watch sees the converged result.
        if (changed) publisher.publishIssues()
    }
}
