package com.peaceantz.stagescope.phone.link

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.peaceantz.stagescope.phone.PhoneApp
import com.peaceantz.stagescope.shared.protocol.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives watch messages, data items and recordings. Per Wear OS guidance a listener must be quick:
 * every callback here persists the request and acknowledges it, then returns -- long work (an AI
 * request, a Gmail send) runs in WorkManager, never in an in-memory coroutine that
 * would die with this service.
 */
class WatchListenerService : WearableListenerService() {

    private val container get() = (application as PhoneApp).container

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Wire.MSG) return
        val bytes = event.data
        runCatching {
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(10_000) { container.messageHandler.handleBytes(bytes) } }
        }.onFailure { Log.w(TAG, "message handling failed: ${it.javaClass.simpleName}") }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // Copy what we need before returning: the buffer is released when this callback ends.
        val incoming = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(Wire.DATA_ISSUES_PREFIX + "/") == true }
            .map { it.dataItem.uri.path.orEmpty() to it.dataItem.data }
        if (incoming.isEmpty()) return
        runCatching { runBlocking(Dispatchers.IO) { container.issueSync.mergeFromWatch(incoming) } }
            .onFailure { Log.w(TAG, "issue sync failed: ${it.javaClass.simpleName}") }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == Wire.CAPABILITY_WATCH && info.nodes.isNotEmpty()) {
            runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(8_000) { container.messageHandler.publishAll() } } }
        }
    }

    /**
     * An older watch app may still open a channel to stream a recording. StageScope no longer accepts audio: the channel is closed
     * without reading a byte, nothing is stored, and no transcription (on this phone or in a cloud service) is ever started.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(Wire.CHANNEL_VOICE + "/")) return
        runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(5_000) { Wearable.getChannelClient(this@WatchListenerService).close(channel).await() } } }
        Log.i(TAG, "refused a legacy recording channel")
    }

    companion object {
        private const val TAG = "StageScope/Link"
    }
}
