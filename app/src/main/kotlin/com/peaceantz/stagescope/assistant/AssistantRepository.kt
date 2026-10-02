package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.assistant.measure.MeasurementSnapshotBuilder
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.protocol.Ack
import com.peaceantz.stagescope.shared.protocol.AckStatus
import com.peaceantz.stagescope.shared.protocol.ActionCommand
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.ActionReply
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.CancelRequest
import com.peaceantz.stagescope.shared.protocol.ContinueOnPhone
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.ContinueReply
import com.peaceantz.stagescope.shared.protocol.Decoded
import com.peaceantz.stagescope.shared.protocol.DeviceRole
import com.peaceantz.stagescope.shared.protocol.Hello
import com.peaceantz.stagescope.shared.protocol.PlaybackNotice
import com.peaceantz.stagescope.shared.protocol.Progress
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.ProviderSelect
import com.peaceantz.stagescope.shared.protocol.ProvidersView
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.ResultReady
import com.peaceantz.stagescope.shared.protocol.StatusQuery
import com.peaceantz.stagescope.shared.protocol.StatusReply
import com.peaceantz.stagescope.shared.protocol.SyncNudge
import com.peaceantz.stagescope.shared.protocol.ThreadView
import com.peaceantz.stagescope.shared.protocol.TranscriptResult
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.protocol.WireCodec
import com.peaceantz.stagescope.shared.protocol.WireMessage
import com.peaceantz.stagescope.shared.show.WatchShowView
import com.peaceantz.stagescope.shared.store.PersistentState
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.ZoneId
import java.util.UUID

data class PhoneNode(val id: String, val name: String, val nearby: Boolean)

/** The watch's side of the Data Layer, abstracted so every delivery rule can be tested without a phone. */
interface PhoneLink {
    suspend fun reachablePhones(): List<PhoneNode>

    /** Hands [message] to every reachable phone; returns how many. A positive number is NOT proof it was processed. */
    suspend fun send(message: WireMessage): Int

    // There is deliberately no way to stream a recording to the phone: StageScope no longer transcribes audio on the phone, and a
    // link that cannot send audio makes "no recording ever leaves this watch through StageScope" a fact of the type, not a habit.

    /** Every StageScope data item currently held by the Data Layer: (path, bytes). For a cold start. */
    suspend fun currentDataItems(): List<Pair<String, ByteArray>>
}

/** What the "Continue on phone" button should say, from the phone's own answers. */
sealed interface ContinueUi {
    data object Asking : ContinueUi
    data class Done(val outcome: ContinueOutcome) : ContinueUi
    data object NoAnswer : ContinueUi
}

/**
 * The watch's durable assistant brain. It owns the outbox (every question the person asked, with where
 * it is on its way to the phone and back), the cached views the phone published, preferences and the
 * older voice recordings an earlier version made. Nothing here touches a microphone or a speaker, and nothing here sends audio.
 *
 * Delivery follows [OutboxPolicy]: persist first, send, treat the phone's acknowledgement (not the
 * transport's "sent") as the confirmation, resend the same id if unacknowledged, never auto-send a stale
 * queued question. State only ever moves forward, so duplicated or reordered messages are harmless.
 */
