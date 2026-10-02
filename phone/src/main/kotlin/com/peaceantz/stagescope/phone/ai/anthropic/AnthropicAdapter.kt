package com.peaceantz.stagescope.phone.ai.anthropic

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
import com.peaceantz.stagescope.phone.ai.core.VendorJson
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
 * Anthropic **Messages** API adapter (`POST /v1/messages`, SSE streaming).
 *
 * Tool lifecycle: the model streams a `tool_use` block whose input arrives as `input_json_delta`
 * fragments; the block is only complete at `content_block_stop`, so the fragments are joined and
 * parsed there -- never earlier. The *whole* assistant content of that response (including
 * `thinking` blocks and their signatures, server-tool blocks and citations) is echoed back unchanged
 * in the next request, followed by one user message holding a `tool_result` for every `tool_use` id.
 * That echo lives only in memory for the one turn ([LoopState]) and is never persisted or sent to
 * another vendor.
 *
 * Models used (see [ModelCatalog]): thinking is always on for Opus 5.5 / Sonnet 5.5 / Fable 5.1, so
 * no `thinking` parameter is sent (a disabled/budget config would be rejected); depth is controlled
 * with `output_config.effort`. `tool_choice` stays `auto` (forced tool use is rejected on these models).
 * `eager_input_streaming` is left off: our tool inputs are small, and without it the API validates the
 * JSON before streaming it.
 */
