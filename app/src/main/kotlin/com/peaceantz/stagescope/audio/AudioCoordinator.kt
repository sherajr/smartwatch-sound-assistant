package com.peaceantz.stagescope.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who is using the audio hardware (or filling the room with sound) right now. */
enum class AudioMode { IDLE, LISTENING, SPEAKING, PHONE_PLAYBACK }

enum class AudioLeaseKind(val pauseReason: PauseReason, val mode: AudioMode) {
    /** The assistant is recording the person (speech recognition or a fallback recording). */
    LISTENING(PauseReason.ASSISTANT_LISTENING, AudioMode.LISTENING),

    /** The watch is speaking a reply: its own speaker would otherwise be measured as the room. */
    SPEAKING(PauseReason.ASSISTANT_SPEAKING, AudioMode.SPEAKING),

    /** The paired phone is speaking a reply (told to us by a PlaybackNotice). */
    PHONE_PLAYBACK(PauseReason.PHONE_PLAYBACK, AudioMode.PHONE_PLAYBACK),
}

/** The part of [CaptureSession] the coordinator drives. A tiny interface so the rules are unit-testable. */
interface MeasurementControl {
    /** Pauses real-microphone measurement. Returns true only if something was actually running and is now paused. */
    fun pauseForAssistant(reason: PauseReason): Boolean

    val isPausedForAssistant: Boolean

    /** Waits until the microphone is really free (a stop is only a request). Bounded; never hangs. */
    suspend fun awaitMicReleased()

    /** Re-opens the microphone for the paused session without resetting anything. */
    fun resumeAfterAssistant()

    /** Ends a paused session cleanly when it may not resume (backgrounded, permission gone). */
    fun endPausedSession()
}

/**
 * Exclusive ownership of the microphone and the room's acoustics, one rule in one place.
 *
 * A voice interaction takes a *lease*. The first lease pauses measurement (if it was running); when
 * the last lease ends, measurement resumes -- but only if:
 *  - it was actually running when the first lease began (a stopped session is never started by this),
 *  - it is still paused (the person pressing Stop, or the keep-awake countdown ending, cancels the resume),
 *  - the app is in the foreground and
 *  - the microphone permission is still granted.
 * Otherwise the paused session is ended cleanly rather than left half-alive. Freeze, pins, meters and
 * session data are untouched throughout: nothing here resets or restarts a session, it only releases
 * and re-opens the microphone beneath it.
 *
 * Leases can carry a time limit so a lost "stopped speaking" message can never leave measurement
 * paused for good; [tick] (and every call) expires stale leases. Call on the main thread.
 */
class AudioCoordinator(
    private val nowMs: () -> Long,
    private val isForeground: () -> Boolean,
    private val hasMicPermission: () -> Boolean,
) {
    private class Lease(val kind: AudioLeaseKind, var expiresAtMs: Long?)

    private val lock = Any()
    private val leases = LinkedHashMap<Long, Lease>()
    private var nextId = 1L
    private var control: MeasurementControl? = null
    private var resumeWhenFree = false

    private val _mode = MutableStateFlow(AudioMode.IDLE)
    val mode: StateFlow<AudioMode> = _mode.asStateFlow()

    val isBusy: Boolean get() = synchronized(lock) { leases.isNotEmpty() }

    fun attach(control: MeasurementControl) = synchronized(lock) { this.control = control }

    /** The session went away (e.g. its screen was popped). Nothing can resume; forget the pending resume. */
    fun detach(control: MeasurementControl) = synchronized(lock) {
        if (this.control === control) {
            this.control = null
            resumeWhenFree = false
        }
    }

    /**
     * Takes the microphone/room for [kind]. Pauses measurement first and waits for the microphone to be
     * released, so the caller can start listening immediately. Returns a lease id for [release]/[refresh].
     */
    suspend fun acquire(kind: AudioLeaseKind, ttlMs: Long? = null): Long {
        val id: Long
        val toPause: MeasurementControl?
        synchronized(lock) {
            expireStaleLocked()
            id = nextId++
            leases[id] = Lease(kind, ttlMs?.let { nowMs() + it })
            publishLocked()
            toPause = if (!resumeWhenFree) control else null
        }
        if (toPause != null && toPause.pauseForAssistant(kind.pauseReason)) {
            synchronized(lock) { resumeWhenFree = true }
            toPause.awaitMicReleased()
        }
        return id
    }

    /** Extends a lease that has a time limit (a heartbeat from the phone while it speaks). Unknown ids are ignored. */
    fun refresh(id: Long, ttlMs: Long) = synchronized(lock) {
        leases[id]?.let { if (it.expiresAtMs != null) it.expiresAtMs = nowMs() + ttlMs }
        Unit
    }

    fun release(id: Long) {
        val finish = synchronized(lock) {
            if (leases.remove(id) == null) return
            expireStaleLocked()
            publishLocked()
            takeResumeIfFreeLocked()
        }
        finish?.let(::finishPause)
    }

    /** Expires leases whose time ran out. Safe and cheap to call often. */
    fun tick() {
        val finish = synchronized(lock) {
            expireStaleLocked()
            publishLocked()
            takeResumeIfFreeLocked()
        }
        finish?.let(::finishPause)
    }

    /**
     * The person started measuring while the room or microphone was already spoken for (the phone or
     * the watch is speaking). Pause immediately instead of measuring the assistant's own voice.
     */
    fun onMeasurementStarted() {
        val c: MeasurementControl
        val reason: PauseReason
        synchronized(lock) {
            expireStaleLocked()
            c = control ?: return
            if (leases.isEmpty() || resumeWhenFree) return
            reason = leases.values.last().kind.pauseReason
        }
        if (c.pauseForAssistant(reason)) synchronized(lock) { resumeWhenFree = true }
    }

    private fun takeResumeIfFreeLocked(): MeasurementControl? {
        if (leases.isNotEmpty() || !resumeWhenFree) return null
        resumeWhenFree = false
        return control
    }

    private fun finishPause(c: MeasurementControl) {
        // Pressing Stop (or the keep-awake countdown ending) while paused already ended the session: nothing to resume.
        if (!c.isPausedForAssistant) return
        if (isForeground() && hasMicPermission()) c.resumeAfterAssistant() else c.endPausedSession()
    }

    private fun expireStaleLocked() {
        val now = nowMs()
        val it = leases.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value.expiresAtMs
            if (e != null && now >= e) it.remove()
        }
    }

    private fun publishLocked() {
        // Priority for the label: listening > speaking > phone playback.
        val kinds = leases.values.map { it.kind }
        _mode.value = when {
            AudioLeaseKind.LISTENING in kinds -> AudioMode.LISTENING
            AudioLeaseKind.SPEAKING in kinds -> AudioMode.SPEAKING
            AudioLeaseKind.PHONE_PLAYBACK in kinds -> AudioMode.PHONE_PLAYBACK
            else -> AudioMode.IDLE
        }
    }
}
