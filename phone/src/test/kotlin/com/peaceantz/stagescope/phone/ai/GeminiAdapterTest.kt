package com.peaceantz.stagescope.phone.ai

import com.peaceantz.stagescope.phone.ai.core.Effort
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolResult
import com.peaceantz.stagescope.phone.ai.gemini.GeminiAdapter
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiAdapterTest {
    private lateinit var h: AdapterHarness
    private lateinit var adapter: GeminiAdapter

    @Before
    fun setUp() {
        h = AdapterHarness()
        adapter = GeminiAdapter(h.http, h.keys, h.baseUrl)
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
    fun `uses the Interactions API stateless with steps input and a thinking level`() {
        h.enqueueSse(Fixtures.GEMINI_TEXT)
        val turn = run(AdapterHarness.request("gemini-3.8-flash", webSearch = true, effort = Effort.THOROUGH))
        assertEquals("Start with the wedge mix.", turn.text)
        assertEquals(StopReason.END_TURN, turn.stop)
        assertEquals(listOf("https://example.com/g"), turn.sources.map { it.url })
        assertEquals(200, turn.usage.inputTokens)
        assertEquals(20, turn.usage.cachedInputTokens)
        assertEquals(90, turn.usage.outputTokens)
        assertEquals(56, turn.usage.reasoningTokens)
        assertNull(turn.loopState)

        val r = h.nextRequest()
        assertEquals("/v1beta/interactions", r.path)
        assertEquals(h.key, r.getHeader("x-goog-api-key"))
        assertNull(r.getHeader("Authorization"))
        val body = h.bodyOf(r)
        assertEquals("gemini-3.8-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("You are the StageScope assistant.", body["system_instruction"]!!.jsonPrimitive.content)
        val gen = body["generation_config"]!!.jsonObject
        assertEquals("high", gen["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("2000", gen["max_output_tokens"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user_input", "model_output", "user_input"), input.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("Analyze these rings", input[2]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val toolTypes = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("function", "google_search"), toolTypes)
    }

    @Test
    fun `function call arguments are joined from deltas and the call id is kept`() {
        h.enqueueSse(Fixtures.GEMINI_TOOL)
        val turn = run(AdapterHarness.request("gemini-3.8-flash"))
        assertEquals(StopReason.TOOL_USE, turn.stop)
        val call = turn.toolCalls.single()
        assertEquals("g_call_1", call.id)
        assertEquals("log_issue", call.name)
        assertEquals("""{"description":"Mic 12 crackled"}""", call.argumentsJson)
        assertTrue(h.events.any { it is ProviderEvent.Thinking })
        assertNotNull(turn.loopState)
    }

    @Test
    fun `the next request echoes this turn's model steps with the thought signature, then a function_result`() {
        h.enqueueSse(Fixtures.GEMINI_TOOL)
        h.enqueueSse(Fixtures.GEMINI_TEXT)
        val first = run(AdapterHarness.request("gemini-3.8-flash"))
        h.nextRequest()
        run(
            AdapterHarness.request(
                "gemini-3.8-flash", loop = first.loopState,
                results = listOf(ToolResult("g_call_1", "log_issue", """{"saved":true}""", false)),
            ),
        )
        val input = h.bodyOf(h.nextRequest())["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("user_input", "model_output", "user_input", "thought", "function_call", "function_result"),
            input.map { it["type"]!!.jsonPrimitive.content },
        )
        assertEquals("GEM-SIG-TOOL", input[3]["signature"]!!.jsonPrimitive.content)
        val call = input[4]
        assertEquals("g_call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("Mic 12 crackled", call["arguments"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        val result = input[5]
        assertEquals("g_call_1", result["call_id"]!!.jsonPrimitive.content)
        assertEquals("log_issue", result["name"]!!.jsonPrimitive.content)
        assertEquals("""{"saved":true}""", result["result"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid function arguments are a malformed-response error never an executed call`() {
        h.enqueueSse(Fixtures.GEMINI_TOOL_BAD_JSON)
        assertEquals(TurnErrorKind.MALFORMED_RESPONSE, error(AdapterHarness.request("gemini-3.8-flash")).kind)
    }

    @Test
    fun `server side google search steps are counted and echoed unchanged with their signatures`() {
        h.enqueueSse(Fixtures.GEMINI_SEARCH_AND_TOOL)
        val turn = run(AdapterHarness.request("gemini-3.8-flash", webSearch = true))
        assertTrue(turn.webSearchUsed)
        assertEquals(1, turn.usage.webSearchCalls)
        assertEquals("Gain staging notes.", turn.text)
        assertTrue(h.events.any { it is ProviderEvent.WebSearching })
    }

    @Test
    fun `an unavailable model is reported without substituting another`() {
        h.enqueueJson(404, """{"error":{"code":404,"message":"models/gemini-9 is not found for API version v1beta","status":"NOT_FOUND"}}""")
        val e = error(AdapterHarness.request("gemini-9"))
        assertEquals(TurnErrorKind.MODEL_UNAVAILABLE, e.kind)
        assertEquals(1, h.server.requestCount)
    }

    @Test
    fun `rate limit invalid key and stream timeout map to actionable kinds`() {
        h.enqueueJson(429, """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}""")
        assertEquals(TurnErrorKind.RATE_LIMITED, error(AdapterHarness.request("gemini-3.8-flash")).kind)
        h.enqueueJson(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        val e = error(AdapterHarness.request("gemini-3.8-flash"))
        assertEquals(TurnErrorKind.BAD_REQUEST, e.kind)
        h.enqueueJson(403, """{"error":{"code":403,"message":"denied","status":"PERMISSION_DENIED"}}""")
        assertEquals(TurnErrorKind.PERMISSION_DENIED, error(AdapterHarness.request("gemini-3.8-flash")).kind)
        h.enqueueSse(Fixtures.GEMINI_STREAM_ERROR)
        assertEquals(TurnErrorKind.OVERLOADED, error(AdapterHarness.request("gemini-3.8-flash")).kind)
    }

    @Test
    fun `an interaction that never completes is an error`() {
        h.enqueueSse(Fixtures.GEMINI_TOOL.substringBefore("event: interaction.completed"))
        assertEquals(TurnErrorKind.NETWORK, error(AdapterHarness.request("gemini-3.8-flash")).kind)
    }

    @Test
    fun `a gemini state is not usable by another vendor and vice versa - no transplanted metadata`() {
        h.enqueueSse(Fixtures.GEMINI_TOOL)
        val gemini = run(AdapterHarness.request("gemini-3.8-flash"))
        // Hand Gemini's opaque loop state to the Anthropic adapter: it must ignore it entirely.
        val anthropic = com.peaceantz.stagescope.phone.ai.anthropic.AnthropicAdapter(h.http, h.keys, h.baseUrl)
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT)
        h.nextRequest()
        runBlocking { anthropic.runTurn(AdapterHarness.request("claude-opus-5-5", loop = gemini.loopState)) {} }
        val sent = h.nextRequest().body.readUtf8()
        assertFalse(sent.contains("GEM-SIG-TOOL"))
        assertFalse(sent.contains("g_call_1"))
    }
}
