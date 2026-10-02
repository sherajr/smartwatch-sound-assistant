package com.peaceantz.stagescope.phone

import android.content.Context
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.assistant.ActionExecutor
import com.peaceantz.stagescope.phone.assistant.AssistantEvents
import com.peaceantz.stagescope.phone.assistant.AssistantOrchestrator
import com.peaceantz.stagescope.phone.data.ClaimResult
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.google.AndroidGoogleAuthorizer
import com.peaceantz.stagescope.phone.google.CalendarClient
import com.peaceantz.stagescope.phone.google.GmailClient
import com.peaceantz.stagescope.phone.google.GoogleService
import com.peaceantz.stagescope.phone.link.ConfirmCommand
import com.peaceantz.stagescope.phone.link.DataLayerWatchLink
import com.peaceantz.stagescope.phone.link.IssueSync
import com.peaceantz.stagescope.phone.link.PhoneMessageHandler
import com.peaceantz.stagescope.phone.link.ThreadPublisher
import com.peaceantz.stagescope.phone.link.WatchLink
import com.peaceantz.stagescope.phone.link.WorkScheduler
import com.peaceantz.stagescope.phone.notify.ContinuationManager
import com.peaceantz.stagescope.phone.security.AndroidKeystoreKey
import com.peaceantz.stagescope.phone.security.CredentialStore
import com.peaceantz.stagescope.phone.security.EncryptedFileCredentialStore
import com.peaceantz.stagescope.phone.tools.ContinueTarget
import com.peaceantz.stagescope.phone.tools.ToolRegistry
import com.peaceantz.stagescope.phone.voice.AndroidSpeechTranscriber
import com.peaceantz.stagescope.phone.voice.OpenAiTranscriber
import com.peaceantz.stagescope.phone.voice.PhoneSpeech
import com.peaceantz.stagescope.phone.voice.TranscriberChain
import com.peaceantz.stagescope.phone.voice.VoiceReceiver
import com.peaceantz.stagescope.phone.work.WorkManagerScheduler
import com.peaceantz.stagescope.shared.actions.ActionError
import com.peaceantz.stagescope.shared.actions.ActionEvent
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.ConfirmSource
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.RequestState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Manual constructor injection -- no DI framework (matches the watch app's convention). */
class PhoneContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val data = PhoneData(File(appContext.filesDir, "stagescope").apply { mkdirs() })
    val credentials: CredentialStore = EncryptedFileCredentialStore(File(appContext.noBackupFilesDir, "credentials.json")) { AndroidKeystoreKey.getOrCreate() }
    val http = ProviderHttp()

    val watchLink: WatchLink = DataLayerWatchLink(appContext) { data.settings.value.phoneInstallId }
    val continuations = ContinuationManager(appContext, data, watchLink)
    val publisher = ThreadPublisher(
        data, watchLink, notificationsEnabled = { continuations.notificationsEnabled() },
        appVersion = BuildConfig.VERSION_NAME,
    )
    val scheduler: WorkScheduler = WorkManagerScheduler(appContext)

    private suspend fun publishProviders() = publisher.publishProviders(credentials::has, data.settings.value.googleAccountEmail != null)

    val providers = ProviderService(data, credentials, http, onChanged = { publishProviders() })
    val authorizer = AndroidGoogleAuthorizer(appContext, http)
    private val calendarClient = CalendarClient(http)
    val executor = ActionExecutor(data, GmailClient(http), calendarClient, authorizer, onChanged = { publisher.actionChanged(it) })
    val google = GoogleService(data, authorizer, calendarClient, onChanged = { publishProviders() })
    val tools = ToolRegistry(data)
    val speech = PhoneSpeech(appContext, watchLink, appScope)

    private val progressStages = ConcurrentHashMap<String, ProgressStage>()

    private val events = object : AssistantEvents {
        override fun progress(requestId: String, stage: ProgressStage, text: String?) {
            // Only transitions are sent: no per-token traffic over the Data Layer.
            if (progressStages.put(requestId, stage) == stage) return
            appScope.launch { publisher.progress(requestId, stage, text) }
        }

        override suspend fun resultReady(requestId: String, conversationId: String, state: RequestState) {
            progressStages.remove(requestId)
            publisher.publishThread(conversationId, requestId, state)
        }

        override suspend fun continuationRequested(target: ContinueTarget) {
            continuations.continueOnPhone("tool-" + UUID.randomUUID(), target)
        }
    }

    val orchestrator = AssistantOrchestrator(
        data = data, adapters = providers::adapter, tools = tools, events = events,
        googleEmail = { data.settings.value.googleAccountEmail },
    )

    private val transcriber = {
        val cloud = if (data.settings.value.speechProvider == com.peaceantz.stagescope.phone.data.SpeechProviderChoice.OPENAI) OpenAiTranscriber(http, credentials) else null
        TranscriberChain(AndroidSpeechTranscriber(appContext), cloud)
    }
    val voiceReceiver = VoiceReceiver(appContext, data, watchLink, transcriber)
    val issueSync = IssueSync(data, publisher)

    val messageHandler = PhoneMessageHandler(
        data = data, link = watchLink, publisher = publisher, scheduler = scheduler, executor = executor,
        continuations = continuations, phoneInstallId = { data.settings.value.phoneInstallId },
        appVersionName = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        hasKey = credentials::has, onVoiceOffer = voiceReceiver::onOffer,
    )

    init {
        appScope.launch { reconcileAfterRestart() }
    }

    // --------------------------------------------------------------------------- worker entry points

    suspend fun runAssistantRequest(requestId: String) {
        when (val claim = data.inbox.claim(requestId, System.currentTimeMillis())) {
            is ClaimResult.Claimed -> orchestrator.run(claim.request) { data.inbox.isCancelled(requestId) }
            // A previous worker died while this was RUNNING. Never silently re-run (a hidden repeat charge).
            is ClaimResult.Interrupted -> {
                orchestrator.recordInterrupted(claim.request)
            }
            else -> Unit
        }
    }

    suspend fun runConfirm(cmd: ConfirmCommand) {
        val outcome = executor.confirm(cmd.actionId, cmd.opId, cmd.revision, cmd.contentHash, cmd.by)
        watchLink.send(
            com.peaceantz.stagescope.shared.protocol.ActionReply(
                cmd.opId, cmd.actionId, outcome is com.peaceantz.stagescope.phone.assistant.ConfirmOutcome.Ran,
                outcome.record?.state ?: ActionState.CANCELLED, outcome.message,
            ),
        )
    }

    suspend fun runTranscription(memoId: String, requestId: String, sampleRateHz: Int) = voiceReceiver.transcribe(memoId, requestId, sampleRateHz)

    // ---------------------------------------------------------------------------- UI-facing helpers

    /** A request typed (or dictated) on the phone itself: same durable inbox, same worker, same orchestrator. */
    suspend fun submitFromPhone(
        text: String, kind: TaskKind = TaskKind.FREE_CHAT, conversationId: String = UUID.randomUUID().toString(),
        measurement: MeasurementContext? = null, editsActionId: String? = null,
    ): String {
        val request = AssistantRequest(
            requestId = UUID.randomUUID().toString(), conversationId = conversationId, taskKind = kind, userText = text,
            inputOrigin = InputOrigin.TYPED, transcriptReviewed = true, replyMode = ReplyMode.TEXT, measurement = measurement,
            performanceId = data.shows.library.selectedPerformanceId, createdAtWatchEpochMs = System.currentTimeMillis(), editsActionId = editsActionId,
        )
        data.inbox.accept(request, "phone", System.currentTimeMillis())
        scheduler.enqueueAssistant(request.requestId)
        return request.requestId
    }

    suspend fun cancelRequest(requestId: String) {
        data.inbox.cancel(requestId, System.currentTimeMillis())
        scheduler.cancelAssistant(requestId)
    }

    /**
     * Ask again after a failure or an interruption. The original request is re-sent *as it was* -- same
     * text, same measurement snapshot with its original capture time -- under a new request id, so
     * the evidence is preserved and the earlier attempt stays in the history instead of being rewritten.
     */
    suspend fun retry(requestId: String): String? {
        val original = data.inbox.get(requestId)?.request ?: return null
        val again = original.copy(requestId = UUID.randomUUID().toString())
        data.inbox.accept(again, "phone-retry", System.currentTimeMillis())
        scheduler.enqueueAssistant(again.requestId)
        return again.requestId
    }

    /**
     * The person tapped Confirm on the phone. The confirmation is built from the revision and content
     * hash of the version that was on screen; if the draft changed since, the ledger refuses it.
     * Runs through WorkManager like a watch confirmation, so it survives the app being closed mid-send.
     */
    fun confirmFromPhone(record: ActionRecord) {
        scheduler.enqueueConfirm(ConfirmCommand(record.actionId, UUID.randomUUID().toString(), record.revision, record.contentHash, ConfirmSource.PHONE))
    }

    suspend fun refreshWatchViews() = messageHandler.publishAll()

    /**
     * After a process death: a request left RUNNING becomes INTERRUPTED, and an action left EXECUTING
     * becomes OUTCOME_UNCERTAIN -- we cannot know whether the API call completed, so it is never
     * retried automatically and the person is asked to check.
     */
    private suspend fun reconcileAfterRestart() {
        val now = System.currentTimeMillis()
        data.inbox.state.value.entries.values
            .filter { it.state == com.peaceantz.stagescope.phone.data.InboxState.RUNNING && now - (it.startedAtEpochMs ?: it.receivedAtEpochMs) > STALE_RUNNING_MS }
            .forEach { data.inbox.finish(it.requestId, com.peaceantz.stagescope.phone.data.InboxState.INTERRUPTED, now) }
        data.actions.all().filter { it.state == ActionState.EXECUTING && now - (it.attempts.lastOrNull()?.startedAtEpochMs ?: it.updatedAtEpochMs) > STALE_RUNNING_MS }
            .forEach {
                data.actions.apply(it.actionId, ActionEvent.ExecutionUncertain(ActionError("interrupted", "StageScope was interrupted while this was being sent, so it's unknown whether it went through.")), now)
                publisher.actionChanged(data.actions.get(it.actionId) ?: it)
            }
    }

    companion object {
        /** Longer than any HTTP call timeout (8 min), so a genuinely in-flight request is never mislabelled. */
        const val STALE_RUNNING_MS = 10 * 60_000L
    }
}
