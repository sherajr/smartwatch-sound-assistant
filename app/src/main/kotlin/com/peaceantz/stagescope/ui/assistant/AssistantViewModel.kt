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
import com.peaceantz.stagescope.assistant.MemoState
import com.peaceantz.stagescope.assistant.OutboxEntry
import com.peaceantz.stagescope.assistant.OutboxState
import com.peaceantz.stagescope.assistant.VoiceMemo
import com.peaceantz.stagescope.assistant.measure.MeasurementSnapshotBuilder
import com.peaceantz.stagescope.assistant.speech.ListenResult
import com.peaceantz.stagescope.assistant.speech.RecordResult
import com.peaceantz.stagescope.assistant.speech.SpeakStart
import com.peaceantz.stagescope.audio.AudioLeaseKind
import com.peaceantz.stagescope.dsp.SystemMonotonicClock
import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.measurement.UserContext
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.VoicePurpose
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** What the full-screen listening flow is showing. */
sealed interface ListenUi {
    data object Idle : ListenUi
    data object Preparing : ListenUi
    data class Listening(val partial: String?, val usingRecorder: Boolean) : ListenUi
    data object Transcribing : ListenUi

    /** The words the assistant heard, for the person to check before anything is sent anywhere. */
    data class Review(
        val transcript: String,
        val engine: String?,
        val task: TaskKind,
        val measurementNote: String,
        val canLogLocally: Boolean,
    ) : ListenUi

    data class Logged(val issue: LoggedIssue, val undone: Boolean = false) : ListenUi
    data class Notice(val message: String, val canRetry: Boolean, val needsPermission: Boolean = false) : ListenUi

    val isBusy: Boolean get() = this is Preparing || this is Listening || this is Transcribing
}

/**
 * A one-line message for the screen that caused it. Tying it to a [scope] means a failure reported on one screen can never
 * turn up on another one later (an earlier version showed "Your phone isn't reachable" on Setup after a failed Confirm).
 */
data class ScreenNotice(val scope: String, val message: String) {
    companion object {
        const val SETUP = "setup"
        const val PROVIDERS = "providers"
        fun forAction(actionId: String) = "action:$actionId"
    }
}

/** The person's pending question while the listening flow runs: its task, the snapshot taken *before* they spoke, etc. */
private data class Pending(
    val requestId: String,
    val task: TaskKind,
    val origin: SnapshotOrigin,
    val snapshot: MeasurementContext?,
    val conversationId: String?,
    val editsActionId: String?,
    val inputOrigin: InputOrigin = InputOrigin.SPEECH_WATCH,
)

/**
 * Drives the assistant page and everything reached from it. Scoped to the "main" destination like the other
 * ViewModels, so a question survives swiping between pages. It never starts a microphone on its own: listening
 * begins only from an explicit tap, takes an exclusive audio lease (pausing measurement and resuming it
 * afterwards), is bounded in time, and ends with a transcript the person reviews before anything is sent.
 */
class AssistantViewModel(private val container: AppContainer) : ViewModel() {
    private val repo get() = container.assistant

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

    private val _listen = MutableStateFlow<ListenUi>(ListenUi.Idle)
    val listen: StateFlow<ListenUi> = _listen.asStateFlow()

    private val _notice = MutableStateFlow<ScreenNotice?>(null)

    /** The current screen-scoped message (e.g. "Phone not reachable"). It clears itself after [NOTICE_MS]. */
    val notice: StateFlow<ScreenNotice?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private var pending: Pending? = null
    private var listenJob: Job? = null
    private var discardRecording = false
    private var lastLoggedTimer: Job? = null

