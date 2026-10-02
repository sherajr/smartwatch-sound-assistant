package com.peaceantz.stagescope.ui.assistant

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.assistant.AssistantAttention
import com.peaceantz.stagescope.assistant.AssistantFormatting
import com.peaceantz.stagescope.assistant.AttentionItem
import com.peaceantz.stagescope.assistant.LoggedIssue
import com.peaceantz.stagescope.assistant.OutboxEntry
import com.peaceantz.stagescope.assistant.OutboxState
import com.peaceantz.stagescope.assistant.measure.MeasurementSnapshotBuilder
import com.peaceantz.stagescope.assistant.speech.DictationDraft
import com.peaceantz.stagescope.assistant.speech.DictationPhase
import com.peaceantz.stagescope.assistant.speech.DictationPresentation
import com.peaceantz.stagescope.assistant.speech.DictationRequest
import com.peaceantz.stagescope.assistant.speech.DictationRequests
import com.peaceantz.stagescope.assistant.speech.DictationState
import com.peaceantz.stagescope.assistant.speech.InputMethod
import com.peaceantz.stagescope.assistant.speech.SpeakStart
import com.peaceantz.stagescope.dsp.SystemMonotonicClock
import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.measurement.UserContext
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.ThreadView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** What the full-screen listening flow is showing. */
sealed interface ListenUi {
    data object Idle : ListenUi

    /** The measurement is saved and the watch's input screen is being opened. */
    data object Preparing : ListenUi

    /** The watch's dictation (or text) screen is open, or has just reported back. This app's own screen is not what the person is using. */
    data class Listening(val method: InputMethod) : ListenUi

    /** The words the person gave, for them to check before anything is sent anywhere. [note] explains a replacement attempt that didn't work. */
    data class Review(
        val transcript: String,
        val source: String,
        val task: TaskKind,
        val measurementNote: String,
        val canLogLocally: Boolean,
        val note: String? = null,
        val sending: Boolean = false,
    ) : ListenUi

    data class Logged(val issue: LoggedIssue, val undone: Boolean = false) : ListenUi

    /** The attempt ended without words. What can be done next is in [text]; at most two round buttons show, the rest are chips. */
    data class Notice(val text: DictationPresentation.NoticeText, val method: InputMethod) : ListenUi

    val isBusy: Boolean get() = this is Preparing || this is Listening
}

/** Pure: what the listening screen shows for a controller state. [nowMs] only feeds the "measurement taken ... ago" line. */
fun DictationState.toListenUi(nowMs: Long): ListenUi = when (this) {
    DictationState.Idle -> ListenUi.Idle
    is DictationState.Active -> when (phase) {
        DictationPhase.PREPARING, DictationPhase.AWAITING_LAUNCH, DictationPhase.LAUNCHING -> ListenUi.Preparing
        DictationPhase.LISTENING, DictationPhase.RESOLVING -> ListenUi.Listening(method)
    }
    is DictationState.Review -> {
        val snap = draft.request.snapshot
        ListenUi.Review(
            transcript = draft.text,
            source = draft.source,
            task = draft.request.task,
            measurementNote = if (snap == null) "No measurement attached" else "Measurement attached · taken ${AssistantFormatting.ago(snap.capturedAtEpochMs, nowMs)}",
            canLogLocally = draft.request.task == TaskKind.LOG_ISSUE,
            note = DictationPresentation.keptNote(note),
            sending = sending,
        )
    }
    is DictationState.Failed -> ListenUi.Notice(DictationPresentation.notice(kind, method), method)
}

/**
 * A one-line message for the screen that caused it. Tying it to a [scope] means a failure reported on one screen can never
 * turn up on another one later (an earlier version showed "Your phone isn't reachable" on Setup after a failed Confirm).
 */
data class ScreenNotice(val scope: String, val message: String) {
    companion object {
        const val SETUP = "setup"
        const val PROVIDERS = "providers"
        const val LISTEN = "listen"
        fun forAction(actionId: String) = "action:$actionId"
    }
}

