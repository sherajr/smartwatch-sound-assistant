package com.peaceantz.stagescope.audio

import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for the capture session, recording exactly what the coordinator asked of it. [micGate], if set, holds
 * `awaitMicReleased` open -- the moment between "paused" and "the microphone is really free".
 */
class FakeMeasurementControl(var running: Boolean = true) : MeasurementControl {
    var paused = false
    var micGate: CompletableDeferred<Unit>? = null
    val events = mutableListOf<String>()

    override fun pauseForAssistant(reason: PauseReason): Boolean {
        if (!running) return false
        running = false
        paused = true
        events += "pause:${reason.name}"
        return true
    }

    override val isPausedForAssistant: Boolean get() = paused

    override suspend fun awaitMicReleased() {
        events += "awaitReleased"
        micGate?.await()
    }

    override fun resumeAfterAssistant() { paused = false; running = true; events += "resume" }
    override fun endPausedSession() { paused = false; running = false; events += "end" }

    /** The person pressed Stop, or the keep-awake countdown ended, while paused. */
    fun sessionEndedByUser() { paused = false; running = false }

    fun count(event: String) = events.count { it == event }
}
