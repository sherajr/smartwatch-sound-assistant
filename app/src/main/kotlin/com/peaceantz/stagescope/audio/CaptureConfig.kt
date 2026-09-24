package com.peaceantz.stagescope.audio

/** Status of one platform audio effect we attempted to disable so it doesn't color the signal. */
data class EffectStatus(
    val name: String,
    val presentOnDevice: Boolean,
    val disabledSuccessfully: Boolean,
)

/** The audio configuration actually negotiated at Start time -- never assumed, always reported. */
data class CaptureConfig(
    val sampleRate: Int,
    val audioSource: Int,
    val sourceLabel: String,
    val isUnprocessedSource: Boolean,
    val effects: List<EffectStatus>,
    val isDemo: Boolean = false,
) {
    /** Used to invalidate a stored calibration offset when the input configuration changes. */
    fun fingerprint(): String = "$sampleRate|$audioSource"
}

/** Observable state of the shared capture engine. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data class Running(val config: CaptureConfig) : CaptureStatus
    data object Stopped : CaptureStatus
    data object PermissionDenied : CaptureStatus
    data class Unavailable(val reason: String) : CaptureStatus
    data class Error(val message: String) : CaptureStatus
}