/**
 * Drives the assistant page and everything reached from it. Scoped to the "main" destination like the other
 * ViewModels, so a question survives swiping between pages. It never starts a microphone or a dictation on its own: dictation
 * begins only from an explicit tap, borrows the microphone from measurement (via [DictationController]) for just the time the
 * watch's own dictation screen is open, and ends with words the person reviews before anything is sent. The ViewModel holds no
 * Activity and no launcher -- the controller outlives it, and the Activity opens the system screen.
 */
class AssistantViewModel(private val container: AppContainer) : ViewModel() {
    private val repo get() = container.assistant
    private val dictation get() = container.dictation

    val prefs = repo.prefs
    val cache = repo.cache
    val outbox = repo.outbox
    val memos = repo.memos
    val reachable = repo.phoneReachable
    val phoneName = repo.phoneName
    val audioMode = container.audioCoordinator.mode
    val issueLedger = container.issues.state
    val speaking = container.speechOutput.speaking
    val lastActionReply = repo.lastActionReply
    val continueStates = repo.continueStates

    /** Shown in place of the dictation state for a few seconds after a local log, so Undo is there. */
    private val _logged = MutableStateFlow<ListenUi.Logged?>(null)

    val listen: StateFlow<ListenUi> = combine(dictation.state, _logged) { s, logged -> logged ?: s.toListenUi(System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, dictation.state.value.toListenUi(System.currentTimeMillis()))

    private val _notice = MutableStateFlow<ScreenNotice?>(null)

    /** The current screen-scoped message (e.g. "Phone not reachable"). It clears itself after [NOTICE_MS]. */
    val notice: StateFlow<ScreenNotice?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private var lastLoggedTimer: Job? = null

    /** Questions still on their way (not final, not waiting for a decision) -- shown as a "working" card. */
    val inFlight: StateFlow<List<OutboxEntry>> = outbox.map { f ->
        f.entries.filter { !it.state.isFinal && it.state != OutboxState.STALE }.sortedBy { it.createdAtEpochMs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The one unsent dictation kept across closing the app (null when there isn't one). */
    val unsentDraft: StateFlow<DictationDraft?> = dictation.stored.map { it.draft }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), dictation.stored.value.draft)

    val attention: StateFlow<List<AttentionItem>> = combine(cache, outbox, memos, unsentDraft) { c, o, m, d ->
        AssistantAttention.build(c.threads, o.entries, m.memos, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providerChip: StateFlow<String> = cache.map { AssistantFormatting.providerChip(it.providers) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Providers")

    val showChip: StateFlow<String> = combine(cache, prefs) { c, p -> AssistantFormatting.showChip(c.shows, p.performanceOverrideId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "No show selected")

    init {
        // A fresh look at the phone whenever this is created (the app was just opened).
        viewModelScope.launch { runCatching { repo.refreshReachability() } }
        // Replies are spoken automatically only when the person has turned theatre mode off AND chosen voice replies.
        viewModelScope.launch { watchForAnswers() }
        // The phone's answer to a Confirm/Cancel tap is shown for a few seconds, then forgotten -- not replayed the next
        // time that item is opened. It also replaces the "waiting for your phone" line.
        viewModelScope.launch {
            repo.lastActionReply.collectLatest { reply ->
                if (reply == null) return@collectLatest
                consumeNotice(ScreenNotice.forAction(reply.actionId))
                delay(REPLY_MS)
                repo.consumeActionReply(reply)
            }
        }
    }

    // --------------------------------------------------------------------------------- helpers

    fun thread(conversationId: String): ThreadView? = cache.value.threads.firstOrNull { it.conversationId == conversationId }

    fun card(conversationId: String, actionId: String): ActionCard? = thread(conversationId)?.actions?.firstOrNull { it.actionId == actionId }

    fun latestThread(): ThreadView? = cache.value.threads.firstOrNull()

    fun effectivePerformanceId(): String? = prefs.value.performanceOverrideId ?: cache.value.shows?.selectedPerformanceId

    private fun userContext(): UserContext? {
        val shows = cache.value.shows ?: return null
        val perf = shows.performances.firstOrNull { it.id == effectivePerformanceId() }
        val prod = shows.productions.firstOrNull { it.id == (perf?.productionId ?: shows.selectedProductionId) }
        if (perf == null && prod == null) return null
        return UserContext(productionName = prod?.name, venue = prod?.venue, performanceLabel = perf?.label)
    }

    /**
     * The measurement the person is looking at, as an immutable snapshot taken *now*. Called before the microphone
     * is touched, so evidence from before the dictation keeps the time it was captured.
     */
    private fun snapshotNow(origin: SnapshotOrigin): MeasurementContext? {
        val hub = container.measurementHub
        val elapsed = SystemClock.elapsedRealtime()
        val ctx = MeasurementSnapshotBuilder.build(
            MeasurementSnapshotBuilder.Inputs(
                snapshotId = UUID.randomUUID().toString(), nowEpochMs = System.currentTimeMillis(), nowElapsedMs = elapsed,
                nowMonotonicMs = SystemMonotonicClock.nowMillis(), origin = origin, analyzer = hub.analyzer, ring = hub.ring,
                runState = hub.runState, errorMessage = hub.errorMessage, deviceModel = Build.MODEL, userContext = userContext(),
                history = hub.history.snapshot(elapsed),
            ),
        )
        return if (MeasurementSnapshotBuilder.hasEvidence(ctx)) MeasurementSnapshotBuilder.fitToBudget(ctx) else null
    }

    /** New questions continue a conversation asked about in the last ten minutes; tasks always start fresh. */
    private fun conversationFor(task: TaskKind): String {
        if (task != TaskKind.FREE_CHAT && task != TaskKind.ANALYZE_SOUND) return UUID.randomUUID().toString()
        val last = repo.outbox.value.entries.lastOrNull { it.request.taskKind == TaskKind.FREE_CHAT || it.request.taskKind == TaskKind.ANALYZE_SOUND }
        return if (last != null && System.currentTimeMillis() - last.createdAtEpochMs < CONVERSATION_WINDOW_MS) last.conversationId else UUID.randomUUID().toString()
    }

    // ---------------------------------------------------------------------------------- dictation

    /**
     * Starts one dictation. The measurement snapshot (if [attach]) and the performance are captured first -- before the watch's
     * dictation screen opens and before the microphone is borrowed -- and stay with the question until it is sent or discarded.
     * [conversationId] / [editsActionId] continue an existing conversation (e.g. a spoken edit of a draft). Rapid taps are harmless:
     * while a session is active this does nothing (the screen just shows what is happening).
     */
    fun startListening(
        task: TaskKind, origin: SnapshotOrigin, attach: Boolean = true,
        conversationId: String? = null, editsActionId: String? = null,
    ) {
        if (dictation.state.value.isBusy) return
        // Taken before anything else happens, so what is attached is what was on screen when the person asked.
        val snapshot = if (attach) snapshotNow(origin) else null
        dictation.begin(
            DictationRequest(
                sessionId = UUID.randomUUID().toString(), task = task, origin = origin, snapshot = snapshot,
                conversationId = conversationId ?: conversationFor(task), editsActionId = editsActionId,
                performanceId = effectivePerformanceId(), inputOrigin = InputOrigin.SPEECH_WATCH, startedAtEpochMs = System.currentTimeMillis(),
                measurementWasRunning = container.measurementHub.runState == RunState.RUNNING,
            ),
        )
    }

    /** The person cancelled from this app's own screen. Keeps nothing from this utterance (an earlier draft, if any, stays). */
    fun cancelListening() = dictation.cancel()

    /** Leaves the flow: cancels an active session, or just puts a finished attempt / an unsent draft away (the draft stays stored). */
    fun closeListening() {
        _logged.value = null
        dictation.dismiss()
    }

    /** Dictate again. The words under review stay until a usable replacement arrives. */
    fun redo() { dictation.redo(InputMethod.SPEECH) }

    /** "Try again" after an attempt that gave no words: the same question, the same pre-speech measurement. */
    fun retry() { dictation.retry(InputMethod.SPEECH) }

    /** Type instead of dictating -- from a failed attempt, or to replace the words under review with typed ones. */
    fun typeInstead() {
        if (dictation.state.value is DictationState.Review) dictation.redo(InputMethod.KEYBOARD) else dictation.retry(InputMethod.KEYBOARD)
    }

    /** Opens the unsent dictation kept from earlier (it survives closing the app). */
    fun resumeDraft(): Boolean = dictation.resumeDraft()

    /** Opens a transcript that an older version's phone transcription made, as text for review. The snapshot taken before that recording is preserved. */
    fun reviewMemo(memoId: String): Boolean {
        val m = memos.value.memos.firstOrNull { it.memoId == memoId } ?: return false
        val text = m.transcript ?: return false
        val request = DictationRequest(
            sessionId = m.requestId, task = m.taskKind, origin = m.snapshot?.origin ?: SnapshotOrigin.ASSISTANT, snapshot = m.snapshot,
            conversationId = conversationFor(m.taskKind), performanceId = m.performanceId, inputOrigin = InputOrigin.VOICE_MEMO_TRANSCRIBED,
            startedAtEpochMs = m.createdAtEpochMs, legacyMemoId = m.memoId,
        )
        return dictation.openForReview(
            DictationDraft(request, text, InputMethod.SPEECH, m.engine?.let { "Older recording · $it" } ?: "Older recording", System.currentTimeMillis()),
        )
    }

    fun deleteMemo(memoId: String) { viewModelScope.launch { repo.deleteMemo(memoId) } }

    /** Throws away the words under review (and the older recording they came from, if any). Nothing is sent. */
    fun discardReview() {
        viewModelScope.launch { dictation.discard()?.request?.legacyMemoId?.let { repo.deleteMemo(it) } }
    }

    /**
     * Sends the reviewed words (and the pre-speech measurement) to the assistant: the person's explicit Send. The question is
     * saved to the outbox first, so closing the app or an unreachable phone loses nothing; a second tap while it runs does nothing.
     */
    fun send() {
        viewModelScope.launch {
            dictation.complete { d ->
                repo.submit(DictationRequests.toAssistantRequest(d, repo.prefs.value.outputMode, System.currentTimeMillis()))
                d.request.legacyMemoId?.let { repo.deleteMemo(it) }
            }
        }
    }

    /** Logs the issue on the watch right now -- no phone, network or AI needed -- with a read-back and Undo. */
    fun logLocally() {
        viewModelScope.launch {
            dictation.complete { d ->
                val shows = cache.value.shows
                val perfId = d.request.performanceId
                val prodId = shows?.performances?.firstOrNull { it.id == perfId }?.productionId ?: shows?.selectedProductionId
                val logged = container.issues.log(d.text.trim(), prodId, perfId)
                d.request.legacyMemoId?.let { repo.deleteMemo(it) }
                // Shown before the draft is cleared, so the screen goes straight from the review to the read-back.
                _logged.value = ListenUi.Logged(logged)
                lastLoggedTimer?.cancel()
                lastLoggedTimer = viewModelScope.launch {
                    delay(UNDO_WINDOW_MS)
                    if (_logged.value?.issue?.issueId == logged.issueId) _logged.value = null
                }
            }
        }
    }

    fun undoLogged() {
        val logged = _logged.value?.issue ?: return
        viewModelScope.launch {
            container.issues.undo(logged.issueId)
            _logged.value = ListenUi.Logged(logged, undone = true)
        }
    }

    /** "Continue on phone" from a failed dictation: asks Wear OS to open StageScope there, where the question can be typed. */
    fun continueOnPhoneToType() {
        viewModelScope.launch {
            val requested = container.phoneHandoff.requestOpen(null, null)
            postNotice(
                ScreenNotice.LISTEN,
                if (requested) "Asked your phone to open StageScope. It may need to be unlocked." else "Couldn't reach your phone. Open StageScope there yourself.",
            )
        }
    }

    // ----------------------------------------------------------------------------- issue log edits

    fun resolveIssue(issueId: String, certainty: com.peaceantz.stagescope.shared.issues.ResolutionCertainty) {
        viewModelScope.launch { container.issues.resolve(issueId, certainty) }
    }

    fun reopenIssue(issueId: String) { viewModelScope.launch { container.issues.reopen(issueId) } }

    /** Deleting leaves a tombstone, so the deletion syncs to the phone instead of the phone's copy bringing it back. */
    fun deleteIssue(issueId: String) { viewModelScope.launch { container.issues.undo(issueId) } }

    // --------------------------------------------------------------------------------- questions

    fun cancelQuestion(requestId: String) { viewModelScope.launch { repo.cancel(requestId) } }
    fun retryQuestion(requestId: String) { viewModelScope.launch { repo.retry(requestId) } }
    fun sendStale(requestId: String) { viewModelScope.launch { repo.sendStale(requestId) } }
    fun discardQuestion(requestId: String) { viewModelScope.launch { repo.discard(requestId) } }

    // ------------------------------------------------------------------------------------ actions

    /**
     * Sends a decision (confirm, cancel, mark done...) to the phone. The phone's [com.peaceantz.stagescope.shared.protocol.ActionReply]
     * is the only thing that says what happened; until it arrives the screen says it is waiting, and if it never does it says so
     * -- a tap must never look like it did nothing, and never look like it worked when the phone hasn't said so.
     */
    fun sendAction(actionId: String, command: ActionCommandKind, card: ActionCard? = null) {
        val scope = ScreenNotice.forAction(actionId)
        viewModelScope.launch {
            if (!repo.actionCommand(actionId, command, card?.revision, card?.contentHash)) {
                postNotice(scope, "Your phone isn't reachable. Try again when it's nearby.")
                return@launch
            }
            postNotice(scope, WAITING_FOR_PHONE)
            delay(ACTION_ANSWER_WAIT_MS)
            if (_notice.value == ScreenNotice(scope, WAITING_FOR_PHONE)) {
                postNotice(scope, "No answer from your phone yet. It may be out of range — check it before trying again.")
            }
        }
    }

    private fun postNotice(scope: String, message: String) {
        val notice = ScreenNotice(scope, message)
        _notice.value = notice
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            if (_notice.value === notice) _notice.value = null
        }
    }

    fun consumeNotice(scope: String) { if (_notice.value?.scope == scope) _notice.value = null }

    /** "Continue on phone": the phone is asked to save/open the exact item; the watch shows only what the phone answers. */
    fun continueOnPhone(conversationId: String?, actionId: String?, onStarted: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.continueOnPhone(conversationId, actionId)
            onStarted(id)
            // Also ask Wear OS to open it directly (needs the phone unlocked; the notification is the fallback).
            container.phoneHandoff.requestOpen(conversationId, actionId)
            delay(CONTINUE_ANSWER_WAIT_MS)
            repo.markContinueNoAnswer(id)
        }
    }

    /** Asks Wear OS to bring StageScope to the front on the phone (for setup). It's a request: the phone may need to be unlocked. */
    fun openPhoneApp() {
        viewModelScope.launch {
            val requested = container.phoneHandoff.requestOpen(null, null)
            postNotice(
                ScreenNotice.SETUP,
                if (requested) "Asked your phone to open StageScope. It may need to be unlocked." else "Couldn't reach your phone. Open StageScope there yourself.",
            )
        }
    }

    // ------------------------------------------------------------------------------------ speaking

    sealed interface SpeakDecision {
        data object Started : SpeakDecision
        data object NeedsConfirm : SpeakDecision
        data class Unavailable(val reason: String) : SpeakDecision
    }

    /** True if headphones/earbuds are connected -- i.e. speaking won't be heard by the room. */
    fun privateAudioConnected(): Boolean {
        val am = container.appContext.getSystemService(AudioManager::class.java) ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID, 26 /* TYPE_BLE_HEADSET */ -> true
                else -> false
            }
        }
    }

    /** The Speak button. In theatre mode, with nothing private connected, it asks first: a wrist speaker is audible to the room. */
    fun speak(view: ThreadView, confirmed: Boolean = false): SpeakDecision {
        val text = AssistantFormatting.spokenSummary(view)
        if (text.isBlank()) return SpeakDecision.Unavailable("There is nothing to say.")
        if (repo.prefs.value.theatreMode && !privateAudioConnected() && !confirmed) return SpeakDecision.NeedsConfirm
        return when (val r = container.speechOutput.speak(text)) {
            SpeakStart.Started -> SpeakDecision.Started
            is SpeakStart.Unavailable -> SpeakDecision.Unavailable(r.reason)
        }
    }

    fun stopSpeaking() = container.speechOutput.stop()

    /**
     * Speaks a *new* answer without being asked only when theatre mode is off, the person chose voice replies, and the app is
     * on screen. Everything else stays silent until Speak is tapped.
     */
    private suspend fun watchForAnswers() {
        val seen = HashSet<String>()
        outbox.value.entries.filter { it.state == OutboxState.RESULT_READY }.forEach { seen += it.requestId }
        outbox.collect { f ->
            for (e in f.entries) {
                if (e.state != OutboxState.RESULT_READY || !seen.add(e.requestId)) continue
                val p = repo.prefs.value
                if (p.theatreMode || p.outputMode == ReplyMode.TEXT) continue
                if (!container.isAppForeground()) continue
                thread(e.conversationId)?.let { v -> container.speechOutput.speak(AssistantFormatting.spokenSummary(v)) }
            }
        }
    }

    // ------------------------------------------------------------------------------------ settings

    fun setOutputMode(mode: ReplyMode) { viewModelScope.launch { repo.updatePrefs { it.copy(outputMode = mode) } } }
    fun setTheatreMode(on: Boolean) { viewModelScope.launch { repo.updatePrefs { it.copy(theatreMode = on) } } }
    fun setHaptics(on: Boolean) { viewModelScope.launch { repo.updatePrefs { it.copy(hapticsEnabled = on) } } }
    fun setPerformance(id: String?) { viewModelScope.launch { repo.updatePrefs { it.copy(performanceOverrideId = id) } } }

    fun selectProvider(provider: com.peaceantz.stagescope.shared.assistant.ProviderId, thorough: Boolean? = null, webSearch: Boolean? = null) {
        viewModelScope.launch {
            if (!repo.selectProvider(provider, null, thorough, webSearch)) postNotice(ScreenNotice.PROVIDERS, "Your phone isn't reachable, so nothing was changed.")
        }
    }

    /** "Refresh from phone": the Setup screen says whether the phone was reachable at all, so the tap never looks like nothing happened. */
    fun refresh() {
        viewModelScope.launch {
            val reachable = repo.refreshReachability()
            if (reachable) repo.requestSync("manual refresh")
            repo.flushOutbox()
            postNotice(ScreenNotice.SETUP, if (reachable) "Asked your phone for the latest." else "Your phone isn't reachable right now.")
        }
    }

    companion object {
        private const val CONVERSATION_WINDOW_MS = 10 * 60_000L
        private const val UNDO_WINDOW_MS = 10_000L
        private const val CONTINUE_ANSWER_WAIT_MS = 10_000L
        private const val NOTICE_MS = 10_000L
        private const val REPLY_MS = 10_000L
        private const val ACTION_ANSWER_WAIT_MS = 8_000L
        private const val WAITING_FOR_PHONE = "Sent to your phone. Waiting for its answer…"
    }
}