    /** Questions still on their way (not final, not waiting for a decision) -- shown as a "working" card. */
    val inFlight: StateFlow<List<OutboxEntry>> = outbox.map { f ->
        f.entries.filter { !it.state.isFinal && it.state != OutboxState.STALE }.sortedBy { it.createdAtEpochMs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val attention: StateFlow<List<AttentionItem>> = combine(cache, outbox, memos) { c, o, m ->
        AssistantAttention.build(c.threads, o.entries, m.memos)
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

    // ---------------------------------------------------------------------------------- listening

    /**
     * Starts a bounded dictation. The measurement snapshot (if [attach]) is taken first, then the microphone is borrowed
     * from measurement for just the utterance. [conversationId] / [editsActionId] continue an existing conversation (a
     * voice edit of a draft).
     */
    fun startListening(
        task: TaskKind, origin: SnapshotOrigin, attach: Boolean = true,
        conversationId: String? = null, editsActionId: String? = null,
    ) {
        if (_listen.value.isBusy) return
        listenJob?.cancel()
        // Taken before anything else happens, so what is attached is what was on screen when the person asked.
        val snapshot = if (attach) snapshotNow(origin) else null
        pending = Pending(UUID.randomUUID().toString(), task, origin, snapshot, conversationId ?: conversationFor(task), editsActionId)
        _listen.value = ListenUi.Preparing
        listenJob = viewModelScope.launch { runListening() }
    }

    private sealed interface Captured {
        data class Said(val text: String, val engine: String) : Captured
        class Recorded(val pcm: ByteArray, val sampleRateHz: Int, val durationMs: Long) : Captured
        data class Ended(val ui: ListenUi) : Captured
    }

    private suspend fun runListening() {
        val p = pending ?: return
        val input = container.speechInput
        val maxMs = repo.prefs.value.listenSeconds.coerceIn(5, 30) * 1000L
        if (!input.hasPermission()) {
            _listen.value = ListenUi.Notice("Allow the microphone to ask by voice.", canRetry = true, needsPermission = true)
            return
        }
        // Borrow the microphone from measurement for this utterance only (and wait until it is truly free).
        val lease = container.audioCoordinator.acquire(AudioLeaseKind.LISTENING, ttlMs = maxMs + 20_000)
        val captured = try {
            captureUtterance(maxMs)
        } finally {
            // Released the moment the mic is no longer needed -- measurement resumes while the person reviews.
            container.audioCoordinator.release(lease)
        }
        when (captured) {
            is Captured.Said -> showReview(p, captured.text, captured.engine)
            is Captured.Recorded -> handleRecording(p, captured)
            is Captured.Ended -> _listen.value = captured.ui
        }
    }

    private suspend fun captureUtterance(maxMs: Long): Captured {
        val input = container.speechInput
        if (input.isOnDeviceAvailable()) {
            _listen.value = ListenUi.Listening(partial = null, usingRecorder = false)
            val partialJob = viewModelScope.launch { input.partial.collect { t -> _listen.value = ListenUi.Listening(t, false) } }
            val result = input.listen(maxMs, viewModelScope)
            partialJob.cancel()
            return when (result) {
                is ListenResult.Text -> Captured.Said(result.text, result.engine)
                ListenResult.NoSpeech -> Captured.Ended(ListenUi.Notice("I didn't catch that. Tap ● to try again.", canRetry = true))
                ListenResult.Cancelled -> Captured.Ended(ListenUi.Idle)
                ListenResult.NeedsPermission -> Captured.Ended(ListenUi.Notice("Allow the microphone to ask by voice.", canRetry = true, needsPermission = true))
                is ListenResult.Unavailable -> recordForPhone(maxMs)
                is ListenResult.Failed -> Captured.Ended(ListenUi.Notice(result.message, canRetry = true))
            }
        }
        return recordForPhone(maxMs)
    }

    /** No on-device recognizer: record a short clip; the phone transcribes it (on-device there; cloud only if you opted in). */
    private suspend fun recordForPhone(maxMs: Long): Captured {
        // Never record something there is nowhere to keep: a waiting recording or an unchecked transcript is not dropped to make room.
        if (!repo.canKeepAnotherMemo()) {
            return Captured.Ended(ListenUi.Notice("Voice memos are full. Send or delete one under Voice memos, then try again.", canRetry = false))
        }
        _listen.value = ListenUi.Listening(partial = null, usingRecorder = true)
        discardRecording = false
        val rec = container.voiceRecorder.record(maxMs)
        if (discardRecording) return Captured.Ended(ListenUi.Idle)
        return when (rec) {
            is RecordResult.Recorded -> Captured.Recorded(rec.pcm, rec.sampleRateHz, rec.durationMs)
            RecordResult.NothingHeard -> Captured.Ended(ListenUi.Notice("I didn't hear anything. Tap ● to try again.", canRetry = true))
            RecordResult.NeedsPermission -> Captured.Ended(ListenUi.Notice("Allow the microphone to ask by voice.", canRetry = true, needsPermission = true))
            is RecordResult.Failed -> Captured.Ended(ListenUi.Notice(rec.message, canRetry = true))
        }
    }

    private suspend fun handleRecording(p: Pending, rec: Captured.Recorded) {
        val memoId = UUID.randomUUID().toString()
        repo.saveMemo(
            VoiceMemo(
                memoId = memoId, requestId = p.requestId, createdAtEpochMs = System.currentTimeMillis(), durationMs = rec.durationMs,
                sampleRateHz = rec.sampleRateHz, byteCount = rec.pcm.size.toLong(), purpose = VoicePurpose.DICTATION, taskKind = p.task,
                state = MemoState.PENDING_PHONE, snapshot = p.snapshot, performanceId = effectivePerformanceId(),
            ),
            rec.pcm,
        )
        if (!repo.refreshReachability()) {
            _listen.value = ListenUi.Notice("Saved on your watch. It will go to your phone when it's nearby — find it under Voice memos.", canRetry = false)
            return
        }
        _listen.value = ListenUi.Transcribing
        repo.uploadPendingMemos()
        val settled = withTimeoutOrNull(TRANSCRIBE_WAIT_MS) {
            repo.memos.first { f -> f.memos.firstOrNull { it.memoId == memoId }?.state.let { it == MemoState.TRANSCRIPT_READY || it == MemoState.FAILED } }
        }
        val memo = settled?.memos?.firstOrNull { it.memoId == memoId }
        when {
            // The memo is kept (as a transcript, no audio) until it is sent or discarded, so a transcript is never lost
            // if the app is closed mid-review; it then shows up under Voice memos.
            memo?.state == MemoState.TRANSCRIPT_READY && memo.transcript != null -> showReview(p, memo.transcript, memo.engine)
            memo?.state == MemoState.FAILED -> _listen.value = ListenUi.Notice(memo.error ?: "Your phone couldn't transcribe that.", canRetry = true)
            else -> _listen.value = ListenUi.Notice("Your phone is still working on it. It will appear under Voice memos when it's ready.", canRetry = false)
        }
    }

    private fun showReview(p: Pending, text: String, engine: String?) {
        val snap = p.snapshot
        val note = when {
            snap == null -> "No measurement attached"
            else -> "Measurement attached · taken ${AssistantFormatting.ago(snap.capturedAtEpochMs, System.currentTimeMillis())}"
        }
        _listen.value = ListenUi.Review(text, engine, p.task, note, canLogLocally = p.task == TaskKind.LOG_ISSUE)
    }

    /** The person tapped Done: use what has been heard / recorded so far. */
    fun finishListening() {
        container.speechInput.finish()
        container.voiceRecorder.stop()
    }

    /** The person tapped Cancel: stop listening and keep nothing. */
    fun cancelListening() {
        discardRecording = true
        container.speechInput.cancel()
        container.voiceRecorder.stop()
        val job = listenJob
        val state = _listen.value
        if (state is ListenUi.Transcribing) job?.cancel()
        _listen.value = ListenUi.Idle
        pending = null
    }

    /** Dismisses a notice / finished flow so the screen can close. */
    fun closeListening() {
        if (_listen.value.isBusy) cancelListening()
        _listen.value = ListenUi.Idle
        pending = null
    }

    fun redo() {
        val p = pending ?: return
        _listen.value = ListenUi.Idle
        // The transcript being replaced is discarded with its memo.
        viewModelScope.launch { repo.memos.value.memos.filter { it.requestId == p.requestId }.forEach { repo.deleteMemo(it.memoId) } }
        startListening(p.task, p.origin, attach = p.snapshot != null, conversationId = p.conversationId, editsActionId = p.editsActionId)
    }

    /** Opens a transcript that a voice memo produced, for review. The snapshot taken before that recording is preserved. */
    fun reviewMemo(memoId: String) {
        val m = memos.value.memos.firstOrNull { it.memoId == memoId } ?: return
        val text = m.transcript ?: return
        val p = Pending(
            requestId = m.requestId, task = m.taskKind, origin = m.snapshot?.origin ?: SnapshotOrigin.ASSISTANT, snapshot = m.snapshot,
            conversationId = conversationFor(m.taskKind), editsActionId = null, inputOrigin = InputOrigin.VOICE_MEMO_TRANSCRIBED,
        )
        pending = p
        showReview(p, text, m.engine)
    }

    fun deleteMemo(memoId: String) { viewModelScope.launch { repo.deleteMemo(memoId) } }

    /** Throws away the transcript under review, and the voice memo it came from (its audio is already gone). Nothing is sent. */
    fun discardReview() {
        val p = pending
        _listen.value = ListenUi.Idle
        pending = null
        if (p != null) viewModelScope.launch { repo.memos.value.memos.filter { it.requestId == p.requestId }.forEach { repo.deleteMemo(it.memoId) } }
    }

    /** Sends the reviewed words (and the pre-speech measurement) to the assistant. */
    fun send(text: String) {
        val p = pending ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val request = AssistantRequest(
            requestId = p.requestId, conversationId = p.conversationId ?: UUID.randomUUID().toString(), taskKind = p.task, userText = clean,
            inputOrigin = p.inputOrigin, transcriptReviewed = true, replyMode = repo.prefs.value.outputMode, measurement = p.snapshot,
            performanceId = effectivePerformanceId(), createdAtWatchEpochMs = System.currentTimeMillis(), editsActionId = p.editsActionId,
        )
        val memoId = repo.memos.value.memos.firstOrNull { it.requestId == p.requestId }?.memoId
        viewModelScope.launch {
            repo.submit(request)
            memoId?.let { repo.deleteMemo(it) }
        }
        _listen.value = ListenUi.Idle
        pending = null
    }

    /** Logs the issue on the watch right now -- no phone, network or AI needed -- with a read-back and Undo. */
    fun logLocally(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val shows = cache.value.shows
        val perfId = effectivePerformanceId()
        val prodId = shows?.performances?.firstOrNull { it.id == perfId }?.productionId ?: shows?.selectedProductionId
        viewModelScope.launch {
            val logged = container.issues.log(clean, prodId, perfId)
            _listen.value = ListenUi.Logged(logged)
            pending = null
            lastLoggedTimer?.cancel()
            lastLoggedTimer = launch {
                delay(UNDO_WINDOW_MS)
                if ((_listen.value as? ListenUi.Logged)?.issue?.issueId == logged.issueId) _listen.value = ListenUi.Idle
            }
        }
    }

    fun undoLogged() {
        val logged = (_listen.value as? ListenUi.Logged)?.issue ?: return
        viewModelScope.launch {
            container.issues.undo(logged.issueId)
            _listen.value = ListenUi.Logged(logged, undone = true)
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
    fun setListenSeconds(s: Int) { viewModelScope.launch { repo.updatePrefs { it.copy(listenSeconds = s.coerceIn(5, 30)) } } }
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
        private const val TRANSCRIBE_WAIT_MS = 45_000L
        private const val UNDO_WINDOW_MS = 10_000L
        private const val CONTINUE_ANSWER_WAIT_MS = 10_000L
        private const val NOTICE_MS = 10_000L
        private const val REPLY_MS = 10_000L
        private const val ACTION_ANSWER_WAIT_MS = 8_000L
        private const val WAITING_FOR_PHONE = "Sent to your phone. Waiting for its answer…"
    }
}
