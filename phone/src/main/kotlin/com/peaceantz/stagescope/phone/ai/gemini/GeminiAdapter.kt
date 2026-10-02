package com.peaceantz.stagescope.phone.ai.gemini

import com.peaceantz.stagescope.phone.ai.core.EventSink
import com.peaceantz.stagescope.phone.ai.core.HttpErrors
import com.peaceantz.stagescope.phone.ai.core.KeyCheck
import com.peaceantz.stagescope.phone.ai.core.KeySource
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.ai.core.NeutralMessage
import com.peaceantz.stagescope.phone.ai.core.ProviderAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.core.ProviderLoopState
import com.peaceantz.stagescope.phone.ai.core.ProviderRequest
import com.peaceantz.stagescope.phone.ai.core.ProviderTurn
import com.peaceantz.stagescope.phone.ai.core.Redactor
import com.peaceantz.stagescope.phone.ai.core.Sse
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolCall
import com.peaceantz.stagescope.phone.ai.core.arr
import com.peaceantz.stagescope.phone.ai.core.get
import com.peaceantz.stagescope.phone.ai.core.long
import com.peaceantz.stagescope.phone.ai.core.obj
import com.peaceantz.stagescope.phone.ai.core.parseObject
import com.peaceantz.stagescope.phone.ai.core.str
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.SourceRef
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Google Gemini adapter on the **Interactions** API (`POST /v1beta/interactions`), which Google
 * documents (2026-10-01) as the recommended interface for new work -- not `generateContent`.
 *
 * Conversation state: always **stateless** (`store:false`). StageScope owns the provider-neutral
 * conversation, so nothing is retained at Google and a provider switch loses nothing.
 *
 * Tool lifecycle: a `function_call` step announces id + name at `step.start` and streams its
 * arguments as `arguments_delta` string fragments; they are joined and parsed at `step.stop`, and the
 * interaction finishes with status `requires_action`. The next request carries the earlier user/model
 * steps, **this turn's model steps exactly as received** (thought steps with their
 * `thought_signature` included -- Gemini 3 requires them for function calling), and a
 * `function_result` per call id. Those opaque steps stay in memory ([LoopState]) for the turn only.
 */
