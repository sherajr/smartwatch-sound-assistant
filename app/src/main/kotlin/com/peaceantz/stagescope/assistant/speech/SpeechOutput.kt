package com.peaceantz.stagescope.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.AudioLeaseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

sealed interface SpeakStart {
    data object Started : SpeakStart
    data class Unavailable(val reason: String) : SpeakStart
}

/**
 * Text-to-speech on the watch, **only when the person asks for it** (the Speak button, or the reply mode they
 * chose). While it speaks it holds an audio lease, so measurement is paused and the watch's own voice is never
 * measured as the room. The lease has a time limit as a safety net, and is released on every way a
 * speech can end (done, error, stop, shutdown). Call from the main thread.
 */
class SpeechOutput(
    private val context: Context,
    private val coordinator: AudioCoordinator,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    /** The utterance whose lease we hold; callbacks for any other (an older, flushed one) are ignored. */
    private var activeUtterance: String? = null
    private var lease: Long? = null

    /** Bumped by every play and stop, so a lease that finished acquiring *after* a stop is given straight back. */
    private var generation = 0

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun speak(text: String): SpeakStart {
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return SpeakStart.Unavailable("There is nothing to say.")
        val id = UUID.randomUUID().toString()
        val engine = tts
        if (engine == null) {
            pending = id to clean
            _speaking.value = true
            tts = TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS
                val p = pending
                pending = null
                if (ready && p != null) {
                    tts?.language = Locale.getDefault()
                    play(p.first, p.second)
                } else {
                    _speaking.value = false
                }
            }
            return SpeakStart.Started
        }
        if (!ready) return SpeakStart.Unavailable("This watch's text-to-speech isn't available.")
        play(id, clean)
        return SpeakStart.Started
    }

    fun stop() {
        generation++
        pending = null
        tts?.stop()
        finish()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun play(utteranceId: String, text: String) {
        val engine = tts ?: return
        if (engine.isLanguageAvailable(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) {
            _speaking.value = false
            return
        }
        val gen = ++generation
        activeUtterance = utteranceId
        _speaking.value = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { scope.launch { ended(utteranceId) } }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { scope.launch { ended(utteranceId) } }
            override fun onStop(id: String?, interrupted: Boolean) { scope.launch { ended(utteranceId) } }
        })
        scope.launch {
            // Pause measurement first (and wait for it), so not a word of the reply is measured.
            val acquired = coordinator.acquire(AudioLeaseKind.SPEAKING, ttlMs = LEASE_TTL_MS)
            if (gen != generation) {
                coordinator.release(acquired) // stopped (or replaced) while the lease was being taken
                return@launch
            }
            lease = acquired
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun ended(utteranceId: String) {
        if (utteranceId == activeUtterance) finish()
    }

    private fun finish() {
        lease?.let(coordinator::release)
        lease = null
        activeUtterance = null
        _speaking.value = false
    }

    companion object {
        const val MAX_CHARS = 2_500
        const val LEASE_TTL_MS = 3 * 60_000L
    }
}
