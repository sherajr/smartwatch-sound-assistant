package com.peaceantz.stagescope.phone.voice

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.data.UsageEntry
import com.peaceantz.stagescope.phone.link.WatchLink
import com.peaceantz.stagescope.phone.work.TranscribeWorker
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.protocol.TranscriptResult
import com.peaceantz.stagescope.shared.protocol.VoiceOffer
import com.peaceantz.stagescope.shared.protocol.Wire
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives a short watch recording over a Data Layer channel (too large for a message), keeps it only
 * long enough to transcribe, and answers with a transcript the person must review on the watch --
 * a transcript is never auto-sent anywhere. Bounded: at most [MAX_BYTES] (~90 s of 16 kHz PCM).
 */
class VoiceReceiver(
    private val context: Context,
    private val data: PhoneData,
    private val link: WatchLink,
    private val transcriber: () -> Transcriber,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val offers = ConcurrentHashMap<String, VoiceOffer>()

    fun onOffer(offer: VoiceOffer) {
        if (offer.byteCount in 1..MAX_BYTES) offers[offer.memoId] = offer
    }

    suspend fun receive(channel: ChannelClient.Channel) {
        val memoId = channel.path.removePrefix(Wire.CHANNEL_VOICE + "/").takeIf { it.isNotBlank() && it.none { c -> c == '/' || c == '.' } } ?: return
        val client = Wearable.getChannelClient(context)
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "$memoId.pcm")
        var total = 0L
        client.getInputStream(channel).await().use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { file.delete(); client.close(channel); return }
                    out.write(buf, 0, n)
                }
            }
        }
        client.close(channel)
        val offer = offers[memoId]
        TranscribeWorker.enqueue(context, memoId, offer?.requestId ?: memoId, offer?.sampleRateHz ?: 16_000)
    }

    /** Runs in WorkManager: transcribe, answer the watch, record any cloud cost, delete the audio. */
    suspend fun transcribe(memoId: String, requestId: String, sampleRateHz: Int) {
        val file = File(File(context.cacheDir, "voice"), "$memoId.pcm")
        if (!file.exists()) {
            link.send(TranscriptResult(requestId, memoId, error = "The recording didn't arrive on the phone. Try again."))
            return
        }
        val pcm = file.readBytes()
        val result = try {
            transcriber().transcribe(pcm, sampleRateHz)
        } finally {
            file.delete() // no audio is kept on the phone
        }
        when (result) {
            is TranscribeResult.Text -> {
                result.costMicros?.let { micros ->
                    data.usage.record(UsageEntry(requestId, clock(), ProviderId.OPENAI, "gpt-transcribe", UsageSummary(estimatedCostMicros = micros), kind = "speech"))
                }
                link.send(TranscriptResult(requestId, memoId, text = result.text, engine = result.engine))
            }
            is TranscribeResult.Failure -> link.send(TranscriptResult(requestId, memoId, error = result.message))
        }
        offers.remove(memoId)
    }

    companion object {
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}
