package com.peaceantz.stagescope.phone.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.peaceantz.stagescope.phone.link.WatchLink
import com.peaceantz.stagescope.shared.protocol.PlaybackNotice
import com.peaceantz.stagescope.shared.protocol.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

sealed interface SpeakResult {
    data object Started : SpeakResult
    data class Unavailable(val reason: String) : SpeakResult
}

/**
 * Text-to-speech on the phone, only when the person taps Speak (theatre mode: silence is the
 * default). While it plays, the watch is told ([PlaybackNotice]) so it can pause measurement -- the
 * phone's loudspeaker would otherwise feed ring detection and the measurement history. A message can
 * be lost, so playback is *re-announced* every [HEARTBEAT_MS]; the watch treats 30 s without one as
 * "the phone stopped" and resumes on its own.
 */
class PhoneSpeech(
    private val context: Context,
    private val link: WatchLink,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    private var heartbeat: Job? = null

    fun speak(text: String): SpeakResult {
        val utterance = UUID.randomUUID().toString()
        val engine = tts
        if (engine == null) {
            pending = utterance to text
            tts = TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    pending?.let { (id, t) -> play(id, t) }
                }
                pending = null
            }
            return SpeakResult.Started
        }
        if (!ready) return SpeakResult.Unavailable("This phone's text-to-speech engine isn't available.")
        play(utterance, text)
        return SpeakResult.Started
    }

    fun stop() {
        tts?.stop()
    }

    private fun play(utteranceId: String, text: String) {
        val engine = tts ?: return
        if (engine.isLanguageAvailable(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { began(utteranceId) }
            override fun onDone(id: String?) { ended(utteranceId) }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { ended(utteranceId) }
            override fun onStop(id: String?, interrupted: Boolean) { ended(utteranceId) }
        })
        // Announce before the first sound so the watch has already released the microphone.
        scope.launch { link.send(PlaybackNotice(utteranceId, PlaybackState.STARTED)) }
        engine.speak(text.take(MAX_CHARS), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun began(utteranceId: String) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                link.send(PlaybackNotice(utteranceId, PlaybackState.STARTED))
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun ended(utteranceId: String) {
        heartbeat?.cancel()
        heartbeat = null
        scope.launch { link.send(PlaybackNotice(utteranceId, PlaybackState.STOPPED)) }
    }

    fun shutdown() {
        heartbeat?.cancel()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        const val MAX_CHARS = 3_500
        const val HEARTBEAT_MS = 10_000L
    }
}
