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

/** Why measurement was paused: the microphone (or the room's acoustics) belongs to the assistant for a moment. */
enum class PauseReason(val label: String) {
    ASSISTANT_LISTENING("Paused — listening to you"),
    ASSISTANT_SPEAKING("Paused — the watch is speaking"),
    PHONE_PLAYBACK("Paused — your phone is speaking"),
}

/** Observable state of the shared capture engine. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data class Running(val config: CaptureConfig) : CaptureStatus

    /**
     * The microphone was released on purpose for a voice interaction and will be re-opened when it
     * ends (only if the session was running, the app is in the foreground, the microphone permission
     * is still granted, and the keep-awake countdown hasn't ended). This is NOT a stop: the session,
     * its listeners, accumulated meters, Freeze state and the Ring bank all stay exactly as they were.
     * [config] is the last negotiated input configuration (null if the session was still starting).
     */
    data class Paused(val config: CaptureConfig?, val reason: PauseReason) : CaptureStatus

    data object Stopped : CaptureStatus
    data object PermissionDenied : CaptureStatus
    data class Unavailable(val reason: String) : CaptureStatus
    data class Error(val message: String) : CaptureStatus
}