class GeminiAdapter(
    private val http: ProviderHttp,
    private val keys: KeySource,
    baseUrl: String = "https://generativelanguage.googleapis.com/",
) : ProviderAdapter {

    override val id: ProviderId = ProviderId.GEMINI
    private val base: HttpUrl = baseUrl.toHttpUrl()

    class LoopState(val steps: List<JsonObject>) : ProviderLoopState

    override suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn {
        val key = keys.key(id) ?: throw ProviderException(TurnErrorKind.MISSING_KEY, "No Gemini API key is set. Add it in Providers on your phone.")
        val prior = (request.loop as? LoopState)?.steps.orEmpty()
        val resultSteps = request.toolResults.map { r ->
            buildJsonObject {
                put("type", "function_result")
                put("call_id", r.callId)
                put("name", r.name)
                putJsonArray("result") { add(buildJsonObject { put("type", "text"); put("text", r.content) }) }
                if (r.isError) put("is_error", true)
            }
        }
        val replay = prior + resultSteps
        val httpRequest = http.jsonPost(
            base.newBuilder().addPathSegments("v1beta/interactions").build(),
            mapOf("x-goog-api-key" to key),
            buildBody(request, replay).toString(),
            stream = true,
        )
        val collector = Collector(onEvent)
        http.stream(httpRequest, errorMapper = ::mapHttpError) { response ->
            Sse.read(response.body.source()) { event ->
                if (event.data == "[DONE]") return@read false
                val json = parseObject(event.data) ?: return@read true
                collector.accept(json["event_type"].str() ?: event.event.orEmpty(), json)
                !collector.finished
            }
        }
        return collector.finish(replay, request.model)
    }

    override suspend fun validateKey(): KeyCheck {
        val key = keys.key(id) ?: return KeyCheck(false, TurnErrorKind.MISSING_KEY, "No key entered.")
        val result = http.execute(
            http.get(
                base.newBuilder().addPathSegments("v1beta/models").addQueryParameter("pageSize", "200").build(),
                mapOf("x-goog-api-key" to key),
            ),
        )
        if (result.status !in 200..299) {
            val e = mapHttpError(result.status, result.body, result.retryAfterMs)
            return KeyCheck(false, e.kind, e.message)
        }
        val models = parseObject(result.body)?.get("models").arr()?.mapNotNull { it.obj()?.get("name").str()?.removePrefix("models/") }.orEmpty()
        return KeyCheck(true, models = models)
    }

    // --- request ---------------------------------------------------------------------------------

    internal fun buildBody(request: ProviderRequest, replaySteps: List<JsonObject>): JsonObject {
        val info = ModelCatalog.find(id, request.model)
        return buildJsonObject {
            put("model", request.model)
            put("store", false)
            put("stream", true)
            put("system_instruction", request.system)
            putJsonObject("generation_config") {
                put("max_output_tokens", request.maxOutputTokens)
                info?.efforts?.get(request.effort)?.let { put("thinking_level", it) }
            }
            if (request.tools.isNotEmpty() || request.webSearch) {
                putJsonArray("tools") {
                    for (t in request.tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            },
                        )
                    }
                    if (request.webSearch) add(buildJsonObject { put("type", "google_search") })
                }
            }
            put("input", buildJsonArray {
                for (m in request.history) {
                    add(
                        buildJsonObject {
                            put("type", if (m is NeutralMessage.User) "user_input" else "model_output")
                            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", m.text) }) }
                        },
                    )
                }
                replaySteps.forEach { add(it) }
            })
        }
    }

    // --- errors ----------------------------------------------------------------------------------

    internal fun mapHttpError(status: Int, body: String, retryAfterMs: Long?): ProviderException {
        val err = parseObject(body)?.get("error").obj()
        val message = err?.get("message").str() ?: Redactor.text(body).take(160).ifBlank { null }
        val code = err?.get("status").str() ?: err?.get("code").str()
        // Google reports an unknown/retired model as 404 NOT_FOUND or 400 INVALID_ARGUMENT mentioning the model.
        return HttpErrors.map(status, message, code, retryAfterMs)
    }

    // --- stream assembly -------------------------------------------------------------------------

    private class Step(val start: JsonObject) {
        val type: String = start["type"].str().orEmpty()
        val text = StringBuilder()
        val args = StringBuilder()
        var signature: String? = null
        val summary = ArrayList<JsonElement>()
        val extras = LinkedHashMap<String, JsonElement>()
    }

    private inner class Collector(private val onEvent: EventSink) {
        var finished = false
        private val steps = java.util.TreeMap<Int, Step>()
        private val completed = java.util.TreeMap<Int, JsonObject>()
        private val sources = LinkedHashMap<String, SourceRef>()
        private var servedModel: String? = null
        private var status: String? = null
        private var usage: JsonObject? = null
        private var searchCalls = 0
        private var announcedThinking = false

        fun accept(type: String, e: JsonObject) {
            when (type) {
                "interaction.created" -> servedModel = e["interaction", "model"].str()
                "interaction.status_update", "interaction.in_progress", "interaction.requires_action" ->
                    e["status"].str()?.let { status = it }
                "step.start" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    val start = e["step"].obj() ?: return
                    val step = Step(start)
                    steps[index] = step
                    when (step.type) {
                        "function_call" -> onEvent(ProviderEvent.ToolCallStarted(start["name"].str().orEmpty()))
                        "thought" -> if (!announcedThinking) {
                            announcedThinking = true
                            onEvent(ProviderEvent.Thinking)
                        }
                        "google_search_call" -> {
                            searchCalls++
                            onEvent(ProviderEvent.WebSearching)
                        }
                    }
                }
                "step.delta" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    val step = steps[index] ?: return
                    val delta = e["delta"].obj() ?: return
                    when (delta["type"].str()) {
                        "text" -> delta["text"].str()?.let {
                            step.text.append(it)
                            onEvent(ProviderEvent.TextDelta(it))
                        }
                        "arguments_delta" -> step.args.append(delta["arguments"].str().orEmpty())
                        "thought_signature" -> step.signature = delta["signature"].str()
                        "thought_summary" -> delta["content"]?.let { step.summary += it }
                        "text_annotation_delta" -> delta["annotations"].arr()?.forEach { a ->
                            val o = a.obj() ?: return@forEach
                            if (o["type"].str() == "url_citation") {
                                val url = o["url"].str() ?: return@forEach
                                sources.putIfAbsent(url, SourceRef(o["title"].str()?.takeIf { it.isNotBlank() } ?: url, url))
                            }
                        }
                        else -> delta.forEach { (k, v) -> if (k != "type") step.extras[k] = v }
                    }
                }
                "step.stop" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    // An empty model_output (no text) is not echoed back: the API rejects empty text parts.
                    steps[index]?.let { s -> if (!(s.type == "model_output" && s.text.isEmpty())) completed[index] = assemble(s) }
                }
                "interaction.completed" -> {
                    val interaction = e["interaction"].obj()
                    status = interaction?.get("status").str() ?: status
                    usage = interaction?.get("usage").obj() ?: usage
                    servedModel = interaction?.get("model").str() ?: servedModel
                    finished = true
                }
                "error" -> {
                    val err = e["error"].obj()
                    val code = err?.get("code").str()
                    val httpish = when {
                        code == null -> 500
                        code.contains("exhausted", true) || code.contains("rate", true) -> 429
                        code.contains("unauthenticated", true) || code.contains("api_key", true) -> 401
                        code.contains("permission", true) -> 403
                        code.contains("not_found", true) -> 404
                        code.contains("timeout", true) || code.contains("unavailable", true) -> 503
                        else -> 500
                    }
                    throw HttpErrors.map(httpish, err?.get("message").str(), code, null)
                }
                else -> Unit
            }
        }

        /** Rebuilds a model step in the shape the API wants echoed back (signatures preserved). */
        private fun assemble(s: Step): JsonObject = when (s.type) {
            "model_output" -> buildJsonObject {
                put("type", "model_output")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", s.text.toString()) }) }
            }
            "thought" -> buildJsonObject {
                put("type", "thought")
                if (s.summary.isNotEmpty()) put("summary", JsonArray(s.summary))
                s.signature?.let { put("signature", it) }
            }
            "function_call" -> buildJsonObject {
                put("type", "function_call")
                put("id", s.start["id"].str().orEmpty())
                put("name", s.start["name"].str().orEmpty())
                val joined = s.args.toString()
                val args: JsonElement = if (joined.isNotBlank()) {
                    parseObject(joined) ?: throw ProviderException(TurnErrorKind.MALFORMED_RESPONSE, "The service sent an incomplete response (tool input was not valid JSON).")
                } else {
                    s.start["arguments"] ?: JsonObject(emptyMap())
                }
                put("arguments", args)
                s.start["signature"]?.let { put("signature", it) }
            }
            else -> buildJsonObject {
                // Server-side tool steps (google_search_call/result, ...): start fields + delta fields, unchanged.
                s.start.forEach { (k, v) -> put(k, v) }
                s.extras.forEach { (k, v) -> put(k, v) }
                s.signature?.let { put("signature", it) }
            }
        }

        fun finish(replay: List<JsonObject>, requestedModel: String): ProviderTurn {
            if (!finished) throw ProviderException(TurnErrorKind.NETWORK, "The response ended before it was complete.")
            val modelSteps = completed.values.toList()
            val calls = modelSteps.filter { it["type"].str() == "function_call" }
            val text = modelSteps.filter { it["type"].str() == "model_output" }
                .joinToString("") { it["content"].arr()?.joinToString("") { c -> c.obj()?.get("text").str().orEmpty() }.orEmpty() }
            val stop = when {
                calls.isNotEmpty() -> StopReason.TOOL_USE
                status == "incomplete" -> StopReason.MAX_TOKENS
                status == "failed" -> throw ProviderException(TurnErrorKind.SERVER_ERROR, "The service reported that the request failed.", retryable = true)
                status == "cancelled" -> throw ProviderException(TurnErrorKind.CANCELLED, "Cancelled.")
                else -> StopReason.END_TURN
            }
            val u = usage
            val summary = UsageSummary(
                inputTokens = (u?.get("total_input_tokens").long() ?: 0) + (u?.get("total_tool_use_tokens").long() ?: 0),
                cachedInputTokens = u?.get("total_cached_tokens").long() ?: 0,
                outputTokens = u?.get("total_output_tokens").long() ?: 0,
                reasoningTokens = u?.get("total_thought_tokens").long() ?: 0,
                webSearchCalls = searchCalls,
            )
            val model = servedModel ?: requestedModel
            return ProviderTurn(
                text = text,
                toolCalls = calls.map { ToolCall(it["id"].str().orEmpty(), it["name"].str().orEmpty(), (it["arguments"] ?: JsonObject(emptyMap())).toString()) },
                stop = stop,
                usage = summary.copy(estimatedCostMicros = ModelCatalog.estimateMicros(ProviderId.GEMINI, model, summary)),
                sources = sources.values.toList(),
                webSearchUsed = searchCalls > 0,
                loopState = if (calls.isNotEmpty()) LoopState(replay + modelSteps) else null,
                servedModel = servedModel,
            )
        }
    }
}
