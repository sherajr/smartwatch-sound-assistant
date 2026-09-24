package com.peaceantz.stagescope.demo

import com.peaceantz.stagescope.audio.AudioCaptureEngine
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.audio.PcmSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Deterministic synthetic PCM source for DEMO mode. Feeds the exact same [PcmSource] interface
 * as [AudioCaptureEngine] so the whole DSP pipeline and UI run unmodified -- the microphone is
 * never touched. The signal is a fixed function of elapsed time (two steady tones plus a slowly
 * fading narrowband tone), not random, so a demo session is reproducible.
 */
class DemoSignalGenerator : PcmSource {

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    override val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var job: Job? = null

    override fun start(scope: CoroutineScope, onBlock: (FloatArray) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val config = CaptureConfig(
                sampleRate = SAMPLE_RATE,
                audioSource = -1,
                sourceLabel = "Demo (synthetic, no microphone)",
                isUnprocessedSource = false,
                effects = emptyList(),
                isDemo = true,
            )
            _status.value = CaptureStatus.Running(config)

            var sampleIndex = 0L
            val blockDurationMs = (AudioCaptureEngine.BLOCK_SIZE_SAMPLES * 1000L) / SAMPLE_RATE
            val block = FloatArray(AudioCaptureEngine.BLOCK_SIZE_SAMPLES)

            while (isActive) {
                for (i in block.indices) {
                    val t = (sampleIndex + i).toDouble() / SAMPLE_RATE
                    block[i] = generateSample(t).toFloat()
                }
                sampleIndex += block.size
                onBlock(block.copyOf())
                delay(blockDurationMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = CaptureStatus.Stopped
    }

    /** Two steady tones under a slow breathing envelope, plus a tone that fades in/out to
     * demonstrate FIND A RING's persistence heuristic on a signal known to be a musical tone. */
    private fun generateSample(t: Double): Double {
        val base = 0.30 * sin(2.0 * PI * TONE_LOW_HZ * t) + 0.15 * sin(2.0 * PI * TONE_HIGH_HZ * t)
        val envelope = 0.5 + 0.5 * sin(2.0 * PI * 0.05 * t)

        val cyclePos = t % RING_CYCLE_SECONDS
        val ringEnvelope = if (cyclePos in RING_ON_START..RING_ON_END) {
            val local = (cyclePos - RING_ON_START) / (RING_ON_END - RING_ON_START)
            0.5 - 0.5 * cos(2.0 * PI * local)
        } else {
            0.0
        }
        val ring = 0.35 * ringEnvelope * sin(2.0 * PI * RING_TONE_HZ * t)

        return (base * envelope + ring).coerceIn(-1.0, 1.0)
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val TONE_LOW_HZ = 220.0
        private const val TONE_HIGH_HZ = 1200.0
        private const val RING_TONE_HZ = 3140.0
        private const val RING_CYCLE_SECONDS = 20.0
        private const val RING_ON_START = 8.0
        private const val RING_ON_END = 16.0
    }
}
