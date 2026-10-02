package com.peaceantz.stagescope.phone.ai

import com.peaceantz.stagescope.phone.ai.anthropic.AnthropicAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolResult
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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

class AnthropicAdapterTest {
    private lateinit var h: AdapterHarness
    private lateinit var adapter: AnthropicAdapter

    @Before
    fun setUp() {
        h = AdapterHarness()
        adapter = AnthropicAdapter(h.http, h.keys, h.baseUrl)
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
    fun `text stream, usage and the documented request shape`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT)
        val turn = run(AdapterHarness.request("claude-opus-5-5"))
        assertEquals("Looks like a narrow peak.", turn.text)
        assertEquals(StopReason.END_TURN, turn.stop)
        // input = fresh + cache read + cache creation; cached = cache read
        assertEquals(1000, turn.usage.inputTokens)
        assertEquals(100, turn.usage.cachedInputTokens)
        assertEquals(42, turn.usage.outputTokens)
        assertNull(turn.loopState)

        val r = h.nextRequest()
        assertEquals("/v1/messages", r.path)
        assertEquals(h.key, r.getHeader("x-api-key"))
        assertEquals("2023-06-01", r.getHeader("anthropic-version"))
        assertNull("the key is never sent as a bearer token to Anthropic", r.getHeader("Authorization"))
        val body = h.bodyOf(r)
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("You are the StageScope assistant.", body["system"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Thinking is always on for this model; sending a thinking/budget config would be rejected.
        assertFalse("thinking" in body)
        assertFalse("tool_choice" in body)
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("log_issue", tool["name"]!!.jsonPrimitive.content)
        assertNotNull(tool["input_schema"])
        assertEquals(3, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `tool_use input is parsed only at block stop and ids and thinking signatures survive`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TOOL)
        val turn = run(AdapterHarness.request("claude-opus-5-5"))
        assertEquals(StopReason.TOOL_USE, turn.stop)
        assertEquals("Saving that.", turn.text)
        val call = turn.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("log_issue", call.name)
        assertEquals("""{"description":"Mic 12 crackled"}""", call.argumentsJson)
        assertTrue(h.events.any { it is ProviderEvent.Thinking })
        assertTrue(h.events.any { it is ProviderEvent.ToolCallStarted })
    }

    @Test
    fun `the whole assistant content is echoed back unchanged followed by one tool_result per tool_use`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TOOL)
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT)
        val first = run(AdapterHarness.request("claude-opus-5-5"))
        h.nextRequest()
        run(
            AdapterHarness.request(
                "claude-opus-5-5", loop = first.loopState,
                results = listOf(ToolResult("toolu_1", "log_issue", """{"saved":true}""", false)),
            ),
        )
        val messages = h.bodyOf(h.nextRequest())["messages"]!!.jsonArray
        assertEquals(5, messages.size) // 3 history + assistant echo + user tool_result
        val echo = messages[3].jsonObject
        assertEquals("assistant", echo["role"]!!.jsonPrimitive.content)
        val blocks = echo["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("SIG-ABC", blocks[0]["signature"]!!.jsonPrimitive.content)
        // The tool input is echoed as a parsed object, not a string.
        assertEquals("Mic 12 crackled", blocks[2]["input"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        val result = messages[4].jsonObject
        assertEquals("user", result["role"]!!.jsonPrimitive.content)
        val tr = result["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", tr["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", tr["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("""{"saved":true}""", tr["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool error result is flagged is_error`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TOOL)
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT)
        val first = run(AdapterHarness.request("claude-opus-5-5"))
        h.nextRequest()
        run(AdapterHarness.request("claude-opus-5-5", loop = first.loopState, results = listOf(ToolResult("toolu_1", "log_issue", "invalid arguments: description is required", true))))
        val tr = h.bodyOf(h.nextRequest())["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("true", tr["is_error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid tool JSON at block stop is a malformed-response error, never an executed call`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TOOL_BAD_JSON)
        assertEquals(TurnErrorKind.MALFORMED_RESPONSE, error(AdapterHarness.request("claude-opus-5-5")).kind)
    }

    @Test
    fun `web search results become sources and the search is counted`() {
        h.enqueueSse(Fixtures.ANTHROPIC_WEB_SEARCH)
        val turn = run(AdapterHarness.request("claude-opus-5-5", webSearch = true))
        assertTrue(turn.webSearchUsed)
        assertEquals(1, turn.usage.webSearchCalls)
        assertEquals(listOf("https://behringer.example/x32"), turn.sources.map { it.url })
        assertEquals("See the manual.", turn.text)
        val tools = h.bodyOf(h.nextRequest())["tools"]!!.jsonArray.map { it.jsonObject }
        val search = tools.single { it["name"]!!.jsonPrimitive.content == "web_search" }
        assertEquals("web_search_20260209", search["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `older models get the basic web search tool and no effort parameter`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT)
        run(AdapterHarness.request("claude-haiku-4-5", webSearch = true))
        val body = h.bodyOf(h.nextRequest())
        assertFalse("output_config" in body)
        val search = body["tools"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "web_search" }
        assertEquals("web_search_20250305", search["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `http and stream errors map to retryable and non-retryable kinds`() {
        h.enqueueJson(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(TurnErrorKind.INVALID_KEY, error(AdapterHarness.request("claude-opus-5-5")).kind)

        h.enqueueJson(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        val overloaded = error(AdapterHarness.request("claude-opus-5-5"))
        assertEquals(TurnErrorKind.OVERLOADED, overloaded.kind)
        assertTrue(overloaded.retryable)

        h.enqueueJson(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-nope"}}""")
        assertEquals(TurnErrorKind.MODEL_UNAVAILABLE, error(AdapterHarness.request("claude-nope")).kind)

        h.enqueueJson(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", "retry-after" to "2")
        val limited = error(AdapterHarness.request("claude-opus-5-5"))
        assertEquals(TurnErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(2000L, limited.retryAfterMs)

        h.enqueueSse(Fixtures.ANTHROPIC_OVERLOADED)
        assertEquals(TurnErrorKind.OVERLOADED, error(AdapterHarness.request("claude-opus-5-5")).kind)
    }

    @Test
    fun `a response that ends before message_stop is an error`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TOOL.substringBefore("event: message_stop"))
        assertEquals(TurnErrorKind.NETWORK, error(AdapterHarness.request("claude-opus-5-5")).kind)
    }

    @Test
    fun `a refusal stop reason is surfaced, not hidden`() {
        h.enqueueSse(Fixtures.ANTHROPIC_TEXT.replace("end_turn", "refusal"))
        assertEquals(StopReason.REFUSAL, run(AdapterHarness.request("claude-opus-5-5")).stop)
    }

    @Test
    fun `missing key makes no request`() {
        h.key = null
        assertEquals(TurnErrorKind.MISSING_KEY, error(AdapterHarness.request("claude-opus-5-5")).kind)
        assertEquals(0, h.server.requestCount)
    }
}
