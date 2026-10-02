package com.peaceantz.stagescope.phone.ai

/**
 * Recorded-shape SSE streams for each vendor, written from their official documentation (verified
 * 2026-10-01) and used with MockWebServer. They are *shapes*, not captures of live traffic: a green
 * test proves the adapter handles the documented wire format, not that a live key works.
 */
object Fixtures {

    private fun sse(vararg events: Pair<String?, String>): String =
        events.joinToString("") { (name, data) -> (if (name != null) "event: $name\n" else "") + "data: $data\n\n" }

    // ---------------------------------------------------------------- OpenAI / xAI (Responses)

    val RESPONSES_TEXT = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_1","model":"gpt-6.1-sol","status":"in_progress"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[]}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Check the "}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"ring at 2.1 kHz."}""",
        "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Check the ring at 2.1 kHz.","annotations":[{"type":"url_citation","url":"https://example.com/a","title":"Example A","start_index":0,"end_index":5}]}]}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-6.1-sol","status":"completed","usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":200},"output_tokens":300,"output_tokens_details":{"reasoning_tokens":100},"total_tokens":1300}}}""",
    )

    /** OpenAI-style fragmented function call, preceded by an (encrypted) reasoning item. */
    val RESPONSES_TOOL_FRAGMENTED = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_2","model":"gpt-6.1-sol","status":"in_progress"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
        "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"ENC-REASONING-BLOB"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"log_issue","arguments":""}}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{\"descr"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"iption\":\"Mic 12 crack"}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"led\"}"}""",
        "response.function_call_arguments.done" to """{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":1,"arguments":"{\"description\":\"Mic 12 crackled\"}"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"log_issue","arguments":"{\"description\":\"Mic 12 crackled\"}"}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_2","model":"gpt-6.1-sol","status":"completed","usage":{"input_tokens":500,"output_tokens":80,"total_tokens":580}}}""",
    )

    /** The arguments end mid-string: never executable. */
    val RESPONSES_TOOL_MALFORMED_ARGS = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_3","model":"gpt-6.1-sol"}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_9","call_id":"call_9","name":"log_issue","arguments":""}}""",
        "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_9","output_index":0,"delta":"{\"description\":\"Mic 12 crack"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_9","call_id":"call_9","name":"log_issue","arguments":"{\"description\":\"Mic 12 crack"}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_3","model":"gpt-6.1-sol","status":"completed","usage":{"input_tokens":5,"output_tokens":5}}}""",
    )

    /** xAI delivers a function call whole in one chunk (documented), and reports its exact cost in ticks. */
    val XAI_TOOL_WHOLE = sse(
        null to """{"type":"response.created","response":{"id":"resp_x","model":"grok-4.7"}}""",
        null to """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_x","summary":[],"encrypted_content":"XAI-ENC"}}""",
        null to """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_x","call_id":"call_x","name":"prepare_keep_item","arguments":"{\"item_text\":\"spare mic tape\",\"list_name\":\"Theatre Supplies\"}"}}""",
        null to """{"type":"response.completed","response":{"id":"resp_x","model":"grok-4.7","status":"completed","usage":{"input_tokens":400,"output_tokens":60,"output_tokens_details":{"reasoning_tokens":30},"cost_in_usd_ticks":37756000}}}""",
        null to "[DONE]",
    )

    val RESPONSES_STREAM_ERROR = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_e","model":"gpt-6.1-sol"}}""",
        "error" to """{"type":"error","code":"rate_limit_exceeded","message":"Rate limit reached for gpt-6.1-sol. sk-proj-ABCDEFGHIJKLMNOP"}""",
    )

    val RESPONSES_INCOMPLETE = sse(
        "response.created" to """{"type":"response.created","response":{"id":"resp_i","model":"gpt-6.1-sol"}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","output_index":0,"delta":"Partial"}""",
        "response.incomplete" to """{"type":"response.incomplete","response":{"id":"resp_i","model":"gpt-6.1-sol","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":4}}}""",
    )

    // ---------------------------------------------------------------- Anthropic (Messages)

    val ANTHROPIC_TEXT = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"usage":{"input_tokens":900,"cache_read_input_tokens":100,"cache_creation_input_tokens":0,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "ping" to """{"type":"ping"}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Looks like "}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"a narrow peak."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":42}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    /** thinking (with signature) + text + a fragmented tool_use, stop_reason tool_use. */
    val ANTHROPIC_TOOL = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"usage":{"input_tokens":700,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG-ABC"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Saving that."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"log_issue","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"descri"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"ption\":\"Mic 12 crackled\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":55}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    val ANTHROPIC_TOOL_BAD_JSON = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_3","model":"claude-opus-5-5","usage":{"input_tokens":5,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"log_issue","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"description\": \"cut off"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    val ANTHROPIC_WEB_SEARCH = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_4","model":"claude-opus-5-5","usage":{"input_tokens":50,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"x32 feedback\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://behringer.example/x32","title":"X32 manual","encrypted_content":"ENC"}]}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"See the manual."}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://behringer.example/x32","title":"X32 manual","cited_text":"..."}}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20,"server_tool_use":{"web_search_requests":1}}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    val ANTHROPIC_OVERLOADED = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_5","model":"claude-opus-5-5","usage":{"input_tokens":5,"output_tokens":1}}}""",
        "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
    )

    // ---------------------------------------------------------------- Gemini (Interactions)

    val GEMINI_TEXT = sse(
        "interaction.created" to """{"interaction":{"id":"v1_a","status":"in_progress","object":"interaction","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
        "interaction.status_update" to """{"interaction_id":"v1_a","status":"in_progress","event_type":"interaction.status_update"}""",
        "step.start" to """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        "step.delta" to """{"index":0,"delta":{"signature":"GEM-SIG-1","type":"thought_signature"},"event_type":"step.delta"}""",
        "step.stop" to """{"index":0,"event_type":"step.stop"}""",
        "step.start" to """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
        "step.delta" to """{"index":1,"delta":{"text":"Start with the ","type":"text"},"event_type":"step.delta"}""",
        "step.delta" to """{"index":1,"delta":{"text":"wedge mix.","type":"text"},"event_type":"step.delta"}""",
        "step.delta" to """{"index":1,"delta":{"type":"text_annotation_delta","annotations":[{"type":"url_citation","url":"https://example.com/g","title":"G","start_index":0,"end_index":5}]},"event_type":"step.delta"}""",
        "step.stop" to """{"index":1,"event_type":"step.stop"}""",
        "interaction.completed" to """{"interaction":{"id":"v1_a","status":"completed","usage":{"total_tokens":346,"total_input_tokens":200,"total_cached_tokens":20,"total_output_tokens":90,"total_tool_use_tokens":0,"total_thought_tokens":56},"model":"gemini-3.8-flash"},"event_type":"interaction.completed"}""",
        "done" to "[DONE]",
    )

    val GEMINI_TOOL = sse(
        "interaction.created" to """{"interaction":{"id":"v1_b","status":"in_progress","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
        "step.start" to """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
        "step.delta" to """{"index":0,"delta":{"signature":"GEM-SIG-TOOL","type":"thought_signature"},"event_type":"step.delta"}""",
        "step.stop" to """{"index":0,"event_type":"step.stop"}""",
        "step.start" to """{"index":1,"step":{"type":"function_call","id":"g_call_1","name":"log_issue","arguments":{}},"event_type":"step.start"}""",
        "step.delta" to """{"index":1,"delta":{"type":"arguments_delta","arguments":"{\"description\":"},"event_type":"step.delta"}""",
        "step.delta" to """{"index":1,"delta":{"type":"arguments_delta","arguments":"\"Mic 12 crackled\"}"},"event_type":"step.delta"}""",
        "step.stop" to """{"index":1,"event_type":"step.stop"}""",
        "interaction.completed" to """{"interaction":{"id":"v1_b","status":"requires_action","usage":{"total_input_tokens":138,"total_output_tokens":20,"total_thought_tokens":141,"total_cached_tokens":0},"model":"gemini-3.8-flash"},"event_type":"interaction.completed"}""",
        "done" to "[DONE]",
    )

    val GEMINI_TOOL_BAD_JSON = sse(
        "interaction.created" to """{"interaction":{"id":"v1_c","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
        "step.start" to """{"index":0,"step":{"type":"function_call","id":"g_call_9","name":"log_issue","arguments":{}},"event_type":"step.start"}""",
        "step.delta" to """{"index":0,"delta":{"type":"arguments_delta","arguments":"{\"description\":\"cut"},"event_type":"step.delta"}""",
        "step.stop" to """{"index":0,"event_type":"step.stop"}""",
        "interaction.completed" to """{"interaction":{"id":"v1_c","status":"requires_action","model":"gemini-3.8-flash"},"event_type":"interaction.completed"}""",
    )

    val GEMINI_SEARCH_AND_TOOL = sse(
        "interaction.created" to """{"interaction":{"id":"v1_d","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
        "step.start" to """{"index":0,"step":{"id":"gs1","signature":"","type":"google_search_call"},"event_type":"step.start"}""",
        "step.delta" to """{"index":0,"delta":{"signature":"SRCH-SIG","type":"google_search_call","arguments":{"queries":["x32 gain staging"]}},"event_type":"step.delta"}""",
        "step.stop" to """{"index":0,"event_type":"step.stop"}""",
        "step.start" to """{"index":1,"step":{"call_id":"gs1","signature":"","type":"google_search_result"},"event_type":"step.start"}""",
        "step.delta" to """{"index":1,"delta":{"signature":"SRCH-SIG-2","type":"google_search_result","is_error":false},"event_type":"step.delta"}""",
        "step.stop" to """{"index":1,"event_type":"step.stop"}""",
        "step.start" to """{"index":2,"step":{"type":"model_output"},"event_type":"step.start"}""",
        "step.delta" to """{"index":2,"delta":{"text":"Gain staging notes.","type":"text"},"event_type":"step.delta"}""",
        "step.stop" to """{"index":2,"event_type":"step.stop"}""",
        "interaction.completed" to """{"interaction":{"id":"v1_d","status":"completed","usage":{"total_input_tokens":10,"total_output_tokens":5},"model":"gemini-3.8-flash"},"event_type":"interaction.completed"}""",
    )

    val GEMINI_STREAM_ERROR = sse(
        "interaction.created" to """{"interaction":{"id":"v1_e","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
        "error" to """{"error":{"message":"Deadline expired before operation could complete.","code":"gateway_timeout"},"event_type":"error"}""",
    )
}