class AnthropicAdapter(
    private val http: ProviderHttp,
    private val keys: KeySource,
    baseUrl: String = "https://api.anthropic.com/",
) : ProviderAdapter {

    override val id: ProviderId = ProviderId.ANTHROPIC
    private val base: HttpUrl = baseUrl.toHttpUrl()

    class LoopState(val extraMessages: List<JsonObject>) : ProviderLoopState

    override suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn {
        val key = keys.key(id) ?: throw ProviderException(TurnErrorKind.MISSING_KEY, "No Anthropic API key is set. Add it in Providers on your phone.")
        val prior = (request.loop as? LoopState)?.extraMessages.orEmpty()
        val toolResultMessage = if (request.toolResults.isEmpty()) null else buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                for (r in request.toolResults) {
                    add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", r.callId)
                            put("content", r.content)
                            if (r.isError) put("is_error", true)
                        },
                    )
                }
            }
        }
        val extra = prior + listOfNotNull(toolResultMessage)
        val body = buildBody(request, extra)
        val httpRequest = http.jsonPost(
            base.newBuilder().addPathSegments("v1/messages").build(),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body.toString(),
            stream = true,
        )
        val collector = Collector(onEvent)
        http.stream(httpRequest, errorMapper = ::mapHttpError) { response ->
            Sse.read(response.body.source()) { event ->
                val json = parseObject(event.data) ?: return@read true
                collector.accept(event.event ?: json["type"].str().orEmpty(), json)
                !collector.finished
            }
        }
        return collector.finish(extra, request.model)
    }

    override suspend fun validateKey(): KeyCheck {
        val key = keys.key(id) ?: return KeyCheck(false, TurnErrorKind.MISSING_KEY, "No key entered.")
        val result = http.execute(
            http.get(
                base.newBuilder().addPathSegments("v1/models").addQueryParameter("limit", "100").build(),
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            ),
        )
        if (result.status !in 200..299) {
            val e = mapHttpError(result.status, result.body, result.retryAfterMs)
            return KeyCheck(false, e.kind, e.message)
        }
        val models = parseObject(result.body)?.get("data").arr()?.mapNotNull { it.obj()?.get("id").str() }.orEmpty()
        return KeyCheck(true, models = models)
    }

    // --- request ---------------------------------------------------------------------------------

    internal fun buildBody(request: ProviderRequest, extraMessages: List<JsonObject>): JsonObject {
        val info = ModelCatalog.find(id, request.model)
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxOutputTokens)
            put("system", request.system)
            put("stream", true)
            info?.efforts?.get(request.effort)?.let { e -> putJsonObject("output_config") { put("effort", e) } }
            if (request.tools.isNotEmpty() || request.webSearch) {
                putJsonArray("tools") {
                    for (t in request.tools) {
                        add(
                            buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("input_schema", t.parameters)
                            },
                        )
                    }
                    if (request.webSearch) {
                        add(
                            buildJsonObject {
                                put("type", webSearchToolType(request.model))
                                put("name", "web_search")
                                put("max_uses", 3)
                            },
                        )
                    }
                }
            }
            put("messages", messages(request, extraMessages))
        }
    }

    private fun messages(request: ProviderRequest, extra: List<JsonObject>): JsonArray = buildJsonArray {
        val history = request.history
        if (history.firstOrNull() is NeutralMessage.Assistant) {
            add(buildJsonObject { put("role", "user"); put("content", "(conversation continues)") })
        }
        for (m in history) {
            add(buildJsonObject { put("role", if (m is NeutralMessage.User) "user" else "assistant"); put("content", m.text) })
        }
        extra.forEach { add(it) }
    }

    /** The dynamic-filtering search tool exists on the newest models; older ones keep the basic variant. */
    private fun webSearchToolType(model: String): String =
        if (model == "claude-opus-5-5" || model == "claude-sonnet-5-5") "web_search_20260209" else "web_search_20250305"

    // --- errors ----------------------------------------------------------------------------------

    internal fun mapHttpError(status: Int, body: String, retryAfterMs: Long?): ProviderException {
        val json = parseObject(body)
        val err = json?.get("error").obj()
        val message = err?.get("message").str() ?: Redactor.text(body).take(160).ifBlank { null }
        return HttpErrors.map(status, message, err?.get("type").str(), retryAfterMs)
    }

    // --- stream assembly -------------------------------------------------------------------------

    private class Block(val type: String, val start: JsonObject) {
        val text = StringBuilder()
        val partialJson = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
        val citations = ArrayList<JsonObject>()
    }

    private inner class Collector(private val onEvent: EventSink) {
        var finished = false
        private val blocks = java.util.TreeMap<Int, Block>()
        private val completed = java.util.TreeMap<Int, JsonObject>()
        private val sources = LinkedHashMap<String, SourceRef>()
        private var servedModel: String? = null
        private var stopReason: String? = null
        private var inputTokens = 0L
        private var cacheRead = 0L
        private var cacheCreation = 0L
        private var outputTokens = 0L
        private var searchRequests = 0
        private var announcedThinking = false

        fun accept(type: String, e: JsonObject) {
            when (type) {
                "message_start" -> {
                    val m = e["message"].obj()
                    servedModel = m?.get("model").str()
                    absorbUsage(m?.get("usage").obj())
                }
                "content_block_start" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    val start = e["content_block"].obj() ?: return
                    val t = start["type"].str().orEmpty()
                    blocks[index] = Block(t, start)
                    when (t) {
                        "tool_use" -> onEvent(ProviderEvent.ToolCallStarted(start["name"].str().orEmpty()))
                        "server_tool_use" -> onEvent(ProviderEvent.WebSearching)
                        "thinking", "redacted_thinking" -> if (!announcedThinking) {
                            announcedThinking = true
                            onEvent(ProviderEvent.Thinking)
                        }
                    }
                    // Blocks that arrive complete (e.g. web_search_tool_result) have no deltas.
                    if (t == "web_search_tool_result") {
                        collectSearchResults(start)
                    }
                }
                "content_block_delta" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    val block = blocks[index] ?: return
                    val delta = e["delta"].obj() ?: return
                    when (delta["type"].str()) {
                        "text_delta" -> delta["text"].str()?.let {
                            block.text.append(it)
                            onEvent(ProviderEvent.TextDelta(it))
                        }
                        "input_json_delta" -> block.partialJson.append(delta["partial_json"].str().orEmpty())
                        "thinking_delta" -> block.thinking.append(delta["thinking"].str().orEmpty())
                        "signature_delta" -> block.signature = (block.signature.orEmpty()) + delta["signature"].str().orEmpty()
                        "citations_delta" -> delta["citation"].obj()?.let {
                            block.citations += it
                            collectCitation(it)
                        }
                    }
                }
                "content_block_stop" -> {
                    val index = e["index"].long()?.toInt() ?: return
                    // An empty text block is not echoed back: the API rejects empty text content.
                    blocks[index]?.let { b -> if (!(b.type == "text" && b.text.isEmpty())) completed[index] = assemble(b) }
                }
                "message_delta" -> {
                    stopReason = e["delta", "stop_reason"].str() ?: stopReason
                    absorbUsage(e["usage"].obj())
                }
                "message_stop" -> finished = true
                "error" -> {
                    val err = e["error"].obj()
                    val status = when (err?.get("type").str()) {
                        "overloaded_error" -> 529
                        "rate_limit_error" -> 429
                        "authentication_error" -> 401
                        "permission_error" -> 403
                        "not_found_error" -> 404
                        "invalid_request_error" -> 400
                        else -> 500
                    }
                    throw HttpErrors.map(status, err?.get("message").str(), err?.get("type").str(), null)
                }
                else -> Unit // ping and unknown event types
            }
        }

        /** Rebuilds one content block in exactly the shape the API wants echoed back. */
        private fun assemble(b: Block): JsonObject = when (b.type) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", b.text.toString())
                if (b.citations.isNotEmpty()) put("citations", JsonArray(b.citations))
            }
            "thinking" -> buildJsonObject {
                put("type", "thinking")
                put("thinking", b.thinking.toString())
                put("signature", b.signature.orEmpty())
            }
            "tool_use", "server_tool_use" -> buildJsonObject {
                put("type", b.type)
                put("id", b.start["id"].str().orEmpty())
                put("name", b.start["name"].str().orEmpty())
                val json = b.partialJson.toString()
                put("input", if (json.isBlank()) JsonObject(emptyMap()) else parseObject(json) ?: throw malformed("tool input was not valid JSON"))
            }
            else -> b.start // redacted_thinking, web_search_tool_result, ...: unchanged
        }

        private fun absorbUsage(u: JsonObject?) {
            if (u == null) return
            u["input_tokens"].long()?.let { inputTokens = it }
            u["cache_read_input_tokens"].long()?.let { cacheRead = it }
            u["cache_creation_input_tokens"].long()?.let { cacheCreation = it }
            u["output_tokens"].long()?.let { outputTokens = it }
            u["server_tool_use", "web_search_requests"].long()?.let { searchRequests = it.toInt() }
        }

        private fun collectCitation(c: JsonObject) {
            val url = c["url"].str() ?: return
            sources.putIfAbsent(url, SourceRef(c["title"].str()?.takeIf { it.isNotBlank() } ?: url, url))
        }

        private fun collectSearchResults(block: JsonObject) {
            block["content"].arr()?.forEach { el ->
                val r = el.obj() ?: return@forEach
                if (r["type"].str() == "web_search_result") collectCitation(r)
            }
        }

        fun finish(extraSoFar: List<JsonObject>, requestedModel: String): ProviderTurn {
            if (!finished) throw ProviderException(TurnErrorKind.NETWORK, "The response ended before it was complete.")
            val content: List<JsonObject> = completed.values.toList()
            val toolUses = content.filter { it["type"].str() == "tool_use" }
            val text = content.filter { it["type"].str() == "text" }.joinToString("") { it["text"].str().orEmpty() }
            for (b in content) if (b["type"].str() == "text") b["citations"].arr()?.forEach { it.obj()?.let(::collectCitation) }

            val stop = when (stopReason) {
                "tool_use" -> StopReason.TOOL_USE
                "max_tokens" -> StopReason.MAX_TOKENS
                "refusal" -> StopReason.REFUSAL
                "pause_turn" -> StopReason.PAUSE
                "end_turn", "stop_sequence", null -> StopReason.END_TURN
                else -> StopReason.OTHER
            }
            val assistantMessage = buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(content))
            }
            val usage = UsageSummary(
                inputTokens = inputTokens + cacheRead + cacheCreation,
                cachedInputTokens = cacheRead,
                outputTokens = outputTokens,
                webSearchCalls = if (searchRequests > 0) searchRequests else content.count { it["type"].str() == "server_tool_use" },
            )
            val model = servedModel ?: requestedModel
            return ProviderTurn(
                text = text,
                toolCalls = toolUses.map { ToolCall(it["id"].str().orEmpty(), it["name"].str().orEmpty(), (it["input"] ?: JsonObject(emptyMap())).toString()) },
                stop = stop,
                usage = usage.copy(estimatedCostMicros = ModelCatalog.estimateMicros(ProviderId.ANTHROPIC, model, usage)),
                sources = sources.values.toList(),
                webSearchUsed = usage.webSearchCalls > 0,
                // A tool_use turn or a pause_turn must be echoed back; a finished turn needs nothing.
                loopState = if (stop == StopReason.TOOL_USE || stop == StopReason.PAUSE) LoopState(extraSoFar + assistantMessage) else null,
                servedModel = servedModel,
            )
        }

        private fun malformed(why: String) = ProviderException(TurnErrorKind.MALFORMED_RESPONSE, "The service sent an incomplete response ($why).")
    }
}
