package com.peaceantz.stagescope.assistant.speech

import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.AudioLeaseKind
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.TaskKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Asks whether a system screen for [method] exists on this watch. It must NOT ask about offline recognition -- that is not a prerequisite. */
fun interface DictationAvailability {
    fun isAvailable(method: InputMethod): Boolean
}

/** Proof that the one host claimed the one pending launch. [generation] ties it to the session it was issued for. */
data class LaunchTicket(val generation: Int, val method: InputMethod, val task: TaskKind)

/** What a system input screen returned, before interpretation: the host extracts both shapes and the controller picks by method. */
class RawDictationResult(val resultCode: Int, val speech: List<String>?, val typed: String?)

enum class DictationPhase {
    /** The measurement snapshot is already taken; speech is being stopped and the microphone freed. */
    PREPARING,

    /** Ready: the host should open the system screen. */
    AWAITING_LAUNCH,

    /** The host claimed the launch and is opening the screen. */
    LAUNCHING,

    /** The system screen is (or was just) open; waiting for it to report back. */
    LISTENING,

    /** It reported back; the words are being saved. */
    RESOLVING,
}

sealed interface DictationState {
    data object Idle : DictationState

    /** A system screen is being opened, is open, or has just reported. [previous] is the draft kept while replacing it. */
    data class Active(
        val request: DictationRequest,
        val method: InputMethod,
        val phase: DictationPhase,
        val previous: DictationDraft?,
    ) : DictationState

    /** Words the person has been given back, to check. Nothing has gone anywhere. [note] explains a replacement that didn't work. */
    data class Review(val draft: DictationDraft, val note: DictationFailureKind? = null, val sending: Boolean = false) : DictationState

    /** The attempt ended without words and there is no earlier draft to fall back to. */
    data class Failed(val request: DictationRequest, val method: InputMethod, val kind: DictationFailureKind) : DictationState

    val isBusy: Boolean get() = this is Active || (this is Review && sending)
}

