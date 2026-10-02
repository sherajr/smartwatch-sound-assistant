package com.peaceantz.stagescope.assistant.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface ListenResult {
    data class Text(val text: String, val engine: String) : ListenResult

    /** The recognizer ran but heard nothing it could use. */
    data object NoSpeech : ListenResult

    data object Cancelled : ListenResult
    data object NeedsPermission : ListenResult

    /** No usable on-device recognizer: the caller may fall back to recording and asking the phone to transcribe. */
    data class Unavailable(val reason: String) : ListenResult

    data class Failed(val message: String) : ListenResult
}

/**
 * Push-to-talk speech recognition on the watch, **on-device only**. It never silently falls back to a
 * cloud recognizer: if no on-device recognizer exists the result is [ListenResult.Unavailable] and the
 * assistant offers the recording-to-phone route instead (which is opt-in for any cloud use, with its cost shown).
 *
 * One bounded utterance per call: it stops at the first long pause, at [maxMs], or when [finish]/[cancel]
 * is called -- there is no always-on listening. Must be called from the main thread's coroutine context.
 */
class SpeechInput(private val context: Context) {
    private val _partial = MutableStateFlow<String?>(null)

    /** Words recognized so far in the current utterance, for live feedback. */
    val partial: StateFlow<String?> = _partial.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var cancelled = false
    private var finishing: (() -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The on-device recognizer API arrived with API 31 (in practice Wear OS 4+); older watches use the recording route. */
    fun isOnDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** The person tapped Done: stop listening and use what was heard. */
    fun finish() { finishing?.invoke() }

    /** The person tapped Cancel: discard everything. */
    fun cancel() {
        cancelled = true
        runCatching { recognizer?.cancel() }
    }

    suspend fun listen(maxMs: Long, scope: CoroutineScope): ListenResult = withContext(Dispatchers.Main.immediate) {
        if (!hasPermission()) return@withContext ListenResult.NeedsPermission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isOnDeviceAvailable()) {
            return@withContext ListenResult.Unavailable("This watch has no on-device speech recognition.")
        }
        _partial.value = null
        cancelled = false
        val bounded = maxMs.coerceIn(3_000L, 30_000L)

        suspendCancellableCoroutine { cont ->
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            var delivered = false
            fun deliver(result: ListenResult) {
                if (delivered) return
                delivered = true
                finishing = null
                runCatching { engine.destroy() }
                recognizer = null
                if (cont.isActive) cont.resume(result)
            }
            val watchdog = scope.launch {
                delay(bounded)
                runCatching { engine.stopListening() } // the bound: ask for whatever was heard
                delay(WATCHDOG_GRACE_MS)
                deliver(_partial.value?.takeIf { it.isNotBlank() }?.let { ListenResult.Text(it, ENGINE_LABEL) } ?: ListenResult.Failed("Listening timed out."))
            }
            finishing = { runCatching { engine.stopListening() } }
            cont.invokeOnCancellation {
                watchdog.cancel()
                finishing = null
                runCatching { engine.destroy() }
                recognizer = null
            }
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    watchdog.cancel()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    deliver(if (cancelled) ListenResult.Cancelled else if (text.isEmpty()) ListenResult.NoSpeech else ListenResult.Text(text, ENGINE_LABEL))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                }

                override fun onError(error: Int) {
                    watchdog.cancel()
                    deliver(if (cancelled) ListenResult.Cancelled else mapError(error))
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            engine.startListening(intent)
        }
    }

    companion object {
        const val ENGINE_LABEL = "On-device (Android speech recognition)"
        private const val WATCHDOG_GRACE_MS = 4_000L

        fun mapError(error: Int): ListenResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenResult.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenResult.NeedsPermission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenResult.Failed("The microphone is busy. Try again in a moment.")
            // API 31+: LANGUAGE_NOT_SUPPORTED (12) / LANGUAGE_UNAVAILABLE (13): the offline pack isn't there.
            12, 13 -> ListenResult.Unavailable("The offline speech pack for your language isn't installed.")
            SpeechRecognizer.ERROR_AUDIO -> ListenResult.Failed("The microphone couldn't be opened.")
            else -> ListenResult.Failed("Speech recognition failed (code $error).")
        }
    }
}
