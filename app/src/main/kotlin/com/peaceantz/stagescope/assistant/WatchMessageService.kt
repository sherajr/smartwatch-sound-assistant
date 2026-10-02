package com.peaceantz.stagescope.assistant

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.peaceantz.stagescope.StageScopeApp
import com.peaceantz.stagescope.shared.protocol.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the phone's replies, durable thread/provider/show data items, its issue replica and capability
 * changes. Per Wear guidance every callback is quick: it persists what arrived and returns. Nothing here starts
 * the microphone or a speaker, and nothing here sends anything the person didn't ask for -- the one thing it may do on
 * reconnect is deliver a question that is still fresh (see [OutboxPolicy]).
 */
class WatchMessageService : WearableListenerService() {
    private val container get() = (application as StageScopeApp).container

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Wire.MSG) return
        val bytes = event.data
        runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(8_000) { container.assistant.handleBytes(bytes) } } }
            .onFailure { Log.w(TAG, "message handling failed: ${it.javaClass.simpleName}") }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // Copy what's needed before returning: the buffer is released when this callback ends.
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.uri.path.orEmpty() to it.dataItem.data }
        if (items.isEmpty()) return
        val (issueItems, viewItems) = items.partition { it.first.startsWith(Wire.DATA_ISSUES_PREFIX + "/") }
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(8_000) {
                    if (viewItems.isNotEmpty()) container.assistant.handleDataItems(viewItems)
                    if (issueItems.isNotEmpty()) container.issues.mergeFromPhone(issueItems)
                }
            }
        }.onFailure { Log.w(TAG, "data handling failed: ${it.javaClass.simpleName}") }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != Wire.CAPABILITY_PHONE) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(8_000) {
                    container.assistant.flushOutbox()
                    container.assistant.uploadPendingMemos()
                    if (info.nodes.isNotEmpty()) container.issues.publishAll()
                }
            }
        }
    }

    companion object {
        private const val TAG = "StageScope/Link"
    }
}
