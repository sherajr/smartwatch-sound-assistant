package com.peaceantz.stagescope.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures real microphone audio via [AudioRecord] and hands normalized PCM blocks to a callback.
 *
 * Reliability notes (see docs/MEASUREMENTS.md for the full rationale):
 * - Tries UNPROCESSED (only if [AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED] says so),
 *   then VOICE_RECOGNITION, then MIC.
 * - Tries 48 kHz, then 44.1 kHz, then 16 kHz mono PCM16.
 * - Disables AGC/noise suppression/echo cancellation where the platform exposes them, and reports
 *   actual effect presence/disabled status rather than assuming success.
 * - Runs entirely off the main thread; releases the recorder and effects on every exit path.
 */
class AudioCaptureEngine(private val appContext: Context) : PcmSource {

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    override val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    @Volatile private var stopRequested = false

    override fun start(scope: CoroutineScope, onBlock: (FloatArray) -> Unit) {
        if (job?.isActive == true) return

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _status.value = CaptureStatus.PermissionDenied
            return
        }

        stopRequested = false
        job = scope.launch(Dispatchers.Default) { runCapture(onBlock) }
    }

    override fun stop() {
        stopRequested = true
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: IllegalStateException) {
                // Already stopped/released on another path; nothing to do.
            }
        }
        job?.cancel()
        job = null
    }

    private suspend fun runCapture(onBlock: (FloatArray) -> Unit) {
        val negotiated = try {
            negotiateAudioRecord()
        } catch (e: SecurityException) {
            _status.value = CaptureStatus.PermissionDenied
            return
        } catch (e: Exception) {
            _status.value = CaptureStatus.Error(e.message ?: "Unable to open audio input")
            return
        }

        if (negotiated == null) {
            _status.value = CaptureStatus.Unavailable("No supported microphone configuration found")
            return
        }

        val (record, baseConfig) = negotiated
        audioRecord = record
        val effects = applyEffects(record.audioSessionId)
        val config = baseConfig.copy(effects = effects)

        val shortBuffer = ShortArray(BLOCK_SIZE_SAMPLES)
        val floatBuffer = FloatArray(BLOCK_SIZE_SAMPLES)

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _status.value = CaptureStatus.Error("Recorder did not enter recording state")
                return
            }
            _status.value = CaptureStatus.Running(config)

            while (kotlin.coroutines.coroutineContext.isActive && !stopRequested) {
                val read = record.read(shortBuffer, 0, shortBuffer.size)
                when {
                    read > 0 -> {
                        for (i in 0 until read) floatBuffer[i] = shortBuffer[i] / 32768f
                        onBlock(if (read == floatBuffer.size) floatBuffer.copyOf() else floatBuffer.copyOf(read))
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR -> {
                        if (!stopRequested) _status.value = CaptureStatus.Error("Audio read error ($read)")
                        return
                    }
                    else -> Unit // read == 0: no data yet, loop again
                }
            }
        } finally {
            releaseAll()
            if (!stopRequested && _status.value is CaptureStatus.Running) {
                // Loop exited without an explicit Stop and without setting an error -- surface it.
                _status.value = CaptureStatus.Stopped
            } else if (stopRequested) {
                _status.value = CaptureStatus.Stopped
            }
        }
    }

    // Permission is verified by the caller (start()) before this is ever invoked, and every
    // caller of negotiateAudioRecord() already handles SecurityException -- lint's static
    // analysis just can't see across that call boundary.
    @android.annotation.SuppressLint("MissingPermission")
    private fun negotiateAudioRecord(): Pair<AudioRecord, CaptureConfig>? {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessedSupported =
            audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val sources = buildList {
            if (unprocessedSupported) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }

        for (source in sources) {
            for (sampleRate in SAMPLE_RATE_FALLBACKS) {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBufferSize <= 0) continue

                val bufferSizeBytes = maxOf(minBufferSize * 3, BLOCK_SIZE_SAMPLES * 2 * 4)
                val record = try {
                    AudioRecord(
                        source,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeBytes,
                    )
                } catch (e: IllegalArgumentException) {
                    null
                }

                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    val config = CaptureConfig(
                        sampleRate = record.sampleRate,
                        audioSource = source,
                        sourceLabel = sourceLabel(source),
                        isUnprocessedSource = source == MediaRecorder.AudioSource.UNPROCESSED,
                        effects = emptyList(),
                    )
                    return record to config
                }
                record?.release()
            }
        }
        return null
    }

    private fun sourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "Unprocessed"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Voice Recognition"
        MediaRecorder.AudioSource.MIC -> "Mic (default)"
        else -> "Source $source"
    }

    private fun applyEffects(sessionId: Int): List<EffectStatus> {
        val results = mutableListOf<EffectStatus>()

        if (NoiseSuppressor.isAvailable()) {
            val effect = runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()
            val disabled = effect?.let { runCatching { it.enabled = false; !it.enabled }.getOrDefault(false) } ?: false
            noiseSuppressor = effect
            results.add(EffectStatus("Noise Suppressor", presentOnDevice = true, disabledSuccessfully = disabled))
        } else {
            results.add(EffectStatus("Noise Suppressor", presentOnDevice = false, disabledSuccessfully = false))
        }

        if (AutomaticGainControl.isAvailable()) {
            val effect = runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()
            val disabled = effect?.let { runCatching { it.enabled = false; !it.enabled }.getOrDefault(false) } ?: false
            agc = effect
            results.add(EffectStatus("Automatic Gain Control", presentOnDevice = true, disabledSuccessfully = disabled))
        } else {
            results.add(EffectStatus("Automatic Gain Control", presentOnDevice = false, disabledSuccessfully = false))
        }

        if (AcousticEchoCanceler.isAvailable()) {
            val effect = runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
            val disabled = effect?.let { runCatching { it.enabled = false; !it.enabled }.getOrDefault(false) } ?: false
            aec = effect
            results.add(EffectStatus("Echo Canceler", presentOnDevice = true, disabledSuccessfully = disabled))
        } else {
            results.add(EffectStatus("Echo Canceler", presentOnDevice = false, disabledSuccessfully = false))
        }

        return results
    }

    private fun releaseAll() {
        runCatching { noiseSuppressor?.release() }
        runCatching { agc?.release() }
        runCatching { aec?.release() }
        noiseSuppressor = null
        agc = null
        aec = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        const val BLOCK_SIZE_SAMPLES = 2048
        val SAMPLE_RATE_FALLBACKS = listOf(48000, 44100, 16000)
    }
}
