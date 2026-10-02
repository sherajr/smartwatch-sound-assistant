package com.peaceantz.stagescope.phone.ai

import com.peaceantz.stagescope.phone.ai.core.Effort
import com.peaceantz.stagescope.phone.ai.core.KeySource
import com.peaceantz.stagescope.phone.ai.core.NeutralMessage
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.core.ProviderRequest
import com.peaceantz.stagescope.phone.ai.core.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/** MockWebServer + a ProviderHttp pointed at it. Production stays https + fixed hosts; tests allow only the mock host. */
class AdapterHarness : AutoCloseable {
    val server = MockWebServer().also { it.start() }
    val http = ProviderHttp(OkHttpClient(), allowedHosts = setOf(server.hostName), requireHttps = false)
    val baseUrl: String get() = server.url("/").toString()
    var key: String? = "test-key-ABCDEFGH-12345678"
    val keys = KeySource { key }
    val events = mutableListOf<ProviderEvent>()

    fun enqueueSse(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).addHeader("Content-Type", "text/event-stream").setBody(body))
    }

    fun enqueueJson(code: Int, body: String, vararg headers: Pair<String, String>) {
        val r = MockResponse().setResponseCode(code).addHeader("Content-Type", "application/json").setBody(body)
        headers.forEach { (k, v) -> r.addHeader(k, v) }
        server.enqueue(r)
    }

    fun nextRequest(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("no request was made")

    fun bodyOf(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body.readUtf8()) as JsonObject

    override fun close() = server.shutdown()

    companion object {
        val LOG_ISSUE_TOOL = ToolSpec(
            name = "log_issue",
            description = "Log a show issue.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("description") { put("type", "string") } }
                kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("description"))).let { put("required", it) }
            },
        )

        fun request(
            model: String,
            tools: List<ToolSpec> = listOf(LOG_ISSUE_TOOL),
            webSearch: Boolean = false,
            effort: Effort = Effort.BALANCED,
            loop: com.peaceantz.stagescope.phone.ai.core.ProviderLoopState? = null,
            results: List<com.peaceantz.stagescope.phone.ai.core.ToolResult> = emptyList(),
        ) = ProviderRequest(
            model = model,
            system = "You are the StageScope assistant.",
            history = listOf(NeutralMessage.User("Earlier question"), NeutralMessage.Assistant("Earlier answer"), NeutralMessage.User("Analyze these rings")),
            tools = tools,
            maxOutputTokens = 2000,
            effort = effort,
            webSearch = webSearch,
            loop = loop,
            toolResults = results,
        )
    }
}
