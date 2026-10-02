package com.peaceantz.stagescope.phone.ai.responses

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
 * Adapter for APIs that speak the OpenAI **Responses** wire format: OpenAI itself
 * (`POST /v1/responses`) and xAI Grok (`POST /v1/responses`, same schema). The two are separate
 * adapters with separate request flavours, error bodies and cost fields -- they only share the
 * event grammar below.
 *
 * Tool lifecycle (verified against both vendors' docs on 2026-10-01):
 *  - a function call arrives as an output item `{type:"function_call", call_id, name, arguments}`;
 *  - OpenAI streams `response.function_call_arguments.delta` fragments, then `.done`, then
 *    `response.output_item.done` with the complete item; xAI delivers the call whole in one chunk;
 *  - arguments are only trusted from the completed item (or the joined fragments), never mid-stream;
 *  - the next request replays this turn's output items (including encrypted reasoning, which
 *    `store:false` makes mandatory to echo back) plus a `function_call_output` per call_id.
 */
class ResponsesAdapter(
    override val id: ProviderId,
    private val http: ProviderHttp,
    private val keys: KeySource,
    baseUrl: String,
    private val flavor: Flavor,
) : ProviderAdapter {

    enum class Flavor { OPENAI, XAI }

    private val base: HttpUrl = baseUrl.toHttpUrl()

    class LoopState(val replay: List<JsonObject>) : ProviderLoopState

    override suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn {
        val key = keys.key(id) ?: throw ProviderException(TurnErrorKind.MISSING_KEY, "No ${id.shortLabelForErrors()} API key is set. Add it in Providers on your phone.")
        val body = buildBody(request)
        val httpRequest = http.jsonPost(
            base.newBuilder().addPathSegments("v1/responses").build(),
            mapOf("Authorization" to "Bearer $key"),
            body.toString(),
            stream = true,
        )
        val collector = Collector(onEvent)
        http.stream(httpRequest, errorMapper = ::mapHttpError) { response ->
            Sse.read(response.body.source()) { event ->
                if (event.data == "[DONE]") return@read false
                val json = parseObject(event.data) ?: return@read true // tolerate keep-alive junk
                collector.accept(json)
                !collector.finished
            }
        }
        val priorReplay = (request.loop as? LoopState)?.replay.orEmpty()
        val resultItems = request.toolResults.map { r ->
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", r.callId)
                put("output", r.content)
            }
        }
        return collector.finish(id, priorReplay + resultItems)
    }

    override suspend fun validateKey(): KeyCheck {
        val key = keys.key(id) ?: return KeyCheck(false, TurnErrorKind.MISSING_KEY, "No key entered.")
        val result = http.execute(http.get(base.newBuilder().addPathSegments("v1/models").build(), mapOf("Authorization" to "Bearer $key")))
        if (result.status !in 200..299) {
            val e = mapHttpError(result.status, result.body, result.retryAfterMs)
            return KeyCheck(false, e.kind, e.message)
        }
        val models = parseObject(result.body)?.get("data").arr()?.mapNotNull { it.obj()?.get("id").str() }.orEmpty()
        return KeyCheck(true, models = models)
    }

    // --- request ---------------------------------------------------------------------------------

    internal fun buildBody(request: ProviderRequest): JsonObject {
        val info = ModelCatalog.find(id, request.model)
        return buildJsonObject {
            put("model", request.model)
            put("instructions", request.system)
            put("stream", true)
            // Privacy + determinism: StageScope owns the conversation; nothing is stored server-side.
            put("store", false)
            put("max_output_tokens", request.maxOutputTokens)
            info?.efforts?.get(request.effort)?.let { e -> putJsonObject("reasoning") { put("effort", e) } }
            if (flavor == Flavor.XAI) {
                putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
            }
            if (request.tools.isNotEmpty() || request.webSearch) {
                put("tool_choice", "auto")
                putJsonArray("tools") {
                    for (t in request.tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                                // Our own validator checks arguments; strict mode would force every field required.
                                put("strict", false)
                            },
                        )
                    }
                    if (request.webSearch) add(buildJsonObject { put("type", "web_search") })
                }
            }
            put("input", inputItems(request))
        }
    }

    private fun inputItems(request: ProviderRequest): JsonArray = buildJsonArray {
        for (m in request.history) {
            add(
                buildJsonObject {
                    put("role", if (m is NeutralMessage.User) "user" else "assistant")
                    put("content", m.text)
                },
            )
        }
        val replay = (request.loop as? LoopState)?.replay.orEmpty()
        replay.forEach { add(it) }
        for (r in request.toolResults) {
            add(
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", r.callId)
                    put("output", r.content)
                },
            )
        }
    }

    // --- errors ----------------------------------------------------------------------------------

    internal fun mapHttpError(status: Int, body: String, retryAfterMs: Long?): ProviderException {
        val parsed = parseError(body)
        return HttpErrors.map(status, parsed.first, parsed.second, retryAfterMs)
    }

    /** OpenAI: `{"error":{"message","code"}}`. xAI: `{"code":..., "error":"text"}` or the OpenAI shape. */
    private fun parseError(body: String): Pair<String?, String?> {
        val json = parseObject(body) ?: return Redactor.text(body).take(160) to null
        val err = json["error"]
        return when (err) {
            is JsonObject -> err["message"].str() to (err["code"].str() ?: err["type"].str())
            is JsonPrimitive -> err.str() to json["code"].str()
            else -> json["message"].str() to json["code"].str()
        }
    }

    private fun ProviderId.shortLabelForErrors(): String = if (this == ProviderId.OPENAI) "OpenAI" else "xAI"

    // --- stream assembly -------------------------------------------------------------------------

    private inner class Collector(private val onEvent: EventSink) {
        var finished = false
        private val textDeltas = StringBuilder()
        private val addedItems = HashMap<Int, JsonObject>()
        private val argFragments = HashMap<Int, StringBuilder>()
        private val finalItems = java.util.TreeMap<Int, JsonObject>()
        private val sources = LinkedHashMap<String, SourceRef>()
        private var servedModel: String? = null
        private var usage: UsageSummary? = null
        private var status: String? = null
        private var incompleteReason: String? = null
        private var announcedThinking = false

        fun accept(e: JsonObject) {
            when (e["type"].str()) {
                "response.created", "response.in_progress" -> {
                    e["response", "model"].str()?.let { servedModel = it }
                }
                "response.output_item.added" -> {
                    val index = e["output_index"].long()?.toInt() ?: return
                    val item = e["item"].obj() ?: return
                    addedItems[index] = item
                    when (item["type"].str()) {
                        "function_call" -> onEvent(ProviderEvent.ToolCallStarted(item["name"].str().orEmpty()))
                        "reasoning" -> if (!announcedThinking) {
                            announcedThinking = true
                            onEvent(ProviderEvent.Thinking)
                        }
                        "web_search_call" -> onEvent(ProviderEvent.WebSearching)
                    }
                }
                "response.output_text.delta" -> e["delta"].str()?.let {
                    textDeltas.append(it)
                    onEvent(ProviderEvent.TextDelta(it))
                }
                "response.function_call_arguments.delta" -> {
                    val index = e["output_index"].long()?.toInt() ?: return
                    argFragments.getOrPut(index) { StringBuilder() }.append(e["delta"].str().orEmpty())
                }
                "response.function_call_arguments.done" -> {
                    val index = e["output_index"].long()?.toInt() ?: return
                    e["arguments"].str()?.let { argFragments[index] = StringBuilder(it) }
                }
                "response.output_item.done" -> {
                    val index = e["output_index"].long()?.toInt() ?: return
                    e["item"].obj()?.let { finalItems[index] = it }
                }
                "response.output_text.annotation.added" -> e["annotation"].obj()?.let(::collectSource)
                "response.completed" -> {
                    absorbResponse(e["response"].obj())
                    finished = true
                }
                "response.incomplete" -> {
                    absorbResponse(e["response"].obj())
                    incompleteReason = e["response", "incomplete_details", "reason"].str() ?: "incomplete"
                    finished = true
                }
                "response.failed" -> {
                    val err = e["response", "error"].obj()
                    throw streamError(err?.get("code").str(), err?.get("message").str())
                }
                "error" -> throw streamError(e["code"].str(), e["message"].str())
                else -> Unit // content_part.*, reasoning_summary_*, file_search_*, etc. are not needed
            }
        }

        private fun absorbResponse(r: JsonObject?) {
            if (r == null) return
            r["model"].str()?.let { servedModel = it }
            status = r["status"].str() ?: status
            r["usage"].obj()?.let { u ->
                val ticks = u["cost_in_usd_ticks"].long()
                usage = UsageSummary(
                    inputTokens = u["input_tokens"].long() ?: 0,
                    cachedInputTokens = u["input_tokens_details", "cached_tokens"].long() ?: 0,
                    outputTokens = u["output_tokens"].long() ?: 0,
                    reasoningTokens = u["output_tokens_details", "reasoning_tokens"].long() ?: 0,
                    // xAI reports the exact cost: 1 USD = 1e10 ticks, so micro-USD = ticks / 1e4.
                    reportedCostMicros = ticks?.let { it / 10_000L },
                )
            }
            // Some servers only put the complete output on the final response object.
            r["output"].arr()?.forEachIndexed { i, el -> el.obj()?.let { if (i !in finalItems) finalItems[i] = it } }
        }

        private fun collectSource(a: JsonObject) {
            if (a["type"].str() != "url_citation") return
            val url = a["url"].str() ?: return
            sources.putIfAbsent(url, SourceRef(a["title"].str()?.takeIf { it.isNotBlank() } ?: url, url))
        }

        private fun streamError(code: String?, message: String?): ProviderException {
            val status = when {
                code == null -> 500
                code.contains("rate_limit") -> 429
                code.contains("insufficient_quota") -> 402
                code.contains("context_length") -> 413
                code.contains("invalid_api_key") -> 401
                code.contains("model_not_found") -> 404
                code.contains("server") || code.contains("overloaded") -> 503
                else -> 400
            }
            return HttpErrors.map(status, message, code, null)
        }

        fun finish(provider: ProviderId, replayBase: List<JsonObject>): ProviderTurn {
            if (!finished) {
                // The stream ended without completed/incomplete: never act on a half-received answer.
                throw ProviderException(TurnErrorKind.NETWORK, "The response ended before it was complete.")
            }
            // Prefer the completed items; fall back to the in-progress ones if the vendor never sent `.done`.
            val indices = (finalItems.keys + addedItems.keys).toSortedSet()
            val ordered: List<Pair<Int, JsonObject>> = indices.map { i -> i to (finalItems[i] ?: addedItems.getValue(i)) }

            val toolCalls = ArrayList<ToolCall>()
            val messageText = StringBuilder()
            var refused = false
            var searches = 0
            for ((index, item) in ordered) {
                when (item["type"].str()) {
                    "function_call" -> {
                        val callId = item["call_id"].str() ?: item["id"].str() ?: throw malformed("a tool call had no id")
                        val name = item["name"].str() ?: throw malformed("a tool call had no name")
                        // The completed item's arguments are authoritative; joined fragments are the fallback.
                        val args = item["arguments"].str()?.takeIf { it.isNotEmpty() }
                            ?: argFragments[index]?.toString()?.takeIf { it.isNotEmpty() }
                            ?: "{}"
                        toolCalls += ToolCall(callId, name, args)
                    }
                    "message" -> item["content"].arr()?.forEach { part ->
                        val p = part.obj() ?: return@forEach
                        when (p["type"].str()) {
                            "output_text" -> {
                                messageText.append(p["text"].str().orEmpty())
                                p["annotations"].arr()?.forEach { a -> a.obj()?.let(::collectSource) }
                            }
                            "refusal" -> {
                                refused = true
                                messageText.append(p["refusal"].str().orEmpty())
                            }
                        }
                    }
                    "web_search_call" -> searches++
                }
            }

            val text = messageText.toString().ifEmpty { textDeltas.toString() }
            val stop = when {
                toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                refused -> StopReason.REFUSAL
                incompleteReason == "max_output_tokens" -> StopReason.MAX_TOKENS
                incompleteReason == "content_filter" -> StopReason.REFUSAL
                incompleteReason != null -> StopReason.OTHER
                else -> StopReason.END_TURN
            }
            val u = (usage ?: UsageSummary()).copy(webSearchCalls = searches)
            return ProviderTurn(
                text = text,
                toolCalls = toolCalls,
                stop = stop,
                usage = u.copy(estimatedCostMicros = servedModel?.let { ModelCatalog.estimateMicros(provider, it, u) }),
                sources = sources.values.toList(),
                webSearchUsed = searches > 0,
                loopState = if (toolCalls.isNotEmpty()) LoopState(replayBase + ordered.map { it.second }) else null,
                servedModel = servedModel,
            )
        }
        private fun malformed(why: String) = ProviderException(TurnErrorKind.MALFORMED_RESPONSE, "The service sent an incomplete response ($why).")
    }
}
