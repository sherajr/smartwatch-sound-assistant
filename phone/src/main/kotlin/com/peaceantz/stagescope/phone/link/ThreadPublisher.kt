package com.peaceantz.stagescope.phone.link

import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.Progress
import com.peaceantz.stagescope.shared.protocol.ProviderStatus
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.ResultReady
import com.peaceantz.stagescope.shared.protocol.ThreadViewBuilder
import com.peaceantz.stagescope.shared.util.Hashing
import com.peaceantz.stagescope.shared.util.StageScopeJson

/**
 * Turns durable phone state into what the watch reads: a [com.peaceantz.stagescope.shared.protocol.ThreadView]
 * data item (survives disconnects and process death) plus a small `ResultReady` nudge. The watch can
 * always re-read the latest revision, so a missed message loses nothing.
 */
class ThreadPublisher(
    private val data: PhoneData,
    private val link: WatchLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notificationsEnabled: () -> Boolean = { true },
    private val appVersion: String = "",
) {
    suspend fun publishThread(conversationId: String, requestId: String? = null, state: RequestState = RequestState.COMPLETED) {
        val conv = data.conversations.get(conversationId) ?: return
        val actions = data.actions.forConversation(conversationId)
        val label = conv.providerId.label
        val view = ThreadViewBuilder.build(conv, actions, data.conversations.revision(conversationId), state, clock(), label)
        link.putThread(view)
        if (requestId != null) link.send(ResultReady(requestId, conversationId, view.revision, state))
    }

    /** A refreshed action card (e.g. after a confirm on the phone) goes out as a new thread revision. */
    suspend fun actionChanged(record: ActionRecord) {
        val conversationId = record.conversationId ?: return
        data.conversations.update(conversationId, clock()) { it }
        publishThread(conversationId, null, RequestState.COMPLETED)
    }

    suspend fun progress(requestId: String, stage: ProgressStage, text: String?) {
        link.send(Progress(requestId, stage, text))
    }

    /** What the watch is told about providers. Pure (no I/O) so it is unit-testable and its revision is deterministic. */
    fun providersView(hasKey: (ProviderId) -> Boolean, googleConnected: Boolean): ProvidersView {
        val s = data.settings.value
        val content = ProvidersView(
            revision = 0,
            selected = s.selectedProvider,
            providers = ProviderId.entries.map { p ->
                val info = ModelCatalog.find(p, s.modelFor(p))
                ProviderStatus(
                    providerId = p, label = p.label, hasKey = hasKey(p), keyValidated = s.keyStatus[p]?.validated == true,
                    modelId = s.modelFor(p), webSearchAvailable = info?.supportsWebSearch == true,
                    webSearchEnabled = s.webSearchFor(p), thorough = s.thorough[p] == true,
                )
            },
            googleConnected = googleConnected,
            gmailReady = s.gmailGranted, calendarReady = s.calendarGranted,
            notificationsEnabled = notificationsEnabled(), phoneAppVersion = appVersion, devMode = s.devMode,
        )
        // Derived from the published content only: an unchanged state yields an identical item, so the
        // watch isn't woken for nothing (and unrelated settings like "last heard from the watch" never leak in).
        val revision = Hashing.sha256Hex(StageScopeJson.encodeToString(ProvidersView.serializer(), content)).take(12).toLong(16)
        return content.copy(revision = revision)
    }

    suspend fun publishProviders(hasKey: (ProviderId) -> Boolean, googleConnected: Boolean) {
        link.putProviders(providersView(hasKey, googleConnected))
    }

    suspend fun publishShows() = link.putShows(data.shows.watchView())

    suspend fun publishIssues() {
        val state = data.issues.state.value
        state.issues.values.forEach { link.putIssue(state.replicaId, it) }
    }

    fun requestStateFor(requestId: String): RequestState = when (data.inbox.get(requestId)?.state) {
        null -> RequestState.UNKNOWN
        InboxState.RECEIVED -> RequestState.QUEUED
        InboxState.RUNNING -> RequestState.RUNNING
        InboxState.COMPLETED -> RequestState.COMPLETED
        InboxState.FAILED, InboxState.INTERRUPTED -> RequestState.FAILED
        InboxState.CANCELLED -> RequestState.CANCELLED
    }
}
