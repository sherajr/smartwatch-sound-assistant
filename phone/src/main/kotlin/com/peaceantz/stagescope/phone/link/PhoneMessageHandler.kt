package com.peaceantz.stagescope.phone.link

import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.assistant.ActionExecutor
import com.peaceantz.stagescope.phone.assistant.ConfirmOutcome
import com.peaceantz.stagescope.phone.data.AcceptResult
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.tools.ContinueTarget
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionPresentation
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.actions.IssueLogDraft
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueOps
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
import com.peaceantz.stagescope.shared.protocol.DeviceRole
import com.peaceantz.stagescope.shared.protocol.Envelope
import com.peaceantz.stagescope.shared.protocol.Hello
import com.peaceantz.stagescope.shared.protocol.ProviderSelect
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.StatusQuery
import com.peaceantz.stagescope.shared.protocol.StatusReply
import com.peaceantz.stagescope.shared.protocol.SyncNudge
import com.peaceantz.stagescope.shared.protocol.TranscriptResult
import com.peaceantz.stagescope.shared.protocol.VoiceOffer
import com.peaceantz.stagescope.shared.protocol.Wire
import com.peaceantz.stagescope.shared.protocol.WireCodec

data class ConfirmCommand(val actionId: String, val opId: String, val revision: Int, val contentHash: String, val by: ConfirmSource)

/** Durable background work. Implemented over WorkManager on Android, faked in tests. */
interface WorkScheduler {
    fun enqueueAssistant(requestId: String)
    fun cancelAssistant(requestId: String)
    fun enqueueConfirm(cmd: ConfirmCommand)

    /**
     * Cancels transcription jobs that an earlier version queued for watch recordings, and nothing else (not AI requests, not
     * confirmations, not sync). Safe to call every start: it finds nothing once they are gone.
     */
    fun cancelLegacyTranscription()
}

interface Continuations {
    /** Makes the exact saved item reachable on the phone and says honestly how. */
    suspend fun continueOnPhone(requestId: String, target: ContinueTarget): ContinueOutcome
}

/**
 * Everything the watch can ask of the phone. Each handler *persists first and acknowledges
 * promptly*: a watch request is written to the durable inbox, acked (RECEIVED / DUPLICATE), and
 * handed to WorkManager -- the (possibly minutes-long) AI work never depends on this call stack
 * surviving. Idempotency keys are the request / operation ids carried by the messages.
 */
