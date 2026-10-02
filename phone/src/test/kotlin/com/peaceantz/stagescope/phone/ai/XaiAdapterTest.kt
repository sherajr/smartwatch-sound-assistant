package com.peaceantz.stagescope.phone.ai

import com.peaceantz.stagescope.phone.ai.core.Effort
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.phone.ai.core.ToolResult
import com.peaceantz.stagescope.phone.ai.responses.ResponsesAdapter
import com.peaceantz.stagescope.shared.assistant.ProviderId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XaiAdapterTest {
    private lateinit var h: AdapterHarness
    private lateinit var adapter: ResponsesAdapter

    @Before
    fun setUp() {
        h = AdapterHarness()
        adapter = ResponsesAdapter(ProviderId.XAI, h.http, h.keys, h.baseUrl, ResponsesAdapter.Flavor.XAI)
    }

    @After
    fun tearDown() = h.close()

    private fun run(req: com.peaceantz.stagescope.phone.ai.core.ProviderRequest) = runBlocking { adapter.runTurn(req) {} }

    @Test
    fun `a whole-chunk function call is accepted and the exact cost is reported`() {
        h.enqueueSse(Fixtures.XAI_TOOL_WHOLE)
        val turn = run(AdapterHarness.request("grok-4.7"))
        assertEquals(StopReason.TOOL_USE, turn.stop)
        val call = turn.toolCalls.single()
        assertEquals("call_x", call.id)
        assertEquals("prepare_keep_item", call.name)
        assertEquals("""{"item_text":"spare mic tape","list_name":"Theatre Supplies"}""", call.argumentsJson)
        // 37,756,000 ticks / 1e10 per USD = $0.0037756 -> 3775 micro-USD (integer division of ticks by 1e4).
        assertEquals(3775L, turn.usage.reportedCostMicros)
        assertEquals(400, turn.usage.inputTokens)
        assertEquals(30, turn.usage.reasoningTokens)
    }

    @Test
    fun `request follows the xAI Responses flavour - store false, encrypted reasoning, bearer key, effort`() {
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        run(AdapterHarness.request("grok-4.7", webSearch = true, effort = Effort.FAST))
        val r = h.nextRequest()
        assertEquals("/v1/responses", r.path)
        assertEquals("Bearer ${h.key}", r.getHeader("Authorization"))
        val body = h.bodyOf(r)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", body["include"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("web_search", body["tools"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a model without an effort setting is not sent one`() {
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        run(AdapterHarness.request("grok-4.3", effort = Effort.THOROUGH))
        assertFalse("reasoning" in h.bodyOf(h.nextRequest()))
    }

    @Test
    fun `reasoning items are replayed unchanged with the function_call_output`() {
        h.enqueueSse(Fixtures.XAI_TOOL_WHOLE)
        h.enqueueSse(Fixtures.RESPONSES_TEXT)
        val first = run(AdapterHarness.request("grok-4.7"))
        h.nextRequest()
        run(AdapterHarness.request("grok-4.7", loop = first.loopState, results = listOf(ToolResult("call_x", "prepare_keep_item", """{"status":"ready_on_phone"}""", false))))
        val input = h.bodyOf(h.nextRequest())["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("XAI-ENC", input.first { it["type"]?.jsonPrimitive?.content == "reasoning" }["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("call_x", input.last()["call_id"]!!.jsonPrimitive.content)
        assertNotNull(first.loopState)
    }

    @Test
    fun `xAI error bodies with a plain string error are mapped`() {
        h.enqueueJson(401, """{"code":"Client specified an invalid argument","error":"Incorrect API key provided: ${h.key}"}""")
        val e = try { run(AdapterHarness.request("grok-4.7")); null } catch (e: com.peaceantz.stagescope.phone.ai.core.ProviderException) { e }
        assertEquals(com.peaceantz.stagescope.shared.assistant.TurnErrorKind.INVALID_KEY, e!!.kind)
        assertFalse(e.message.contains(h.key!!))
        assertTrue(true)
    }
}
