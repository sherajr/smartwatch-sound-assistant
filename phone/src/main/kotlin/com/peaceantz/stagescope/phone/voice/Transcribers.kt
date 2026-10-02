package com.peaceantz.stagescope.phone.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.peaceantz.stagescope.phone.ai.core.HttpErrors
import com.peaceantz.stagescope.phone.ai.core.KeySource
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.core.get
import com.peaceantz.stagescope.phone.ai.core.parseObject
import com.peaceantz.stagescope.phone.ai.core.str
import com.peaceantz.stagescope.shared.assistant.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

sealed interface TranscribeResult {
    data class Text(val text: String, val engine: String, val costMicros: Long? = null) : TranscribeResult
    /** [cloudMayHelp]: on-device recognition isn't possible, so the (opt-in) cloud fallback is worth offering. */
    data class Failure(val message: String, val cloudMayHelp: Boolean = false) : TranscribeResult
}

interface Transcriber {
    suspend fun transcribe(pcm16: ByteArray, sampleRateHz: Int): TranscribeResult
}

/** 16-bit mono PCM -> WAV container (44-byte header). */
object Wav {
    fun fromPcm16Mono(pcm: ByteArray, sampleRateHz: Int): ByteArray {
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRateHz)
            .putInt(sampleRateHz * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return out.array()
    }

    fun durationMs(byteCount: Long, sampleRateHz: Int): Long = byteCount * 1000L / (sampleRateHz * 2L)
}

/**
 * Phone-side recognition of a watch recording using Android's own SpeechRecognizer fed from the
 * recording (API 33+ file/stream audio source). Whether a recognizer honours a supplied audio source
 * is up to the installed recognition service, so failure here is expected on some devices and is
 * reported honestly -- never papered over with a made-up transcript.
 */
class AndroidSpeechTranscriber(private val context: Context) : Transcriber {

    override suspend fun transcribe(pcm16: ByteArray, sampleRateHz: Int): TranscribeResult = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) return@withContext TranscribeResult.Failure("This Android version can't transcribe a recording on the phone.", cloudMayHelp = true)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext TranscribeResult.Failure("Allow microphone access for StageScope on the phone (Setup → Permissions) so it can transcribe your recording.", cloudMayHelp = true)
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return@withContext TranscribeResult.Failure("No on-device speech recognition is installed on this phone.", cloudMayHelp = true)
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val writer = Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(pcm16) } }
        }.also { it.start() }

        val timeoutMs = 20_000L + Wav.durationMs(pcm16.size.toLong(), sampleRateHz) * 2
        val result: TranscribeResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<TranscribeResult> { cont ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(
                            if (text.isEmpty()) TranscribeResult.Failure("Nothing could be understood in the recording.", cloudMayHelp = false)
                            else TranscribeResult.Text(text, "On-device (Android speech recognition)"),
                        )
                    }

                    override fun onError(error: Int) {
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscribeResult.Failure("No speech was found in the recording.")
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscribeResult.Failure("Allow microphone access for StageScope on the phone, then try again.", true)
                                else -> TranscribeResult.Failure("The phone's speech recognizer couldn't transcribe the recording (code $error).", cloudMayHelp = true)
                            },
                        )
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
                }
                recognizer.startListening(intent)
            }
        } ?: TranscribeResult.Failure("The phone's speech recognizer didn't answer in time.", cloudMayHelp = true)
        runCatching { readSide.close() }
        writer.join(1000)
        result
    }
}

/**
 * Optional **opt-in** cloud transcription (Setup → Speech). Only the one requested utterance is
 * uploaded, to OpenAI's transcription endpoint using the person's own OpenAI key; its cost
 * ($0.0045 per audio minute for gpt-transcribe, price checked 2026-10-01) is recorded in the usage
 * ledger. It is never used for a conversation with another provider unless the person enabled it.
 */
class OpenAiTranscriber(
    private val http: ProviderHttp,
    private val keys: KeySource,
    baseUrl: String = "https://api.openai.com/",
    private val model: String = "gpt-transcribe",
) : Transcriber {
    private val base = baseUrl.toHttpUrl()

    override suspend fun transcribe(pcm16: ByteArray, sampleRateHz: Int): TranscribeResult {
        val key = keys.key(ProviderId.OPENAI) ?: return TranscribeResult.Failure("Cloud transcription needs your OpenAI API key (Setup → Providers).")
        val wav = Wav.fromPcm16Mono(pcm16, sampleRateHz)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "utterance.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(base.newBuilder().addPathSegments("v1/audio/transcriptions").build())
            .header("Authorization", "Bearer $key").post(body).build()
        val result = try {
            http.execute(request)
        } catch (e: com.peaceantz.stagescope.phone.ai.core.ProviderException) {
            return TranscribeResult.Failure(e.message)
        }
        if (result.status !in 200..299) {
            val err = parseObject(result.body)?.get("error", "message").str()
            return TranscribeResult.Failure(HttpErrors.map(result.status, err, null, result.retryAfterMs).message)
        }
        val text = parseObject(result.body)?.get("text").str()?.trim().orEmpty()
        if (text.isEmpty()) return TranscribeResult.Failure("The transcription came back empty.")
        val minutes = Math.ceil(Wav.durationMs(pcm16.size.toLong(), sampleRateHz) / 60_000.0)
        val costMicros = (minutes.coerceAtLeast(1.0) * 0.0045 * 1_000_000).toLong()
        return TranscribeResult.Text(text, "Cloud ($model)", costMicros)
    }
}

/** Tries on-device first; the cloud fallback only if the person enabled it and it is plausible. */
class TranscriberChain(
    private val onDevice: Transcriber,
    private val cloud: Transcriber?,
) : Transcriber {
    override suspend fun transcribe(pcm16: ByteArray, sampleRateHz: Int): TranscribeResult {
        val first = onDevice.transcribe(pcm16, sampleRateHz)
        if (first is TranscribeResult.Text) return first
        val failure = first as TranscribeResult.Failure
        if (cloud == null || !failure.cloudMayHelp) return failure
        return cloud.transcribe(pcm16, sampleRateHz).let {
            if (it is TranscribeResult.Failure) TranscribeResult.Failure("${failure.message} Cloud transcription also failed: ${it.message}") else it
        }
    }
}
