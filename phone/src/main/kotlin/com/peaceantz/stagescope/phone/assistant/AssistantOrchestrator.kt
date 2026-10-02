package com.peaceantz.stagescope.phone.assistant

import com.peaceantz.stagescope.phone.ai.core.EventSink
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.ai.core.NeutralMessage
import com.peaceantz.stagescope.phone.ai.core.ProviderAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.ProviderLoopState
import com.peaceantz.stagescope.phone.ai.core.ProviderRequest
import com.peaceantz.stagescope.phone.ai.core.ProviderTurn
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolResult
import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.data.UsageEntry
import com.peaceantz.stagescope.phone.data.UsageVerdict
import com.peaceantz.stagescope.phone.data.UsageGuard
import com.peaceantz.stagescope.phone.tools.ContinueTarget
import com.peaceantz.stagescope.phone.tools.ToolContext
import com.peaceantz.stagescope.phone.tools.ToolRegistry
import com.peaceantz.stagescope.phone.tools.ToolSession
import com.peaceantz.stagescope.shared.actions.RetryPolicy
import com.peaceantz.stagescope.shared.assistant.ChatTurn
import com.peaceantz.stagescope.shared.assistant.ConversationWindow
import com.peaceantz.stagescope.shared.assistant.MeasurementRef
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.SourceRef
import com.peaceantz.stagescope.shared.assistant.ToolOutcome
import com.peaceantz.stagescope.shared.assistant.TurnError
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.assistant.TurnRole
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.ThreadViewBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** Where the orchestrator reports to the outside world (the watch link, notifications, phone speech). */
interface AssistantEvents {
    fun progress(requestId: String, stage: ProgressStage, text: String? = null)

    /** Called after the durable result (conversation + actions) is persisted. */
    suspend fun resultReady(requestId: String, conversationId: String, state: RequestState)

    suspend fun continuationRequested(target: ContinueTarget) {}
}

data class RunOutcome(val state: RequestState, val conversationId: String, val error: TurnError? = null)

/**
 * Runs one assistant request end to end: conversation + measurement context -> a provider-neutral
 * history -> the selected provider (one real HTTP request at a time, streamed) -> a bounded tool
 * loop with local validation -> a durable assistant turn and a durable watch view.
 *
 * Properties the spec cares about:
 * - **No hidden vendor switching**: a failure is reported with the provider/model that failed; the
 *   next provider is only ever used if the person selects it.
 * - **Provider switching keeps context without replaying side effects**: history is neutral text
 *   plus "StageScope already did X" notes; vendor reasoning/signatures/response ids never leave the
 *   one in-memory tool loop that produced them.
 * - **No hidden duplicate charges**: retries happen only for transient failures *before any output*
 *   was produced, never mid-stream, and a request that was already running when its process died is
 *   marked interrupted rather than silently re-run (see `RequestInbox.claim`).
 */
