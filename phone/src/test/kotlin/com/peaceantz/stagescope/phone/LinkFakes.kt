package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.link.ConfirmCommand
import com.peaceantz.stagescope.phone.link.Continuations
import com.peaceantz.stagescope.phone.link.WatchLink
import com.peaceantz.stagescope.phone.link.WatchNode
import com.peaceantz.stagescope.phone.link.WorkScheduler
import com.peaceantz.stagescope.phone.security.CredentialStore
import com.peaceantz.stagescope.phone.security.EncryptedFileCredentialStore
import com.peaceantz.stagescope.phone.tools.ContinueTarget
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.WireMessage
import com.peaceantz.stagescope.shared.show.WatchShowView

/** Records everything the phone would send to / publish for the watch. */
class FakeWatchLink : WatchLink {
    var nodes = listOf(WatchNode("node-1", "Pixel Watch", nearby = true))
    val sent = mutableListOf<WireMessage>()
    val threads = mutableListOf<ThreadView>()
    val providers = mutableListOf<ProvidersView>()
    val shows = mutableListOf<WatchShowView>()
    val issues = mutableListOf<Pair<String, IssueState>>()

    override suspend fun reachableWatches() = nodes
    override suspend fun send(message: WireMessage): Int { sent += message; return nodes.size }
    override suspend fun putThread(view: ThreadView) { threads += view }
    override suspend fun putProviders(view: ProvidersView) { providers += view }
    override suspend fun putShows(view: WatchShowView) { shows += view }
    override suspend fun putIssue(replicaId: String, issue: IssueState) { issues += replicaId to issue }

    inline fun <reified T : WireMessage> sentOf(): List<T> = sent.filterIsInstance<T>()
}

class FakeScheduler : WorkScheduler {
    val assistant = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val confirms = mutableListOf<ConfirmCommand>()
    override fun enqueueAssistant(requestId: String) { assistant += requestId }
    override fun cancelAssistant(requestId: String) { cancelled += requestId }
    override fun enqueueConfirm(cmd: ConfirmCommand) { confirms += cmd }
}

class FakeContinuations(var outcome: ContinueOutcome = ContinueOutcome.NOTIFICATION_POSTED) : Continuations {
    val calls = mutableListOf<Pair<String, ContinueTarget>>()
    override suspend fun continueOnPhone(requestId: String, target: ContinueTarget): ContinueOutcome {
        calls += requestId to target
        return outcome
    }
}

/** In-memory credentials with the same normalisation as the real store. */
class FakeCredentials : CredentialStore {
    val keys = mutableMapOf<ProviderId, String>()
    val unreadable = mutableSetOf<ProviderId>()
    override fun key(provider: ProviderId): String? = if (provider in unreadable) null else keys[provider]
    override fun has(provider: ProviderId) = provider in keys
    override suspend fun put(provider: ProviderId, key: String) { keys[provider] = EncryptedFileCredentialStore.normalize(key) }
    override suspend fun remove(provider: ProviderId) { keys -= provider }
}
