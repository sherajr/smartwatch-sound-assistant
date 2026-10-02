package com.peaceantz.stagescope.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who is using the audio hardware (or filling the room with sound) right now. */
enum class AudioMode { IDLE, LISTENING, SPEAKING, PHONE_PLAYBACK }

enum class AudioLeaseKind(val pauseReason: PauseReason, val mode: AudioMode) {
    /**
     * A system input screen (the watch's dictation or keyboard screen) is open and may be using the microphone. StageScope does not
     * record anything itself; this only keeps measurement and speech out of the way until that screen is finished with.
     */
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
 *
 * A lease held for a *system screen that someone else's process runs* (dictation) deliberately has **no** time limit: a short
 * limit would resume measurement while that screen may still own the microphone. Whoever holds it bounds it instead
 * (see `DictationController`), and gives it up with `release(id, allowResume = false)` when it cannot know the screen is gone,
 * which ends the paused session rather than reopening a microphone that may be in use.
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

    /** Set when a lease was given up with `allowResume = false`: the paused session is ended, not resumed, once the last lease goes. */
    private var resumeVetoed = false

    private val _mode = MutableStateFlow(AudioMode.IDLE)
    val mode: StateFlow<AudioMode> = _mode.asStateFlow()

    val isBusy: Boolean get() = synchronized(lock) { leases.isNotEmpty() }

    /** True while any lease of [kind] is held (and has not expired). */
    fun isHeld(kind: AudioLeaseKind): Boolean = synchronized(lock) {
        expireStaleLocked()
        leases.values.any { it.kind == kind }
    }

    fun attach(control: MeasurementControl) = synchronized(lock) { this.control = control }

    /** The session went away (e.g. its screen was popped). Nothing can resume; forget the pending resume. */
    fun detach(control: MeasurementControl) = synchronized(lock) {
        if (this.control === control) {
            this.control = null
            resumeWhenFree = false
            resumeVetoed = false
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
            try {
                toPause.awaitMicReleased()
            } catch (e: CancellationException) {
                // The caller went away before it ever got the lease id back, so nobody else can release it: do it here, or a lease
                // with no time limit would leave measurement paused for good.
                release(id)
                throw e
            }
        }
        return id
    }

    /** Extends a lease that has a time limit (a heartbeat from the phone while it speaks). Unknown ids are ignored. */
    fun refresh(id: Long, ttlMs: Long) = synchronized(lock) {
        leases[id]?.let { if (it.expiresAtMs != null) it.expiresAtMs = nowMs() + ttlMs }
        Unit
    }

    /**
     * Gives the lease back. By default measurement then resumes if all the usual conditions hold. With [allowResume] = false the
     * caller is saying "I can't be sure the microphone is free" -- the paused session is then **ended** (never resumed), once
     * the last lease is gone. Releasing a lease twice, or an unknown id, does nothing.
     */
    fun release(id: Long, allowResume: Boolean = true) {
        val finish = synchronized(lock) {
            if (leases.remove(id) == null) return
            if (!allowResume && resumeWhenFree) resumeVetoed = true
            expireStaleLocked()
            publishLocked()
            takeResumeIfFreeLocked()
        }
        finish?.let { (c, vetoed) -> finishPause(c, vetoed) }
    }

    /** Expires leases whose time ran out. Safe and cheap to call often. */
    fun tick() {
        val finish = synchronized(lock) {
            expireStaleLocked()
            publishLocked()
            takeResumeIfFreeLocked()
        }
        finish?.let { (c, vetoed) -> finishPause(c, vetoed) }
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

    /** The session to finish (and whether resuming it was ruled out), once the last lease is gone and something was paused. */
    private fun takeResumeIfFreeLocked(): Pair<MeasurementControl, Boolean>? {
        if (leases.isNotEmpty() || !resumeWhenFree) return null
        resumeWhenFree = false
        val vetoed = resumeVetoed
        resumeVetoed = false
        return control?.let { it to vetoed }
    }

    private fun finishPause(c: MeasurementControl, resumeVetoed: Boolean) {
        // Pressing Stop (or the keep-awake countdown ending) while paused already ended the session: nothing to resume.
        if (!c.isPausedForAssistant) return
        if (!resumeVetoed && isForeground() && hasMicPermission()) c.resumeAfterAssistant() else c.endPausedSession()
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
