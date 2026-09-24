package com.peaceantz.stagescope.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A source of normalized mono PCM blocks (samples roughly in -1..1), fed into the same DSP
 * pipeline whether it comes from the real microphone ([AudioCaptureEngine]) or a deterministic
 * synthetic signal (`DemoSignalGenerator`).
 */
interface PcmSource {
    val status: StateFlow<CaptureStatus>

    /** Starts producing blocks on [scope], delivering each to [onBlock]. No-op if already running. */
    fun start(scope: CoroutineScope, onBlock: (FloatArray) -> Unit)

    /** Stops and releases any underlying resources. Safe to call when not running. */
    fun stop()
}
