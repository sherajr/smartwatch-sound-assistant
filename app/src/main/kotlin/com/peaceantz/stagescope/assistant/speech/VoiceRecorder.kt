package com.peaceantz.stagescope.assistant.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

sealed interface RecordResult {
    class Recorded(val pcm: ByteArray, val sampleRateHz: Int, val durationMs: Long) : RecordResult
    data object NothingHeard : RecordResult
    data object NeedsPermission : RecordResult
    data class Failed(val message: String) : RecordResult
}

/**
 * A short, bounded recording (16 kHz mono PCM16) used only when the watch can't transcribe speech itself, or
 * for an offline voice memo. It records one utterance and stops: at [maxMs], when [stop] is called, or when the
 * coroutine is cancelled -- never a continuous or background recording, and the microphone is released on every
 * exit path. The audio goes to the paired phone for transcription and is deleted once that's done.
 */
class VoiceRecorder(private val context: Context) {
    @Volatile private var stopRequested = false

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission") // checked just above; a SecurityException is also handled
    suspend fun record(maxMs: Long): RecordResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext RecordResult.NeedsPermission
        stopRequested = false
        _elapsedMs.value = 0
        val bounded = maxMs.coerceIn(2_000L, MAX_RECORD_MS)
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext RecordResult.Failed("This watch can't record audio right now.")
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (e: SecurityException) {
            return@withContext RecordResult.NeedsPermission
        } catch (e: IllegalArgumentException) {
            return@withContext RecordResult.Failed("The microphone couldn't be opened.")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext RecordResult.Failed("The microphone couldn't be opened.")
        }
        val out = ByteArrayOutputStream()
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var peak = 0
        val startedAt = System.nanoTime()
        try {
            record.startRecording()
            val bytes = ByteArray(buffer.size * 2)
            while (isActive && !stopRequested) {
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                _elapsedMs.value = elapsed
                if (elapsed >= bounded) break
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) return@withContext RecordResult.Failed("The microphone stopped unexpectedly.")
                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    peak = maxOf(peak, abs(s))
                    bytes[2 * i] = (s and 0xff).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                out.write(bytes, 0, n * 2)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        val pcm = out.toByteArray()
        val durationMs = pcm.size * 1000L / (SAMPLE_RATE * 2L)
        // Digital silence or near it: nothing worth sending anywhere.
        if (durationMs < MIN_RECORD_MS || peak < SILENCE_PEAK) RecordResult.NothingHeard else RecordResult.Recorded(pcm, SAMPLE_RATE, durationMs)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_RECORD_MS = 30_000L
        const val MIN_RECORD_MS = 600L
        private const val SILENCE_PEAK = 200
    }
}