/**
 * One dictation session at a time, as an explicit state machine, with no Android types in it so every transition is unit-tested.
 *
 *   Idle -> Active(PREPARING) -> AWAITING_LAUNCH -> LAUNCHING -> LISTENING -> RESOLVING -> Review -> (complete) -> Idle
 *                     \________ any failure/cancel _________/                    \-> Failed / back to the kept draft
 *
 * What it guarantees:
 *  - **One launch per request.** [begin] is refused while a session is active; the host must [claimLaunch] (an atomic claim) before
 *    opening the screen, so recomposition, recreation, rapid taps or a restored app cannot open a second one.
 *  - **Nothing is sent by it.** A successful result only ever leads to [DictationState.Review]; the AI is reached only by
 *    [complete], i.e. the person's explicit Send/Log.
 *  - **The pre-speech context stays intact.** The [DictationRequest] (snapshot, task, conversation, performance) rides along from
 *    [begin] to [complete] and is persisted before the screen opens, so a killed process can still deliver the result into it.
 *  - **Late and duplicate results do nothing.** Only LAUNCHING/LISTENING accept a result, once.
 *  - **The microphone lease is never stranded or released early.** It has no short timer (the screen is another process's); it is
 *    given back exactly once, at a safe point (the app in the foreground, after a short settle), or -- if the screen may still be
 *    open (abandonment) -- by ending the paused measurement rather than reopening a microphone that may be in use.
 *  - **Nothing here records audio.** The microphone belongs to the system screen.
 *
 * It is created once for the app (see `AppContainer`), outliving every Activity and ViewModel; the Activity-side host only opens
 * the screen and reports the result.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val audio: AudioCoordinator,
    private val store: DictationStore,
    private val availability: DictationAvailability,
    /** Waits (bounded) for the app to be on screen; false if it never was. */
    private val awaitForeground: suspend () -> Boolean,
    private val stopSpeaking: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Diagnostics only. Callers get categories and states, never words, audio, keys or measurements. */
    private val log: (String) -> Unit = {},
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val timing: Timing = Timing(),
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    data class Timing(
        /** How long a ready launch may wait for the host before it is given up on (the host launches within a frame when on screen). */
        val launchWaitMs: Long = 8_000,
        /** How long the system screen may stay open without reporting before the session is considered abandoned. */
        val listeningMaxMs: Long = 3 * 60_000,
        /** A pause between the screen reporting and measurement resuming, so the recognizer has really let go of the microphone. */
        val resumeSettleMs: Long = 350,
    )

    private val lock = Any()
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    /** The stored file: the unsent draft (for the "needs you" list) and the screen currently open. */
    val stored: StateFlow<DictationFile> get() = store.state

    private var generation = 0
    private var leaseId: Long? = null
    private var token: String? = null
    private var watchdog: Job? = null

    init {
        restoreIfRecent()
    }

    // ------------------------------------------------------------------------------------------ begin

    /**
     * Starts one session for [request] (already holding its pre-speech snapshot). False if one is already active -- the caller
     * just shows what is happening. [previous] is a draft to fall back to if this attempt yields nothing.
     */
    fun begin(request: DictationRequest, method: InputMethod = InputMethod.SPEECH, previous: DictationDraft? = null): Boolean {
        val gen: Int
        synchronized(lock) {
            if (_state.value.isBusy) {
                log("begin ignored: a session is already active")
                return false
            }
            gen = ++generation
            token = newToken()
            _state.value = DictationState.Active(request, method, DictationPhase.PREPARING, previous)
        }
        log("begin task=${request.task} method=$method")
        scope.launch { prepare(gen, request, method) }
        return true
    }

    private suspend fun prepare(gen: Int, request: DictationRequest, method: InputMethod) {
        if (!availability.isAvailable(method)) {
            fail(gen, DictationFailureKind.NO_HANDLER)
            return
        }
        if (audio.isHeld(AudioLeaseKind.PHONE_PLAYBACK)) {
            fail(gen, DictationFailureKind.PHONE_SPEAKING)
            return
        }
        // Pauses measurement and waits until the microphone is really free. No time limit: see the class comment.
        val lease = audio.acquire(AudioLeaseKind.LISTENING, ttlMs = null)
        val current = synchronized(lock) { (generation == gen).also { if (it) leaseId = lease } }
        if (!current) {
            audio.release(lease) // cancelled while the lease was being taken: straight back
            log("lease returned: cancelled while preparing")
            return
        }
        log("lease acquired")
        stopSpeaking()
        val myToken = synchronized(lock) { token } ?: return
        withContext(io) { store.setInFlight(InFlightDictation(request, method, clock(), myToken)) }
        val ready = synchronized(lock) {
            if (generation != gen) {
                false
            } else {
                // Armed BEFORE the phase is published: the host collects on the main dispatcher and may claim and launch from inside the
                // publication itself (claimLaunch cancels this watchdog and onLaunched arms the listening one). Arming afterwards would
                // overwrite that listening watchdog with a launch timeout and kill a session whose screen is already open.
                armWatchdog(timing.launchWaitMs) { launchTimedOut(gen) }
                setPhase(DictationPhase.AWAITING_LAUNCH)
                true
            }
        }
        if (!ready) withContext(io) { store.clearInFlight(myToken) }
    }

    // ----------------------------------------------------------------------------------- the host side

    /** The host's atomic claim on the pending launch: non-null for exactly one caller per session. */
    fun claimLaunch(): LaunchTicket? = synchronized(lock) {
        val s = _state.value as? DictationState.Active ?: return null
        if (s.phase != DictationPhase.AWAITING_LAUNCH) return null
        cancelWatchdog()
        setPhase(DictationPhase.LAUNCHING)
        log("launch claimed")
        LaunchTicket(generation, s.method, s.request.task)
    }

    /** The host opened the screen. From here the screen reports back, or the abandonment bound ends the session. */
    fun onLaunched(ticket: LaunchTicket) {
        synchronized(lock) {
            val s = _state.value as? DictationState.Active ?: return
            if (ticket.generation != generation || s.phase != DictationPhase.LAUNCHING) return
            setPhase(DictationPhase.LISTENING)
            armWatchdog(timing.listeningMaxMs) { abandon(ticket.generation) }
        }
        log("launched")
    }

    /** The host could not open the screen. The screen never ran, so the microphone is simply handed back. */
    fun onLaunchFailed(ticket: LaunchTicket, kind: DictationFailureKind) {
        log("launch failed kind=$kind")
        fail(ticket.generation, kind)
    }

    /** The host's host Activity is finishing for good: nothing can be reported to it any more. */
    fun onHostFinished() {
        val s = _state.value
        if (s is DictationState.Active && (s.phase == DictationPhase.LAUNCHING || s.phase == DictationPhase.LISTENING)) {
            log("host finished while listening")
            abandon(synchronized(lock) { generation })
        }
    }

    // -------------------------------------------------------------------------------------- the result

    /**
     * The system screen reported. Only a LAUNCHING/LISTENING session accepts it, once; anything else (a duplicate, a late arrival
     * after cancel or abandonment, a result with no session) is ignored without touching state. Never starts an AI request.
     */
    fun onResult(raw: RawDictationResult) {
        val session: DictationState.Active
        val gen: Int
        val lease: Long?
        synchronized(lock) {
            val s = _state.value
            if (s !is DictationState.Active || (s.phase != DictationPhase.LAUNCHING && s.phase != DictationPhase.LISTENING)) {
                log("result ignored: nothing was waiting (state=${s::class.simpleName})")
                return
            }
            cancelWatchdog()
            gen = ++generation
            lease = takeLease()
            session = s
            _state.value = s.copy(phase = DictationPhase.RESOLVING)
        }
        val outcome = DictationResults.interpret(session.method, raw.resultCode, if (session.method == InputMethod.SPEECH) raw.speech else listOf(raw.typed))
        log("result code=${raw.resultCode} outcome=${outcome.category()}")
        releaseAtSafePoint(lease)
        scope.launch { resolve(gen, session, outcome) }
    }

    private suspend fun resolve(gen: Int, s: DictationState.Active, outcome: DictationOutcome) {
        val myToken = synchronized(lock) { token }
        when (outcome) {
            is DictationOutcome.Text -> {
                val draft = DictationDraft(s.request, outcome.text, s.method, DictationPresentation.sourceLabel(s.method), clock())
                withContext(io) { store.commitDraft(draft) } // saves the words and forgets the open screen in one write
                val published = synchronized(lock) {
                    (generation == gen).also { if (it) _state.value = DictationState.Review(draft) }
                }
                if (!published) {
                    // Cancelled while saving: put back what was there before (or nothing).
                    withContext(io) { if (s.previous != null) store.commitDraft(s.previous) else store.clearDraft(s.request.sessionId) }
                }
            }
            DictationOutcome.Cancelled -> endWith(gen, s, myToken, null)
            is DictationOutcome.Failed -> endWith(gen, s, myToken, outcome.kind)
        }
    }

    private suspend fun endWith(gen: Int, s: DictationState.Active, myToken: String?, kind: DictationFailureKind?) {
        withContext(io) { store.clearInFlight(myToken) }
        synchronized(lock) {
            if (generation != gen) return
            _state.value = when {
                // Backing out returns to what was there: the kept draft, or nothing. A failure says so.
                kind == null -> s.previous?.let { DictationState.Review(it) } ?: DictationState.Idle
                s.previous != null -> DictationState.Review(s.previous, note = kind)
                else -> DictationState.Failed(s.request, s.method, kind)
            }
        }
    }

    // ------------------------------------------------------------------------------- failures and ends

    /** Ends the session because it could not run (as opposed to the screen reporting): the microphone goes straight back. */
    private fun fail(gen: Int, kind: DictationFailureKind) {
        val lease: Long?
        val myToken: String?
        synchronized(lock) {
            if (generation != gen) return
            val s = _state.value as? DictationState.Active ?: return
            generation++
            cancelWatchdog()
            lease = takeLease()
            myToken = token
            _state.value = if (s.previous != null) DictationState.Review(s.previous, note = kind) else DictationState.Failed(s.request, s.method, kind)
        }
        log("session failed kind=$kind")
        releaseAtSafePoint(lease)
        scope.launch(io) { store.clearInFlight(myToken) }
    }

    private fun launchTimedOut(gen: Int) {
        // Only a launch that is still waiting for its host has timed out; one that was claimed (screen open) has not.
        val s = _state.value as? DictationState.Active ?: return
        if (s.phase != DictationPhase.AWAITING_LAUNCH) return
        log("launch not claimed in time")
        fail(gen, DictationFailureKind.LAUNCH_FAILED)
    }

    /**
     * The screen never reported within the bound, or its host is gone. It may still be open and may still hold the microphone, so
     * measurement is NOT restarted: the paused session is ended, which is always safe.
     */
    private fun abandon(gen: Int) {
        val lease: Long?
        val myToken: String?
        synchronized(lock) {
            if (generation != gen) return
            val s = _state.value as? DictationState.Active ?: return
            // Only a screen that is (or may be) open can be abandoned; a result that is already being saved is not.
            if (s.phase != DictationPhase.LAUNCHING && s.phase != DictationPhase.LISTENING) return
            generation++
            cancelWatchdog()
            lease = takeLease()
            myToken = token
            _state.value = if (s.previous != null) DictationState.Review(s.previous, note = DictationFailureKind.ABANDONED)
            else DictationState.Failed(s.request, s.method, DictationFailureKind.ABANDONED)
        }
        log("session abandoned; measurement is not resumed")
        lease?.let { audio.release(it, allowResume = false) }
        scope.launch(io) { store.clearInFlight(myToken) }
    }

    /** The person cancelled from the app's own screen. Discards this utterance and returns to the kept draft, if any. */
    fun cancel() {
        val lease: Long?
        val myToken: String?
        val wasActive: Boolean
        synchronized(lock) {
            when (val s = _state.value) {
                is DictationState.Active -> {
                    generation++
                    cancelWatchdog()
                    lease = takeLease()
                    myToken = token
                    wasActive = true
                    _state.value = s.previous?.let { DictationState.Review(it) } ?: DictationState.Idle
                }
                is DictationState.Failed -> {
                    _state.value = DictationState.Idle
                    return
                }
                else -> return
            }
        }
        log("cancelled by the person")
        releaseAtSafePoint(lease)
        if (wasActive) scope.launch(io) { store.clearInFlight(myToken) }
    }

    /** Leaves the screen: an active session is cancelled; a finished attempt or an unsent draft is simply put away (the draft stays stored). */
    fun dismiss() {
        val s = _state.value
        when {
            s is DictationState.Active -> cancel()
            s is DictationState.Failed || (s is DictationState.Review && !s.sending) -> synchronized(lock) {
                val now = _state.value
                if (now is DictationState.Failed || (now is DictationState.Review && !now.sending)) _state.value = DictationState.Idle
            }
        }
    }

    // ------------------------------------------------------------------------------ review actions

    /** Dictate again. The words stay until a usable replacement arrives; backing out or a failure returns to them. */
    fun redo(method: InputMethod = InputMethod.SPEECH): Boolean {
        val s = _state.value as? DictationState.Review ?: return false
        // Dictating again is watch dictation, even if the words being replaced came from an older recording.
        val request = if (method == InputMethod.SPEECH && s.draft.request.inputOrigin == InputOrigin.VOICE_MEMO_TRANSCRIBED) {
            s.draft.request.copy(inputOrigin = InputOrigin.SPEECH_WATCH)
        } else s.draft.request
        return begin(request, method, previous = s.draft)
    }

    /** Try again after a failure, with the same context as the attempt that failed. */
    fun retry(method: InputMethod? = null): Boolean {
        val s = _state.value as? DictationState.Failed ?: return false
        return begin(s.request, method ?: s.method)
    }

    /** Opens the stored unsent draft for review. */
    fun resumeDraft(): Boolean = synchronized(lock) {
        val s = _state.value
        val draft = store.currentDraft
        if (draft == null || s.isBusy) return false
        _state.value = DictationState.Review(draft)
        true
    }

    /** Opens words that did not come from this screen (an older recording's transcript) for review. Not stored as a draft: its memo is its own copy. */
    fun openForReview(draft: DictationDraft): Boolean = synchronized(lock) {
        if (_state.value.isBusy) return false
        _state.value = DictationState.Review(draft)
        true
    }

    /** Throws the words away. Returns what was discarded (so an older recording behind it can go too). Nothing is sent. */
    suspend fun discard(): DictationDraft? {
        val draft = synchronized(lock) {
            val s = _state.value as? DictationState.Review ?: return null
            if (s.sending) return null
            _state.value = DictationState.Idle
            s.draft
        }
        withContext(io) { store.clearDraft(draft.request.sessionId) }
        return draft
    }

    /**
     * The person's explicit Send or Log: runs [action] on the reviewed draft exactly once, then forgets the draft. If [action] throws
     * the draft is still there to try again; a second tap while it runs does nothing. Returns null if there was nothing to complete.
     */
    suspend fun <T> complete(action: suspend (DictationDraft) -> T): T? {
        val draft = synchronized(lock) {
            val s = _state.value as? DictationState.Review ?: return null
            if (s.sending) return null
            _state.value = s.copy(sending = true)
            s.draft
        }
        val result = try {
            action(draft)
        } catch (t: Throwable) {
            synchronized(lock) { (_state.value as? DictationState.Review)?.let { _state.value = it.copy(sending = false) } }
            throw t
        }
        withContext(io) { store.clearDraft(draft.request.sessionId) }
        synchronized(lock) { _state.value = DictationState.Idle }
        return result
    }

    /** Owner teardown: ends any session and returns the microphone. */
    fun shutdown() {
        cancel()
        synchronized(lock) { cancelWatchdog() }
    }

    // ----------------------------------------------------------------------------------- internals

    private fun restoreIfRecent() {
        val f = store.inFlight ?: return
        val age = clock() - f.launchedAtEpochMs
        if (age < 0 || age > timing.listeningMaxMs) {
            scope.launch(io) { store.clearInFlight(f.token) }
            log("stale in-flight record cleared")
            return
        }
        // The process died while the screen was open: wait for its result as if nothing happened (there is no lease --
        // the measurement session died with the process).
        val gen = ++generation
        token = f.token
        _state.value = DictationState.Active(f.request, f.method, DictationPhase.LISTENING, store.currentDraft)
        armWatchdog(timing.listeningMaxMs - age) { abandon(gen) }
        log("restored an open dictation after a restart")
    }

    private fun setPhase(phase: DictationPhase) {
        val s = _state.value as? DictationState.Active ?: return
        _state.value = s.copy(phase = phase)
    }

    private fun takeLease(): Long? = leaseId.also { leaseId = null }

    private fun armWatchdog(afterMs: Long, onTimeout: () -> Unit) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(afterMs.coerceAtLeast(0))
            onTimeout()
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    /**
     * Gives the microphone back once the person is looking at StageScope again and the recognizer has let go. If the app never
     * comes back to the foreground, [AudioCoordinator] ends the paused session instead of resuming it. Exactly once per lease.
     */
    private fun releaseAtSafePoint(lease: Long?) {
        lease ?: return
        scope.launch {
            val foreground = awaitForeground()
            if (foreground) delay(timing.resumeSettleMs)
            audio.release(lease)
            log("lease released foreground=$foreground")
        }
    }

    private fun DictationOutcome.category(): String = when (this) {
        is DictationOutcome.Text -> "text"
        DictationOutcome.Cancelled -> "cancelled"
        is DictationOutcome.Failed -> "failed:$kind"
    }
}
