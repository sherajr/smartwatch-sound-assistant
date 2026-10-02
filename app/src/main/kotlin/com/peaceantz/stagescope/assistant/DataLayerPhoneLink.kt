package com.peaceantz.stagescope.assistant

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.protocol.Envelope
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.protocol.WireCodec
import com.peaceantz.stagescope.shared.protocol.WireMessage
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Where the watch publishes its own copy of an issue so the phone's replica can merge it. */
fun interface IssueDataSink {
    suspend fun putIssue(replicaId: String, issue: IssueState)
}

/**
 * The watch's real Wear Data Layer connection (Play services). A successful `sendMessage` only means the
 * transport accepted it -- the phone's own acknowledgement is what [AssistantRepository] treats as delivery.
 * Every call fails soft: no Play services, no paired phone, or no phone app simply reads as "not reachable",
 * and the instruments never depend on any of it.
 */
class DataLayerPhoneLink(
    private val context: Context,
    private val installId: () -> String,
) : PhoneLink, IssueDataSink {
    private val seq = AtomicLong(0)

    override suspend fun reachablePhones(): List<PhoneNode> = runCatching {
        Wearable.getCapabilityClient(context).getCapability(Wire.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { PhoneNode(it.id, it.displayName, it.isNearby) }
    }.getOrDefault(emptyList())

    override suspend fun send(message: WireMessage): Int {
        // Encoded first so an oversize message is reported as such even when no phone is around.
        val bytes = WireCodec.encode(Envelope(sender = "watch:${installId()}", seq = seq.incrementAndGet(), message = message))
        val phones = reachablePhones()
        var delivered = 0
        for (p in phones) {
            runCatching { Wearable.getMessageClient(context).sendMessage(p.id, Wire.MSG, bytes).await() }.onSuccess { delivered++ }
        }
        return delivered
    }

    override suspend fun sendVoice(memoId: String, file: File, nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val client = Wearable.getChannelClient(context)
        val channel = runCatching { client.openChannel(nodeId, "${Wire.CHANNEL_VOICE}/$memoId").await() }.getOrNull() ?: return@withContext false
        try {
            client.getOutputStream(channel).await().use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { client.close(channel).await() }
        }
    }

    override suspend fun currentDataItems(): List<Pair<String, ByteArray>> = runCatching {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(Wire.ROOT).build()
        val buffer = Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_PREFIX).await()
        try {
            buffer.mapNotNull { item -> item.data?.let { (item.uri.path ?: return@mapNotNull null) to it } }
        } finally {
            buffer.release()
        }
    }.getOrDefault(emptyList())

    override suspend fun putIssue(replicaId: String, issue: IssueState) {
        val bytes = StageScopeJson.encodeToString(IssueState.serializer(), issue).toByteArray(Charsets.UTF_8)
        if (bytes.size > Wire.MAX_PAYLOAD_BYTES) return
        runCatching {
            Wearable.getDataClient(context).putDataItem(PutDataRequest.create(Wire.issuePath(replicaId, issue.id)).setData(bytes).setUrgent()).await()
        }
    }
}
