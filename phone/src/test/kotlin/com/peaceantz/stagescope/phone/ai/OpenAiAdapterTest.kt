package com.peaceantz.stagescope.phone.ai

import com.peaceantz.stagescope.phone.ai.core.Effort
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolResult
import com.peaceantz.stagescope.phone.ai.responses.ResponsesAdapter
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiAdapterTest {
    private lateinit var h: AdapterHarness
    private lateinit var adapter: ResponsesAdapter

    @Before
    fun setUp() {
        h = AdapterHarness()
        adapter = ResponsesAdapter(ProviderId.OPENAI, h.http, h.keys, h.baseUrl, ResponsesAdapter.Flavor.OPENAI)
    }

    @After
    fun tearDown() = h.close()

    private fun run(req: com.peaceantz.stagescope.phone.ai.core.ProviderRequest) = runBlocking { adapter.runTurn(req) { h.events += it } }

    private fun error(req: com.peaceantz.stagescope.phone.ai.core.ProviderRequest): ProviderException {
        try {
            run(req)
        } catch (e: ProviderException) {
            return e
        }
        fail("expected a ProviderException")
        throw IllegalStateException()
    }

    @Test
    fun `a text stream becomes text, usage, sources and a request in the documented Responses shape`() {
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        val turn = run(AdapterHarness.request("gpt-6.1-sol", webSearch = true))

        assertEquals("Check the ring at 2.1 kHz.", turn.text)
        assertEquals(StopReason.END_TURN, turn.stop)
        assertTrue(turn.toolCalls.isEmpty())
        assertNull("a finished turn needs no loop state", turn.loopState)
        assertEquals(1000, turn.usage.inputTokens)
        assertEquals(200, turn.usage.cachedInputTokens)
        assertEquals(300, turn.usage.outputTokens)
        assertEquals(100, turn.usage.reasoningTokens)
        assertEquals(listOf("https://example.com/a"), turn.sources.map { it.url })
        assertNotNull("cost is estimated from the dated price table", turn.usage.estimatedCostMicros)
        assertEquals("Check the ring at 2.1 kHz.", h.events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })

        val r = h.nextRequest()
        assertEquals("/v1/responses", r.path)
        assertEquals("Bearer ${h.key}", r.getHeader("Authorization"))
        val body = h.bodyOf(r)
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("You are the StageScope assistant.", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals(3, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("assistant", input[1].jsonObject["role"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("function", "web_search"), tools)
    }

    @Test
    fun `fragmented tool arguments are assembled before the call exists and ids are kept`() {
        h.enqueueSse(Fixtures.RESPONSES_TOOL_FRAGMENTED)
        val turn = run(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(StopReason.TOOL_USE, turn.stop)
        val call = turn.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("log_issue", call.name)
        assertEquals("""{"description":"Mic 12 crackled"}""", call.argumentsJson)
        assertEquals("Mic 12 crackled", Json.parseToJsonElement(call.argumentsJson).jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue(h.events.any { it is ProviderEvent.ToolCallStarted && it.name == "log_issue" })
        assertNotNull(turn.loopState)
    }

    @Test
    fun `the next request replays this turn's output items and returns a function_call_output per call id`() {
        h.enqueueSse(Fixtures.RESPONSES_TOOL_FRAGMENTED)
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        val first = run(AdapterHarness.request("gpt-6.1-sol"))
        h.nextRequest() // first request
        val second = run(
            AdapterHarness.request(
                "gpt-6.1-sol", loop = first.loopState,
                results = listOf(ToolResult("call_1", "log_issue", """{"saved":true}""", false)),
            ),
        )
        assertEquals(StopReason.END_TURN, second.stop)
        val input = h.bodyOf(h.nextRequest())["input"]!!.jsonArray
        val types = input.map { it.jsonObject["type"]?.jsonPrimitive?.content ?: "role:" + it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("role:user", "role:assistant", "role:user", "reasoning", "function_call", "function_call_output"), types)
        // Encrypted reasoning must round-trip unchanged (store:false makes it mandatory).
        assertEquals("ENC-REASONING-BLOB", input[3].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        val output = input[5].jsonObject
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"saved":true}""", output["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed arguments are handed over untouched so the registry can reject them - never repaired`() {
        h.enqueueSse(Fixtures.RESPONSES_TOOL_MALFORMED_ARGS)
        val call = run(AdapterHarness.request("gpt-6.1-sol")).toolCalls.single()
        assertEquals("""{"description":"Mic 12 crack""", call.argumentsJson)
        val parsed = runCatching { Json.parseToJsonElement(call.argumentsJson) }
        assertTrue("incomplete JSON must stay invalid", parsed.isFailure)
    }

    @Test
    fun `an incomplete response reports why`() {
        h.enqueueSse(Fixtures.RESPONSES_INCOMPLETE)
        val turn = run(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(StopReason.MAX_TOKENS, turn.stop)
        assertEquals("Partial", turn.text)
    }

    @Test
    fun `http errors map to actionable kinds and never echo the key`() {
        h.enqueueJson(401, """{"error":{"message":"Incorrect API key provided: ${h.key}.","type":"invalid_request_error","code":"invalid_api_key"}}""")
        val unauthorised = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.INVALID_KEY, unauthorised.kind)
        assertFalse(unauthorised.message.contains(h.key!!))

        h.enqueueJson(429, """{"error":{"message":"Rate limit reached","code":"rate_limit_exceeded"}}""", "retry-after" to "3")
        val limited = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(3000L, limited.retryAfterMs)
        assertTrue(limited.retryable)

        h.enqueueJson(429, """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}""")
        assertEquals(TurnErrorKind.QUOTA_EXHAUSTED, error(AdapterHarness.request("gpt-6.1-sol")).kind)

        h.enqueueJson(404, """{"error":{"message":"The model `gpt-9` does not exist","code":"model_not_found"}}""")
        val missing = error(AdapterHarness.request("gpt-9"))
        assertEquals(TurnErrorKind.MODEL_UNAVAILABLE, missing.kind)
        assertTrue("it must not silently switch models", missing.message.contains("Pick another model"))

        h.enqueueJson(503, """{"error":{"message":"overloaded"}}""")
        val overloaded = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.OVERLOADED, overloaded.kind)
        assertTrue(overloaded.retryable)

        h.enqueueJson(400, """{"error":{"message":"This model's maximum context length is exceeded","code":"context_length_exceeded"}}""")
        assertEquals(TurnErrorKind.CONTEXT_TOO_LONG, error(AdapterHarness.request("gpt-6.1-sol")).kind)
    }

    @Test
    fun `a stream error event is mapped and a key-shaped string in it is redacted`() {
        h.enqueueSse(Fixtures.RESPONSES_STREAM_ERROR)
        val e = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.RATE_LIMITED, e.kind)
        assertFalse(e.message.contains("sk-proj-ABCDEFGHIJKLMNOP"))
    }

    @Test
    fun `a connection dropped mid-stream never yields a half-received tool call`() {
        h.server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream")
                .setBody(Fixtures.RESPONSES_TOOL_FRAGMENTED.substringBefore("event: response.function_call_arguments.done"))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val e = error(AdapterHarness.request("gpt-6.1-sol"))
        assertTrue(e.kind == TurnErrorKind.NETWORK || e.kind == TurnErrorKind.TIMEOUT)
    }

    @Test
    fun `a stream that ends early without completed is an error not a result`() {
        h.enqueueSse(Fixtures.RESPONSES_TOOL_FRAGMENTED.substringBefore("event: response.completed"))
        val e = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.NETWORK, e.kind)
    }

    @Test
    fun `cancelling the coroutine closes the connection promptly`() {
        h.server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream")
                .setBody(Fixtures.RESPONSES_TEXT).throttleBody(16, 400, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        runBlocking {
            val job = async(Dispatchers.Default) { adapter.runTurn(AdapterHarness.request("gpt-6.1-sol")) {} }
            delay(300)
            val started = System.nanoTime()
            job.cancel()
            try {
                withTimeout(3000) { job.await() }
                fail("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }
            assertTrue("cancel must not wait for the slow stream", (System.nanoTime() - started) / 1_000_000 < 2500)
        }
    }

    @Test
    fun `a missing key stops before any network traffic`() {
        h.key = null
        val e = error(AdapterHarness.request("gpt-6.1-sol"))
        assertEquals(TurnErrorKind.MISSING_KEY, e.kind)
        assertEquals(0, h.server.requestCount)
    }

    @Test
    fun `a host that is not on the approved list is blocked before it is contacted`() {
        val rogue = ResponsesAdapter(ProviderId.OPENAI, com.peaceantz.stagescope.phone.ai.core.ProviderHttp(allowedHosts = setOf("api.openai.com"), requireHttps = true), h.keys, "https://evil.example/", ResponsesAdapter.Flavor.OPENAI)
        val e = try {
            runBlocking { rogue.runTurn(AdapterHarness.request("gpt-6.1-sol")) {} }
            null
        } catch (e: ProviderException) {
            e
        }
        assertNotNull(e)
        assertEquals(TurnErrorKind.BAD_REQUEST, e!!.kind)
        assertEquals(0, h.server.requestCount)
    }

    @Test
    fun `effort preferences map to the provider's own values`() {
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        run(AdapterHarness.request("gpt-6-astra", effort = Effort.THOROUGH))
        assertEquals("high", h.bodyOf(h.nextRequest())["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `key validation uses a free models call and reports a bad key`() {
        h.enqueueJson(200, """{"data":[{"id":"gpt-6.1-sol"},{"id":"gpt-6-luna"}]}""")
        val ok = runBlocking { adapter.validateKey() }
        assertTrue(ok.ok)
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), ok.models)
        assertEquals("/v1/models", h.nextRequest().path)

        h.enqueueJson(401, """{"error":{"message":"bad key","code":"invalid_api_key"}}""")
        val bad = runBlocking { adapter.validateKey() }
        assertFalse(bad.ok)
        assertEquals(TurnErrorKind.INVALID_KEY, bad.kind)
    }
}