class PhoneMessageHandler(
    private val data: PhoneData,
    private val link: WatchLink,
    private val publisher: ThreadPublisher,
    private val scheduler: WorkScheduler,
    private val executor: ActionExecutor,
    private val continuations: Continuations,
    private val phoneInstallId: () -> String,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val hasKey: (ProviderId) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun handleBytes(bytes: ByteArray) {
        when (val d = WireCodec.decode(bytes)) {
            is com.peaceantz.stagescope.shared.protocol.Decoded.Ok -> handle(d.envelope)
            is com.peaceantz.stagescope.shared.protocol.Decoded.UnsupportedVersion -> Unit // newer watch app: it will see no Hello reply and prompt an update
            is com.peaceantz.stagescope.shared.protocol.Decoded.Malformed -> Unit
        }
    }

    suspend fun handle(envelope: Envelope) {
        data.settings.update { it.copy(lastWatchContactEpochMs = clock()) }
        when (val m = envelope.message) {
            is Hello -> onHello(m)
            is AssistantRequest -> onRequest(m)
            is CancelRequest -> onCancel(m)
            is StatusQuery -> link.send(StatusReply(m.requestId, publisher.requestStateFor(m.requestId), data.inbox.get(m.requestId)?.request?.conversationId, data.inbox.get(m.requestId)?.revision))
            is ActionCommand -> onAction(m)
            is ContinueOnPhone -> {
                val target = ContinueTarget(m.conversationId, m.actionId)
                val outcome = continuations.continueOnPhone(m.requestId, target)
                link.send(ContinueReply(m.requestId, outcome))
            }
            is ProviderSelect -> onProviderSelect(m)
            is SyncNudge -> publishAll()
            // An older watch app may still offer a recording. StageScope no longer turns recordings into text -- not on this phone, and
            // never by uploading audio to a cloud service -- so say so in the two shapes an older watch understands (a refused ack and a
            // failed transcript, which it shows next to the recording) and keep nothing: no audio, no job, no cost.
            is VoiceOffer -> {
                link.send(Ack(m.requestId, AckStatus.REJECTED, LEGACY_VOICE_REFUSAL))
                link.send(TranscriptResult(m.requestId, m.memoId, error = LEGACY_VOICE_REFUSAL))
            }
            else -> Unit // Ack/Progress/etc. are phone->watch only
        }
    }

    private suspend fun onHello(h: Hello) {
        if (h.role != DeviceRole.WATCH) return
        val negotiated = WireCodec.negotiate(Wire.MIN_SUPPORTED_VERSION, Wire.PROTOCOL_VERSION, h.minVersion, h.maxVersion)
        data.settings.update { it.copy(watchInstallId = h.installId, watchAppVersion = h.appVersionName, negotiatedProtocolVersion = negotiated) }
        link.send(
            Hello(
                installId = phoneInstallId(), role = DeviceRole.PHONE, appVersionName = appVersionName, appVersionCode = appVersionCode,
                selectedVersion = negotiated, features = setOf("assistant", "issues", "actions"),
                timezoneId = java.time.ZoneId.systemDefault().id,
            ),
        )
        if (negotiated != null) publishAll()
    }

    private suspend fun onRequest(req: AssistantRequest) {
        when (val r = data.inbox.accept(req, "watch", clock())) {
            AcceptResult.New -> {
                link.send(Ack(req.requestId, AckStatus.RECEIVED))
                scheduler.enqueueAssistant(req.requestId)
            }
            is AcceptResult.Duplicate -> {
                // A retransmit: say so, and if it already finished, re-publish the durable result.
                link.send(Ack(req.requestId, AckStatus.DUPLICATE, "Already received (${r.existing.state.name.lowercase()})."))
                publisher.publishThread(req.conversationId, req.requestId, publisher.requestStateFor(req.requestId))
            }
        }
    }

    private suspend fun onCancel(c: CancelRequest) {
        data.inbox.cancel(c.requestId, clock())
        scheduler.cancelAssistant(c.requestId)
        link.send(Ack(c.requestId, AckStatus.RECEIVED, "Cancel received. Anything already done before cancelling is not undone."))
    }

    private suspend fun onAction(c: ActionCommand) {
        val record = data.actions.get(c.actionId)
        if (record == null) {
            link.send(ActionReply(c.requestId, c.actionId, false, ActionState.CANCELLED, "That item no longer exists on the phone."))
            return
        }
        when (c.command) {
            ActionCommandKind.CONFIRM -> {
                val rev = c.revision
                val hash = c.contentHash
                if (rev == null || hash == null) {
                    link.send(ActionReply(c.requestId, c.actionId, false, record.state, "A confirmation must name the exact version that was reviewed."))
                    return
                }
                // The execution itself runs in WorkManager (durable); this reply only says it was received.
                scheduler.enqueueConfirm(ConfirmCommand(c.actionId, c.requestId, rev, hash, ConfirmSource.WATCH))
                link.send(ActionReply(c.requestId, c.actionId, true, record.state, "Received. Working on it."))
            }
            ActionCommandKind.UNDO -> undoLogged(c, record)
            else -> {
                val outcome = when (c.command) {
                    ActionCommandKind.CANCEL -> executor.cancel(c.actionId)
                    ActionCommandKind.MARK_DONE -> executor.markDone(c.actionId)
                    ActionCommandKind.HANDOFF_OPENED -> executor.handoffOpened(c.actionId)
                    ActionCommandKind.REOPEN -> executor.reopen(c.actionId)
                    ActionCommandKind.UNCERTAIN_WAS_DONE -> executor.resolveUncertain(c.actionId, true)
                    else -> executor.resolveUncertain(c.actionId, false)
                }
                reply(c, outcome)
            }
        }
    }

    private suspend fun undoLogged(c: ActionCommand, record: com.peaceantz.stagescope.shared.actions.ActionRecord) {
        val draft = record.draft as? IssueLogDraft
        if (record.kind != ActionKind.LOG_ISSUE || draft == null) {
            link.send(ActionReply(c.requestId, c.actionId, false, record.state, "Only a logged issue can be undone."))
            return
        }
        val outcome = executor.undoLocal(c.actionId)
        if (outcome is ConfirmOutcome.Ran) {
            // A tombstone, not a removal: the delete must survive sync or the watch's copy would bring it back.
            data.issues.update { IssueLedger.delete(it, "undo-${c.requestId}", draft.issueId) }
            publisher.publishIssues()
        }
        reply(c, outcome)
    }

    private suspend fun reply(c: ActionCommand, outcome: ConfirmOutcome) {
        val state = outcome.record?.state ?: ActionState.CANCELLED
        link.send(ActionReply(c.requestId, c.actionId, outcome is ConfirmOutcome.Ran, state, outcome.message))
        outcome.record?.let { publisher.actionChanged(it) }
    }

    private suspend fun onProviderSelect(p: ProviderSelect) {
        data.settings.update { s ->
            var next = s.copy(selectedProvider = p.providerId)
            p.modelId?.takeIf { ModelCatalog.find(p.providerId, it) != null }?.let { next = next.copy(models = next.models + (p.providerId to it)) }
            p.thorough?.let { next = next.copy(thorough = next.thorough + (p.providerId to it)) }
            p.webSearch?.let { next = next.copy(webSearch = next.webSearch + (p.providerId to it)) }
            next
        }
        publisher.publishProviders(hasKey, data.settings.value.googleAccountEmail != null)
        link.send(Ack(p.requestId, AckStatus.RECEIVED))
    }

    suspend fun publishAll() {
        publisher.publishProviders(hasKey, data.settings.value.googleAccountEmail != null)
        publisher.publishShows()
        publisher.publishIssues()
        data.conversations.recent(1).firstOrNull()?.let { publisher.publishThread(it.id, null, RequestState.COMPLETED) }
    }
}

/** What an older watch app is told when it offers a recording. It shows this next to the recording. */
const val LEGACY_VOICE_REFUSAL =
    "StageScope no longer turns recordings into text. Update the StageScope watch app and use the watch's own dictation screen instead."