class AssistantRepository(
    dir: File,
    private val link: PhoneLink,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onPlayback: (PlaybackNotice) -> Unit = {},
    /** Called once when an answer has just arrived (for the optional, off-by-default vibration). */
    private val onAnswerReady: () -> Unit = {},
) {
    private val outboxStore = PersistentState(File(dir, "outbox.json"), OutboxFile.serializer(), 1, { OutboxFile() })
    private val cacheStore = PersistentState(File(dir, "phone_cache.json"), PhoneCache.serializer(), 1, { PhoneCache() })
    private val prefsStore = PersistentState(File(dir, "prefs.json"), AssistantPrefs.serializer(), 1, { AssistantPrefs() })
    private val memoStore = PersistentState(File(dir, "memos.json"), MemoFile.serializer(), 1, { MemoFile() })
    val memoDir = File(dir, "memos").apply { mkdirs() }

    val outbox: StateFlow<OutboxFile> = outboxStore.state
    val cache: StateFlow<PhoneCache> = cacheStore.state
    val prefs: StateFlow<AssistantPrefs> = prefsStore.state
    val memos: StateFlow<MemoFile> = memoStore.state

    private val _reachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _phoneName = MutableStateFlow<String?>(null)
    val phoneName: StateFlow<String?> = _phoneName.asStateFlow()

    private val _lastActionReply = MutableStateFlow<ActionReply?>(null)
    val lastActionReply: StateFlow<ActionReply?> = _lastActionReply.asStateFlow()

    private val _continueStates = MutableStateFlow<Map<String, ContinueUi>>(emptyMap())
    val continueStates: StateFlow<Map<String, ContinueUi>> = _continueStates.asStateFlow()

    private var helloSentFor: String? = null

    val installId: String get() = prefsStore.value.installId

    // ------------------------------------------------------------------------------- reachability

    /** Asks the Data Layer who is reachable right now and, on a (re)connection, says hello. */
    suspend fun refreshReachability(): Boolean {
        val phones = link.reachablePhones()
        _reachable.value = phones.isNotEmpty()
        _phoneName.value = phones.firstOrNull()?.name
        val key = phones.firstOrNull()?.id
        if (key != null && key != helloSentFor) {
            helloSentFor = key
            hello()
        }
        if (key == null) helloSentFor = null
        return phones.isNotEmpty()
    }

    suspend fun hello() {
        link.send(
            Hello(
                installId = installId, role = DeviceRole.WATCH, appVersionName = appVersionName, appVersionCode = appVersionCode,
                features = setOf("assistant", "issues", "text-input"), timezoneId = ZoneId.systemDefault().id,
            ),
        )
    }

    /** Asks the phone to republish its views (done when the app opens, so the watch isn't showing yesterday's state). */
    suspend fun requestSync(reason: String = "watch opened") {
        if (refreshReachability()) link.send(SyncNudge(reason))
    }

    // --------------------------------------------------------------------------------- questions

    /** Persists the question *first*, then tries to send it. Returns its request id. */
    suspend fun submit(request: AssistantRequest): String {
        val now = clock()
        outboxStore.update { f -> prune(OutboxFile(f.entries + OutboxEntry(request, OutboxState.QUEUED_OFFLINE, createdAtEpochMs = now))) }
        flushOutbox()
        return request.requestId
    }

    /** Applies [OutboxPolicy] to every entry: send what is fresh, stale-mark what isn't, give up where it should. */
    suspend fun flushOutbox() {
        val reachable = refreshReachability()
        val now = clock()
        for (entry in outboxStore.value.entries) {
            when (val action = OutboxPolicy.decide(entry, now, reachable)) {
                OutboxAction.None -> if (reachable && OutboxPolicy.shouldCheckStatus(entry, now)) link.send(StatusQuery(entry.requestId))
                OutboxAction.Send -> deliver(entry)
                is OutboxAction.MarkStale -> setState(entry.requestId, OutboxState.STALE, action.reason)
                is OutboxAction.MarkExpired -> setState(entry.requestId, OutboxState.EXPIRED, action.reason)
                is OutboxAction.MarkFailed -> setState(entry.requestId, OutboxState.FAILED, action.reason)
            }
        }
    }

    private suspend fun deliver(entry: OutboxEntry) {
        var request = entry.request
        var sent = trySend(request)
        if (sent == TOO_LARGE) {
            // Shrink the attached measurement first; if even that can't fit, send the question without it.
            request = request.copy(
                measurement = request.measurement?.let { MeasurementSnapshotBuilder.fitToBudget(it, 9_000) },
                comparisonBefore = request.comparisonBefore?.let { MeasurementSnapshotBuilder.fitToBudget(it, 6_000) },
            )
            sent = trySend(request)
            if (sent == TOO_LARGE) {
                request = request.copy(measurement = null, comparisonBefore = null)
                sent = trySend(request)
            }
        }
        when (sent) {
            TOO_LARGE -> setState(entry.requestId, OutboxState.FAILED, "That question is too long to send. Try a shorter one.")
            0 -> Unit // nobody reachable after all: it stays queued and is tried again
            else -> outboxStore.update { f ->
                OutboxFile(
                    f.entries.map {
                        // Only if nothing newer (an ack that raced ahead of this update) has already moved it on.
                        if (it.requestId == entry.requestId && it.state.rank() <= OutboxState.SENDING.rank()) {
                            it.copy(request = request, state = OutboxState.SENDING, attempts = it.attempts + 1, lastAttemptAtEpochMs = clock())
                        } else it
                    },
                )
            }
        }
    }

    private suspend fun trySend(request: AssistantRequest): Int = try {
        link.send(request)
    } catch (e: WireCodec.PayloadTooLarge) {
        TOO_LARGE
    }

    /** The person pressed Cancel. A question the phone never received is simply never sent; otherwise the phone is told. */
    suspend fun cancel(requestId: String) {
        val entry = outboxStore.value.entries.firstOrNull { it.requestId == requestId } ?: return
        if (entry.state.isFinal) return
        val phoneMayHaveIt = entry.state != OutboxState.QUEUED_OFFLINE && entry.state != OutboxState.STALE
        setState(
            requestId, OutboxState.CANCELLED,
            if (phoneMayHaveIt) "Cancelled. Anything the assistant already did is not undone." else "Cancelled before it was sent.",
        )
        if (phoneMayHaveIt) runCatching { link.send(CancelRequest(requestId)) }
    }

    /**
     * Ask again. A question the phone never acknowledged is re-sent *with the same id* (if the phone did get it, it
     * recognises the id and doesn't run it twice). One the phone did take and then failed is a genuinely new request --
     * carrying the original measurement with its original capture time, so evidence is never relabelled as fresh.
     */
    suspend fun retry(requestId: String): String? {
        val entry = outboxStore.value.entries.firstOrNull { it.requestId == requestId } ?: return null
        if (entry.ackedAtEpochMs == null) {
            outboxStore.update { f ->
                OutboxFile(
                    f.entries.map {
                        if (it.requestId == requestId) it.copy(state = OutboxState.QUEUED_OFFLINE, attempts = 0, lastAttemptAtEpochMs = null, createdAtEpochMs = clock(), message = null) else it
                    },
                )
            }
            flushOutbox()
            return requestId
        }
        return submit(entry.request.copy(requestId = newId(), createdAtWatchEpochMs = clock()))
    }

    /** "Send it anyway" for a stale queued question: the person has decided the old question is still wanted. */
    suspend fun sendStale(requestId: String) {
        outboxStore.update { f ->
            OutboxFile(f.entries.map { if (it.requestId == requestId && it.state == OutboxState.STALE) it.copy(state = OutboxState.QUEUED_OFFLINE, createdAtEpochMs = clock(), message = null) else it })
        }
        flushOutbox()
    }

    suspend fun discard(requestId: String) {
        outboxStore.update { f -> OutboxFile(f.entries.filterNot { it.requestId == requestId }) }
    }

    // -------------------------------------------------------------------------------- inbound

    suspend fun handleBytes(bytes: ByteArray) {
        val envelope = when (val d = WireCodec.decode(bytes)) {
            is Decoded.Ok -> d.envelope
            else -> return
        }
        cacheStore.update { it.copy(lastHeardFromPhoneEpochMs = clock()) }
        when (val m = envelope.message) {
            is Hello -> if (m.role == DeviceRole.PHONE) {
                cacheStore.update { it.copy(phoneAppVersion = m.appVersionName, negotiatedProtocolVersion = m.selectedVersion) }
                flushOutbox()
            }
            is Ack -> onAck(m)
            is Progress -> advance(m.requestId, if (m.stage == ProgressStage.QUEUED) OutboxState.ACKED else OutboxState.RUNNING) { it.copy(progress = m.stage, progressText = m.text) }
            is ResultReady -> onResultState(m.requestId, m.state, m.revision, null)
            is StatusReply -> onResultState(m.requestId, m.state, m.revision, null)
            is ActionReply -> _lastActionReply.value = m
            is ContinueReply -> _continueStates.value = _continueStates.value + (m.requestId to ContinueUi.Done(m.outcome))
            is PlaybackNotice -> onPlayback(m)
            is TranscriptResult -> onTranscript(m)
            else -> Unit
        }
    }

    private suspend fun onAck(a: Ack) {
        when (a.status) {
            AckStatus.RECEIVED, AckStatus.DUPLICATE -> advance(a.requestId, OutboxState.ACKED) { it.copy(ackedAtEpochMs = it.ackedAtEpochMs ?: clock()) }
            AckStatus.REJECTED -> setState(a.requestId, OutboxState.FAILED, a.detail ?: "The phone couldn't take that request.")
        }
    }

    private suspend fun onResultState(requestId: String, state: RequestState, revision: Long?, error: String?) {
        when (state) {
            RequestState.COMPLETED -> advance(requestId, OutboxState.RESULT_READY) {
                // Done: the measurement has served its purpose, so the stored copy sheds its weight.
                it.copy(resultRevision = revision ?: it.resultRevision, request = it.request.copy(measurement = null, comparisonBefore = null))
            }
            RequestState.FAILED -> setState(requestId, OutboxState.FAILED, error ?: "The assistant couldn't finish that. Open the conversation on your phone for the reason.")
            RequestState.CANCELLED -> setState(requestId, OutboxState.CANCELLED, "Cancelled.")
            RequestState.RUNNING -> advance(requestId, OutboxState.RUNNING)
            RequestState.QUEUED, RequestState.WAITING_FOR_ACTION -> advance(requestId, OutboxState.ACKED)
            RequestState.UNKNOWN -> {
                // The phone has never heard of it: it must be sent (again), not waited for.
                val e = outboxStore.value.entries.firstOrNull { it.requestId == requestId } ?: return
                if (e.state.isWaitingOnPhone) {
                    setState(requestId, OutboxState.QUEUED_OFFLINE, null)
                    flushOutbox()
                }
            }
        }
    }

    suspend fun handleDataItems(items: List<Pair<String, ByteArray?>>) {
        for ((path, bytes) in items) {
            bytes ?: continue
            val text = bytes.toString(Charsets.UTF_8)
            runCatching {
                when {
                    path.startsWith(Wire.DATA_THREAD_PREFIX + "/") -> onThread(StageScopeJson.decodeFromString(ThreadView.serializer(), text))
                    path == Wire.DATA_PROVIDERS -> cacheStore.update { it.copy(providers = StageScopeJson.decodeFromString(ProvidersView.serializer(), text)) }
                    path == Wire.DATA_SHOWS -> cacheStore.update { it.copy(shows = StageScopeJson.decodeFromString(WatchShowView.serializer(), text)) }
                }
            }
        }
    }

    private suspend fun onThread(view: ThreadView) {
        cacheStore.update { c ->
            val existing = c.threads.firstOrNull { it.conversationId == view.conversationId }
            if (existing != null && existing.revision > view.revision) c
            else c.copy(threads = (c.threads.filterNot { it.conversationId == view.conversationId } + view).sortedByDescending { it.updatedAtPhoneEpochMs }.take(MAX_THREADS))
        }
        view.requestId?.let { onResultState(it, view.requestState, view.revision, view.error?.message) }
    }

    // ------------------------------------------------------------------------ actions on the phone

    /** Sends a person-driven action command (confirm, cancel, mark done...). Returns false if no phone could be reached. */
    suspend fun actionCommand(actionId: String, command: ActionCommandKind, revision: Int? = null, contentHash: String? = null): Boolean {
        if (!refreshReachability()) return false
        _lastActionReply.value = null
        return link.send(ActionCommand(newId(), actionId, command, revision, contentHash)) > 0
    }

    /** Forgets the last reply -- or, given [expected], only if it is still that one (a newer reply is kept). */
    fun consumeActionReply(expected: ActionReply? = null) {
        if (expected == null || _lastActionReply.value == expected) _lastActionReply.value = null
    }

    suspend fun selectProvider(provider: ProviderId, modelId: String? = null, thorough: Boolean? = null, webSearch: Boolean? = null): Boolean {
        if (!refreshReachability()) return false
        return link.send(ProviderSelect(newId(), provider, modelId, thorough, webSearch)) > 0
    }

    /** Asks the phone to make [conversationId] / [actionId] reachable there; the answer arrives as a [ContinueReply]. */
    suspend fun continueOnPhone(conversationId: String?, actionId: String?): String {
        val id = newId()
        _continueStates.value = _continueStates.value + (id to ContinueUi.Asking)
        val sent = refreshReachability() && link.send(ContinueOnPhone(id, conversationId, actionId)) > 0
        if (!sent) _continueStates.value = _continueStates.value + (id to ContinueUi.NoAnswer)
        return id
    }

    fun markContinueNoAnswer(requestId: String) {
        if (_continueStates.value[requestId] == ContinueUi.Asking) _continueStates.value = _continueStates.value + (requestId to ContinueUi.NoAnswer)
    }

    // ---------------------------------------------------------------------------------- memos

    // Older versions recorded short clips and had the phone turn them into text. Nothing records or uploads now; what is left from
    // those versions is kept exactly as it was, for the person to read or delete -- never uploaded, never auto-sent.

    /**
     * One-time, idempotent: a recording that was waiting for (or being turned into text by) the phone becomes an older recording that
     * simply stays on the watch. Transcripts already made are untouched (they can still be reviewed as text), and nothing is deleted:
     * the audio file, the pre-recording measurement snapshot and the memo itself all survive until the person deletes it.
     */
    suspend fun migrateLegacyMemos() {
        memoStore.update { f ->
            MemoFile(
                f.memos.map { m ->
                    when {
                        m.state == MemoState.PENDING_PHONE || m.state == MemoState.UPLOADING || m.state == MemoState.TRANSCRIBING ->
                            m.copy(state = MemoState.LEGACY_RECORDING, error = null)
                        // A recording that "couldn't be sent" is still a recording the person made.
                        m.state == MemoState.FAILED && m.transcript == null && File(memoDir, "${m.memoId}.pcm").exists() ->
                            m.copy(state = MemoState.LEGACY_RECORDING, error = null)
                        else -> m
                    }
                },
            )
        }
    }

    suspend fun updateMemo(memoId: String, change: (VoiceMemo) -> VoiceMemo) {
        memoStore.update { f -> MemoFile(f.memos.map { if (it.memoId == memoId) change(it) else it }) }
    }

    suspend fun deleteMemo(memoId: String) {
        memoStore.update { f -> MemoFile(f.memos.filterNot { it.memoId == memoId }) }
        runCatching { File(memoDir, "$memoId.pcm").delete() }
    }

    /**
     * A transcript from an earlier version's phone transcription that was still on its way when this version took over. It only ever
     * makes words available for the person to review -- it never sends anything -- and an error never disturbs a recording that is
     * still on the watch.
     */
    private suspend fun onTranscript(t: TranscriptResult) {
        val memo = memoStore.value.memos.firstOrNull { it.memoId == t.memoId } ?: return
        if (memo.state == MemoState.TRANSCRIPT_READY || memo.state == MemoState.SENT) return
        val text = t.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            updateMemo(memo.memoId) { it.copy(state = MemoState.TRANSCRIPT_READY, transcript = text, engine = t.engine, error = null) }
            // The words are what the person will review, and they are what is kept; the old clip is not needed any more.
            runCatching { File(memoDir, "${memo.memoId}.pcm").delete() }
        } else if (!File(memoDir, "${memo.memoId}.pcm").exists()) {
            updateMemo(memo.memoId) { it.copy(state = MemoState.FAILED, error = t.error ?: "The phone couldn't transcribe that.") }
        }
    }

    // ---------------------------------------------------------------------------------- prefs

    suspend fun updatePrefs(change: (AssistantPrefs) -> AssistantPrefs) { prefsStore.update(change) }

    // -------------------------------------------------------------------------------- internals

    /**
     * Sets a state directly. A delivered answer, a cancellation or an expiry is never overwritten by a late
     * message; a failure can be (the phone may report a more precise reason, or the person may cancel).
     */
    private suspend fun setState(requestId: String, state: OutboxState, message: String?) {
        outboxStore.update { f ->
            OutboxFile(
                f.entries.map {
                    val settled = it.state == OutboxState.RESULT_READY || it.state == OutboxState.CANCELLED || it.state == OutboxState.EXPIRED
                    if (it.requestId == requestId && !settled) it.copy(state = state, message = message) else it
                },
            )
        }
    }

    /**
     * Moves an entry forward only: a late or duplicated message can never drag it back, or past a decision
     * the person made (cancel). One exception is deliberate: an answer that arrives for a request the *watch*
     * had given up on (the ack was lost, the phone worked anyway) still delivers the answer.
     */
    private suspend fun advance(requestId: String, to: OutboxState, extra: (OutboxEntry) -> OutboxEntry = { it }) {
        var arrived = false
        outboxStore.update { f ->
            arrived = false
            OutboxFile(
                f.entries.map { e ->
                    when {
                        e.requestId != requestId -> e
                        e.state == OutboxState.CANCELLED || e.state == OutboxState.EXPIRED -> e
                        e.state == OutboxState.RESULT_READY -> if (to == OutboxState.RESULT_READY) extra(e) else e
                        e.state == OutboxState.FAILED -> if (to == OutboxState.RESULT_READY) extra(e).copy(state = to, message = null).also { arrived = true } else e
                        to.rank() < e.state.rank() -> extra(e)
                        else -> extra(e).copy(state = to, message = null).also { if (to == OutboxState.RESULT_READY) arrived = true }
                    }
                },
            )
        }
        if (arrived) onAnswerReady()
    }

    private fun OutboxState.rank(): Int = when (this) {
        OutboxState.QUEUED_OFFLINE, OutboxState.STALE -> 0
        OutboxState.SENDING -> 1
        OutboxState.ACKED -> 2
        OutboxState.RUNNING -> 3
        OutboxState.RESULT_READY, OutboxState.FAILED, OutboxState.CANCELLED, OutboxState.EXPIRED -> 4
    }

    private fun prune(f: OutboxFile): OutboxFile {
        if (f.entries.size <= MAX_OUTBOX) return f
        val drop = f.entries.filter { it.state.isFinal }.sortedBy { it.createdAtEpochMs }.take(f.entries.size - MAX_OUTBOX).map { it.requestId }.toSet()
        return OutboxFile(f.entries.filterNot { it.requestId in drop })
    }

    companion object {
        private const val TOO_LARGE = -1
        const val MAX_OUTBOX = 20
        const val MAX_THREADS = 8
    }
}