class AssistantOrchestrator(
    private val data: PhoneData,
    private val adapters: (ProviderId) -> ProviderAdapter?,
    private val tools: ToolRegistry,
    private val events: AssistantEvents,
    private val googleEmail: () -> String?,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val backoff: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun run(request: AssistantRequest, isCancelled: () -> Boolean): RunOutcome {
        val now = clock()
        val settings = data.settings.value
        val provider = settings.selectedProvider
        val modelId = settings.modelFor(provider)
        val library = data.shows.library
        val conv = data.conversations.createIfMissing(
            request.conversationId, provider, modelId, titleFrom(request.userText), request.performanceId ?: library.selectedPerformanceId, now,
        )
        data.conversations.update(conv.id, now) { it.copy(providerId = provider, modelId = modelId, lastRequestId = request.requestId) }

        // Preserve the evidence: the pre-speech snapshot is stored under its own capture time.
        request.measurement?.let { archive(conv.id, it, now) }
        request.comparisonBefore?.let { archive(conv.id, it, now) }
        appendUserTurn(request, now)

        val verdict = UsageGuard.check(settings.limits, data.usage.state.value.entries, now, zone())
        if (verdict is UsageVerdict.Blocked) {
            return finishWithError(request, provider, modelId, TurnError(TurnErrorKind.LIMIT_REACHED, verdict.message), UsageSummary(), emptyList(), now)
        }

        val adapter = adapters(provider)
            ?: return finishWithError(request, provider, modelId, TurnError(TurnErrorKind.MISSING_KEY, "${provider.label} isn't set up. Open StageScope on your phone → Providers."), UsageSummary(), emptyList(), now)

        events.progress(request.requestId, ProgressStage.THINKING)
        val info = ModelCatalog.find(provider, modelId)
        val webOn = settings.webSearchFor(provider) && info?.supportsWebSearch == true
        val editing = request.editsActionId?.let { data.actions.get(it) }?.takeIf { it.conversationId == conv.id }
        val system = SystemPrompt.build(
            SystemPrompt.Inputs(provider, modelId, now, zone(), library, request, webOn, info?.supportsWebSearch == true, settings.googleAccountEmail != null, editing),
        )
        val history = neutralHistory(data.conversations.get(conv.id)!!, request, now, settings.limits.maxContextChars)
        val session = ToolSession()
        val ctx = ToolContext(
            request = request, conversationId = conv.id, userSaid = userWords(data.conversations.get(conv.id)!!),
            library = library, nowEpochMs = now, phoneZone = zone(), googleAccountEmail = googleEmail(),
            defaultCalendarId = settings.defaultCalendarId, defaultCalendarLabel = settings.defaultCalendarLabel,
        )

        val texts = ArrayList<String>()
        val outcomes = ArrayList<ToolOutcome>()
        val sources = LinkedHashMap<String, SourceRef>()
        var usage = UsageSummary()
        var searchUsed = false
        var loop: ProviderLoopState? = null
        var results: List<ToolResult> = emptyList()
        var iterations = 0
        var finalStop = StopReason.END_TURN
        var servedModel: String? = null
        // Text streamed by the call in flight, so a mid-stream failure still shows what arrived.
        val streamed = StringBuilder()

        try {
            while (true) {
                if (isCancelled()) throw CancellationException("cancelled by the person")
                if (++iterations > settings.limits.maxToolLoops) {
                    throw ProviderException(TurnErrorKind.TOOL_LOOP_LIMIT, "The assistant kept calling tools without finishing, so it was stopped.")
                }
                streamed.setLength(0)
                val turn = callWithRetry(adapter, request.requestId, streamed, ProviderRequest(
                    model = modelId, system = system, history = history, tools = tools.specs(),
                    maxOutputTokens = settings.limits.maxOutputTokens.coerceAtMost(info?.maxOutputTokens?.toInt() ?: Int.MAX_VALUE),
                    effort = settings.effortFor(provider), webSearch = webOn, loop = loop, toolResults = results,
                ))
                usage = usage + turn.usage
                servedModel = turn.servedModel ?: servedModel
                turn.sources.forEach { sources.putIfAbsent(it.url, it) }
                searchUsed = searchUsed || turn.webSearchUsed
                if (turn.text.isNotBlank()) texts += turn.text.trim()
                finalStop = turn.stop

                when (turn.stop) {
                    StopReason.TOOL_USE -> {
                        if (turn.loopState == null) throw ProviderException(TurnErrorKind.MALFORMED_RESPONSE, "The service asked for a tool but sent no continuation.")
                        events.progress(request.requestId, ProgressStage.USING_TOOL, turn.toolCalls.firstOrNull()?.name)
                        val ran = ArrayList<ToolResult>()
                        for (call in turn.toolCalls) {
                            if (isCancelled()) throw CancellationException("cancelled by the person")
                            // Each call executes exactly once, in order; its result goes straight back to the model.
                            val r = tools.execute(call, ctx, session)
                            outcomes += r.outcome
                            ran += r.result
                        }
                        results = ran
                        loop = turn.loopState
                    }
                    StopReason.PAUSE -> {
                        loop = turn.loopState
                        results = emptyList()
                    }
                    else -> break
                }
            }
        } catch (e: CancellationException) {
            // The person pressing Cancel is recorded in the inbox; anything else (WorkManager stopping the
            // job for battery/system limits) is an interruption and must not read as a user decision.
            val byUser = isCancelled()
            return withContext(NonCancellable) { finishCancelled(request, provider, modelId, usage, outcomes, session, clock(), byUser) }
        } catch (e: ProviderException) {
            return withContext(NonCancellable) {
                finishWithError(request, provider, servedModel ?: modelId, TurnError(e.kind, e.message, e.retryable), usage, outcomes, clock(), session, (texts + streamed.toString().trim()).filter { it.isNotBlank() }.joinToString("\n\n"), sources.values.toList())
            }
        }

        return withContext(NonCancellable) {
            events.progress(request.requestId, ProgressStage.FINISHING)
            val full = texts.joinToString("\n\n")
            val (summary, body) = ThreadViewBuilder.splitWatchSummary(full)
            val shown = body.ifBlank { summary.orEmpty() }.let {
                when (finalStop) {
                    StopReason.MAX_TOKENS -> "$it\n\n(The answer was cut off at the output limit. Ask me to continue.)"
                    StopReason.REFUSAL -> it.ifBlank { "The model declined to answer that." }
                    else -> it
                }
            }
            val error = when (finalStop) {
                StopReason.REFUSAL -> TurnError(TurnErrorKind.CONTENT_REFUSED, "The model declined to answer that.")
                StopReason.MAX_TOKENS -> TurnError(TurnErrorKind.UNKNOWN, "The answer was cut off at the output limit.", retryable = false)
                else -> null
            }
            val turn = assistantTurn(request, provider, servedModel ?: modelId, shown, summary, outcomes, session, usage, sources.values.toList(), if (webOn) searchUsed else null, error, clock())
            persistAssistant(request, turn, usage, provider, servedModel ?: modelId, clock(), ok = error == null || finalStop == StopReason.MAX_TOKENS)
            session.continuation?.let { events.continuationRequested(it) }
            events.resultReady(request.requestId, conv.id, RequestState.COMPLETED)
            RunOutcome(RequestState.COMPLETED, conv.id, error)
        }
    }

    // --------------------------------------------------------------------------------------- calls

    /**
     * One provider call with bounded retry. A retry is only allowed when nothing was produced by
     * the failed attempt (no text, no tool call): otherwise it could repeat output the person already
     * saw or a side effect, and double-charge. Cancellation is never retried.
     */
    private suspend fun callWithRetry(adapter: ProviderAdapter, requestId: String, partial: StringBuilder, req: ProviderRequest): ProviderTurn {
        var attempt = 0
        while (true) {
            var produced = false
            partial.setLength(0)
            val sink: EventSink = { e ->
                when (e) {
                    is ProviderEvent.TextDelta -> {
                        if (!produced) events.progress(requestId, ProgressStage.WRITING)
                        produced = true
                        partial.append(e.text)
                    }
                    is ProviderEvent.ToolCallStarted -> produced = true
                    ProviderEvent.WebSearching -> events.progress(requestId, ProgressStage.USING_TOOL, "Searching the web")
                    ProviderEvent.Thinking -> Unit
                }
            }
            try {
                return adapter.runTurn(req, sink)
            } catch (e: ProviderException) {
                val mayRetry = e.retryable && !produced && attempt < RetryPolicy.AI_MAX_RETRIES
                if (!mayRetry) throw e
                attempt++
                backoff(minOf(e.retryAfterMs ?: (1_000L shl (attempt - 1)), 20_000L))
            }
        }
    }

    // ----------------------------------------------------------------------------------- history

    private fun neutralHistory(conversation: com.peaceantz.stagescope.shared.assistant.Conversation, request: AssistantRequest, now: Long, maxChars: Int): List<NeutralMessage> {
        val window = ConversationWindow.build(conversation)
        // The current user turn is already appended; render it with its data block instead of plain text.
        val prior = window.turns.filter { it.requestId != request.requestId || it.role != TurnRole.USER }
        val out = ArrayList<NeutralMessage>()
        window.summaryText?.takeIf { it.isNotBlank() }?.let {
            out += NeutralMessage.User("[StageScope's summary of the earlier part of this conversation, for context only]\n$it")
            out += NeutralMessage.Assistant("Understood.")
        }
        for (t in prior) when (t.role) {
            TurnRole.USER -> out += NeutralMessage.User(t.text)
            TurnRole.ASSISTANT -> out += NeutralMessage.Assistant(renderAssistantForHistory(t))
            TurnRole.NOTE -> Unit
        }
        out += NeutralMessage.User(SystemPrompt.userMessage(request, now))
        // Bound the context: drop the oldest turns first, never the current request.
        var total = out.sumOf { it.text.length }
        while (total > maxChars && out.size > 1) {
            total -= out.removeAt(0).text.length
        }
        if (out.first() is NeutralMessage.Assistant && out.size > 1) out.removeAt(0)
        return out
    }

    /** What an earlier assistant turn contributes to a (possibly different) provider's context. */
    private fun renderAssistantForHistory(t: ChatTurn): String = buildString {
        append(t.text)
        if (t.toolOutcomes.isNotEmpty()) {
            append("\n\n[StageScope already completed these actions in that turn — do not repeat them: ")
            append(t.toolOutcomes.joinToString("; ") { "${it.toolName} → ${it.resultSummary}" })
            append("]")
        }
        t.error?.let { append("\n\n[That reply ended with an error: ${it.message}]") }
    }

    private fun userWords(conversation: com.peaceantz.stagescope.shared.assistant.Conversation): String =
        conversation.turns.filter { it.role == TurnRole.USER }.joinToString("\n") { it.text }

    // --------------------------------------------------------------------------------- persistence

    private suspend fun appendUserTurn(request: AssistantRequest, now: Long) {
        val turnId = "${request.requestId}:u"
        val conv = data.conversations.get(request.conversationId) ?: return
        if (conv.turns.any { it.id == turnId }) return
        data.conversations.update(request.conversationId, now) { c ->
            c.copy(
                turns = c.turns + ChatTurn(
                    id = turnId, role = TurnRole.USER, text = request.userText.trim(), seq = c.nextSeq, atEpochMs = now,
                    taskKind = request.taskKind, inputOrigin = request.inputOrigin, requestId = request.requestId,
                    measurementIds = listOfNotNull(request.measurement?.snapshotId, request.comparisonBefore?.snapshotId),
                ),
                nextSeq = c.nextSeq + 1,
            )
        }
    }

    private suspend fun archive(conversationId: String, m: MeasurementContext, now: Long) {
        data.measurements.put(m)
        data.conversations.addMeasurementRef(
            conversationId,
            MeasurementRef(
                snapshotId = m.snapshotId, capturedAtEpochMs = m.capturedAtEpochMs, origin = m.origin.name, isDemo = m.device.isDemo,
                headline = headline(m),
            ),
            now,
        )
    }

    private fun headline(m: MeasurementContext): String = buildString {
        if (m.device.isDemo) append("DEMO · ")
        m.level?.let { append("RMS ${"%.1f".format(it.rmsDbfs)} dBFS") }
        if (m.rings.isNotEmpty()) append(" · ${m.rings.size} ring${if (m.rings.size == 1) "" else "s"}")
    }.trim(' ', '·')

    private fun assistantTurn(
        request: AssistantRequest, provider: ProviderId, modelId: String, text: String, summary: String?,
        outcomes: List<ToolOutcome>, session: ToolSession, usage: UsageSummary, sources: List<SourceRef>,
        webSearchUsed: Boolean?, error: TurnError?, now: Long,
    ): ChatTurn = ChatTurn(
        id = "${request.requestId}:a", role = TurnRole.ASSISTANT, text = text, seq = 0, atEpochMs = now,
        providerId = provider, modelId = modelId, taskKind = request.taskKind, requestId = request.requestId,
        toolOutcomes = outcomes, actionIds = session.actionIds.toList(), sources = sources,
        watchSummary = summary, usage = usage, error = error, webSearchUsed = webSearchUsed,
    )

    private suspend fun persistAssistant(request: AssistantRequest, turn: ChatTurn, usage: UsageSummary, provider: ProviderId, modelId: String, now: Long, ok: Boolean) {
        data.conversations.update(request.conversationId, now) { c ->
            if (c.turns.any { it.id == turn.id }) c
            else c.copy(turns = c.turns + turn.copy(seq = c.nextSeq), nextSeq = c.nextSeq + 1)
        }
        // A test-mode answer never reached a provider, so it must not use up the real daily allowance: it is recorded, but as "test".
        data.usage.record(UsageEntry(request.requestId, now, provider, modelId, usage, if (data.settings.value.devMode) "test" else "chat", ok))
        data.inbox.finish(request.requestId, InboxState.COMPLETED, now, turn.error, data.conversations.revision(request.conversationId))
    }

    private suspend fun finishWithError(
        request: AssistantRequest, provider: ProviderId, modelId: String, error: TurnError, usage: UsageSummary,
        outcomes: List<ToolOutcome>, now: Long, session: ToolSession = ToolSession(), partialText: String = "", sources: List<SourceRef> = emptyList(),
    ): RunOutcome {
        val (summary, body) = ThreadViewBuilder.splitWatchSummary(partialText)
        val turn = assistantTurn(request, provider, modelId, body.ifBlank { summary.orEmpty() }, null, outcomes, session, usage, sources, null, error, now)
        data.conversations.update(request.conversationId, now) { c ->
            if (c.turns.any { it.id == turn.id }) c else c.copy(turns = c.turns + turn.copy(seq = c.nextSeq), nextSeq = c.nextSeq + 1)
        }
        if (usage != UsageSummary()) data.usage.record(UsageEntry(request.requestId, now, provider, modelId, usage, "chat", false))
        data.inbox.finish(request.requestId, InboxState.FAILED, now, error, data.conversations.revision(request.conversationId))
        events.resultReady(request.requestId, request.conversationId, RequestState.FAILED)
        return RunOutcome(RequestState.FAILED, request.conversationId, error)
    }

    private suspend fun finishCancelled(
        request: AssistantRequest, provider: ProviderId, modelId: String, usage: UsageSummary,
        outcomes: List<ToolOutcome>, session: ToolSession, now: Long, byUser: Boolean = true,
    ): RunOutcome {
        val done = if (outcomes.isNotEmpty()) " Anything already done by StageScope (${outcomes.joinToString { it.toolName }}) is not undone." else ""
        val note = if (byUser) "Cancelled.$done" else "The phone stopped this task before it finished (system limits such as battery). Tap Retry.$done"
        val error = TurnError(if (byUser) TurnErrorKind.CANCELLED else TurnErrorKind.TIMEOUT, note, retryable = !byUser)
        val turn = assistantTurn(request, provider, modelId, note, null, outcomes, session, usage, emptyList(), null, error, now)
        data.conversations.update(request.conversationId, now) { c ->
            if (c.turns.any { it.id == turn.id }) c else c.copy(turns = c.turns + turn.copy(seq = c.nextSeq), nextSeq = c.nextSeq + 1)
        }
        if (usage != UsageSummary()) data.usage.record(UsageEntry(request.requestId, now, provider, modelId, usage, "chat", false))
        data.inbox.finish(request.requestId, if (byUser) InboxState.CANCELLED else InboxState.INTERRUPTED, now, error, data.conversations.revision(request.conversationId))
        val finalState = if (byUser) RequestState.CANCELLED else RequestState.FAILED
        events.resultReady(request.requestId, request.conversationId, finalState)
        return RunOutcome(finalState, request.conversationId, error)
    }

    /**
     * A previous worker process died while this request was running. The inbox already marked it
     * INTERRUPTED; here the conversation records that fact too, so the person sees an honest, durable
     * "stopped before finishing -- tap Retry" instead of a request that silently never answers. The
     * request is deliberately NOT re-run: that could repeat a billed model call or a tool's effect.
     */
    suspend fun recordInterrupted(request: AssistantRequest): RunOutcome {
        val now = clock()
        val settings = data.settings.value
        val provider = settings.selectedProvider
        val modelId = settings.modelFor(provider)
        data.conversations.createIfMissing(
            request.conversationId, provider, modelId, titleFrom(request.userText), request.performanceId ?: data.shows.library.selectedPerformanceId, now,
        )
        appendUserTurn(request, now)
        val error = TurnError(
            TurnErrorKind.TIMEOUT,
            "The phone stopped before this finished, so StageScope can't tell whether the AI service already processed it. It was not re-run automatically. Tap Retry to ask again.",
            retryable = true,
        )
        val turn = assistantTurn(request, provider, modelId, error.message, null, emptyList(), ToolSession(), UsageSummary(), emptyList(), null, error, now)
        data.conversations.update(request.conversationId, now) { c ->
            if (c.turns.any { it.id == turn.id }) c else c.copy(turns = c.turns + turn.copy(seq = c.nextSeq), nextSeq = c.nextSeq + 1)
        }
        data.inbox.finish(request.requestId, InboxState.INTERRUPTED, now, error, data.conversations.revision(request.conversationId))
        events.resultReady(request.requestId, request.conversationId, RequestState.FAILED)
        return RunOutcome(RequestState.FAILED, request.conversationId, error)
    }

    private fun titleFrom(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(48)

    private operator fun UsageSummary.plus(o: UsageSummary) = UsageSummary(
        inputTokens + o.inputTokens, cachedInputTokens + o.cachedInputTokens, outputTokens + o.outputTokens,
        reasoningTokens + o.reasoningTokens, webSearchCalls + o.webSearchCalls,
        sumNullable(estimatedCostMicros, o.estimatedCostMicros), sumNullable(reportedCostMicros, o.reportedCostMicros),
    )

    private fun sumNullable(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
}
