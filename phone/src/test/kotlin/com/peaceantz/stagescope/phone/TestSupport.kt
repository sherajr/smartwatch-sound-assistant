package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.core.EventSink
import com.peaceantz.stagescope.phone.ai.core.ProviderAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.ProviderLoopState
import com.peaceantz.stagescope.phone.ai.core.ProviderRequest
import com.peaceantz.stagescope.phone.ai.core.ProviderTurn
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.KeyCheck
import com.peaceantz.stagescope.phone.ai.core.ToolCall
import com.peaceantz.stagescope.phone.assistant.AssistantEvents
import com.peaceantz.stagescope.phone.assistant.AssistantOrchestrator
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.tools.ToolRegistry
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.show.Contact
import com.peaceantz.stagescope.shared.show.Performance
import com.peaceantz.stagescope.shared.show.Production
import com.peaceantz.stagescope.shared.show.RecipientGroup
import com.peaceantz.stagescope.shared.show.ShowLibrary
import java.io.File
import java.time.ZoneId

/** A scripted provider: returns/throws the queued steps in order and records every request it was given. */
class ScriptedAdapter(override val id: ProviderId, private val script: MutableList<Any>) : ProviderAdapter {
    val requests = mutableListOf<ProviderRequest>()
    var calls = 0

    override suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn {
        calls++
        requests += request
        return when (val step = script.removeAt(0)) {
            is ProviderException -> throw step
            is Emit -> {
                onEvent(ProviderEvent.TextDelta(step.text))
                throw step.then
            }
            is ProviderTurn -> step
            else -> error("bad script step")
        }
    }

    override suspend fun validateKey() = KeyCheck(true)

    /** Emit some text, then fail -- a mid-stream failure. */
    class Emit(val text: String, val then: ProviderException)

    companion object {
        class Loop : ProviderLoopState

        fun text(t: String, usage: UsageSummary = UsageSummary(inputTokens = 100, outputTokens = 20)) =
            ProviderTurn(t, emptyList(), StopReason.END_TURN, usage, emptyList(), false, null, null)

        fun tool(name: String, args: String, id: String = "call_1", preface: String = "") =
            ProviderTurn(preface, listOf(ToolCall(id, name, args)), StopReason.TOOL_USE, UsageSummary(inputTokens = 50, outputTokens = 10), emptyList(), false, Loop(), null)
    }
}

class RecordingEvents : AssistantEvents {
    val progress = mutableListOf<ProgressStage>()
    val results = mutableListOf<Pair<String, RequestState>>()
    var continuation: com.peaceantz.stagescope.phone.tools.ContinueTarget? = null
    override fun progress(requestId: String, stage: ProgressStage, text: String?) { progress += stage }
    override suspend fun resultReady(requestId: String, conversationId: String, state: RequestState) { results += requestId to state }
    override suspend fun continuationRequested(target: com.peaceantz.stagescope.phone.tools.ContinueTarget) { continuation = target }
}

object Samples {
    val production = Production("prod-1", "Our Town", venue = "Grover's Corners Playhouse", timezoneId = "America/New_York", defaultKeepList = "Theatre Supplies")
    val performance = Performance("perf-1", "prod-1", "2026-03-14", "19:30", number = 12)
    val library = ShowLibrary(
        productions = listOf(production),
        performances = listOf(performance),
        contacts = listOf(
            Contact("c1", "Dana Whitfield", "dana@theatre.example", verified = true, role = "Production manager"),
            Contact("c2", "Priya Shah", "priya@theatre.example", verified = true, role = "Stage manager"),
        ),
        groups = listOf(RecipientGroup("g1", "Production team", listOf("c1", "c2"))),
        selectedProductionId = "prod-1", selectedPerformanceId = "perf-1",
    )

    fun request(
        text: String = "Log an issue: mic 12 crackled during the opening number. Swapped the cable at intermission; seems resolved.",
        id: String = "req-1", conversation: String = "conv-1", kind: TaskKind = TaskKind.LOG_ISSUE,
        edits: String? = null,
    ) = AssistantRequest(
        requestId = id, conversationId = conversation, taskKind = kind, userText = text,
        inputOrigin = InputOrigin.SPEECH_WATCH, transcriptReviewed = true, replyMode = ReplyMode.TEXT,
        createdAtWatchEpochMs = 1_000L, editsActionId = edits,
    )
}

class Harness(dir: File, val adapters: MutableMap<ProviderId, ProviderAdapter> = mutableMapOf()) {
    val data = PhoneData(dir)
    val events = RecordingEvents()
    var now = 1_800_000_000_000L // 2027-01-15, a fixed clock
    var idCounter = 0
    val backoffs = mutableListOf<Long>()
    val tools = ToolRegistry(data) { "id-" + (++idCounter) }
    var google: String? = "sound@example.com"
    val orchestrator = AssistantOrchestrator(
        data = data,
        adapters = { adapters[it] },
        tools = tools,
        events = events,
        googleEmail = { google },
        zone = { ZoneId.of("America/New_York") },
        clock = { now },
        newId = { "id-" + (++idCounter) },
        backoff = { backoffs += it },
    )

    suspend fun withShows(library: ShowLibrary = Samples.library) = data.shows.update { library }
}
