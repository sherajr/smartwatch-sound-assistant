package com.peaceantz.stagescope.phone.link

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.protocol.Envelope
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.protocol.WireCodec
import com.peaceantz.stagescope.shared.protocol.WireMessage
import com.peaceantz.stagescope.shared.show.WatchShowView
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicLong

data class WatchNode(val id: String, val displayName: String, val nearby: Boolean)

/**
 * The phone's side of the Wear Data Layer. `sendMessage` returning true only means the message was
 * handed to the transport -- never that the watch processed it; the watch's own application-level
 * ack and the durable data items are what carry meaning.
 */
interface WatchLink {
    suspend fun reachableWatches(): List<WatchNode>

    /** Sends to every reachable watch running StageScope. Returns how many nodes it was handed to. */
    suspend fun send(message: WireMessage): Int

    suspend fun putThread(view: ThreadView)
    suspend fun putProviders(view: ProvidersView)
    suspend fun putShows(view: WatchShowView)
    suspend fun putIssue(replicaId: String, issue: IssueState)
}

class DataLayerWatchLink(
    private val context: Context,
    private val installId: () -> String,
) : WatchLink {
    private val seq = AtomicLong(0)

    private fun capabilityClient() = Wearable.getCapabilityClient(context)

    override suspend fun reachableWatches(): List<WatchNode> = runCatching {
        capabilityClient().getCapability(Wire.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { WatchNode(it.id, it.displayName, it.isNearby) }
    }.getOrDefault(emptyList())

    override suspend fun send(message: WireMessage): Int {
        val nodes = reachableWatches()
        if (nodes.isEmpty()) return 0
        val bytes = WireCodec.encode(Envelope(sender = "phone:${installId()}", seq = seq.incrementAndGet(), message = message))
        var delivered = 0
        for (n in nodes) {
            runCatching { Wearable.getMessageClient(context).sendMessage(n.id, Wire.MSG, bytes).await() }.onSuccess { delivered++ }
        }
        return delivered
    }

    override suspend fun putThread(view: ThreadView) = put(Wire.threadPath(view.conversationId), StageScopeJson.encodeToString(ThreadView.serializer(), view))

    override suspend fun putProviders(view: ProvidersView) = put(Wire.DATA_PROVIDERS, StageScopeJson.encodeToString(ProvidersView.serializer(), view))

    override suspend fun putShows(view: WatchShowView) = put(Wire.DATA_SHOWS, StageScopeJson.encodeToString(WatchShowView.serializer(), view))

    override suspend fun putIssue(replicaId: String, issue: IssueState) =
        put(Wire.issuePath(replicaId, issue.id), StageScopeJson.encodeToString(IssueState.serializer(), issue))

    private suspend fun put(path: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > Wire.MAX_PAYLOAD_BYTES) return // never exceed the Data Layer's per-item limit
        runCatching {
            Wearable.getDataClient(context).putDataItem(PutDataRequest.create(path).setData(bytes).setUrgent()).await()
        }
    }
}
