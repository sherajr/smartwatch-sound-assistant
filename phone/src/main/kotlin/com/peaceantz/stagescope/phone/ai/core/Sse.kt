package com.peaceantz.stagescope.phone.ai.core

import okio.BufferedSource
import java.io.IOException

data class SseEvent(val event: String?, val data: String)

/**
 * A minimal, strict Server-Sent Events reader (https://html.spec.whatwg.org/multipage/server-sent-events.html):
 * `event:`/`data:` fields, multi-line `data:`, `:` comment/keep-alive lines ignored, a blank line
 * dispatches. Reads from a [BufferedSource] so cancelling the OkHttp call unblocks it immediately.
 *
 * Lines and events are bounded so a hostile or broken stream cannot exhaust memory.
 */
object Sse {
    private const val MAX_LINE_BYTES = 4L * 1024 * 1024

    /** Calls [onEvent] for each event; return `false` from it to stop reading early. */
    fun read(source: BufferedSource, onEvent: (SseEvent) -> Boolean) {
        var eventName: String? = null
        val data = StringBuilder()
        var hasData = false

        fun dispatch(): Boolean {
            if (!hasData) {
                eventName = null
                return true
            }
            val payload = data.toString().removeSuffix("\n")
            val name = eventName
            eventName = null
            data.setLength(0)
            hasData = false
            return onEvent(SseEvent(name, payload))
        }

        while (true) {
            val line = try {
                source.readUtf8LineStrict(MAX_LINE_BYTES)
            } catch (e: java.io.EOFException) {
                // Stream ended; a final event without a trailing blank line still counts.
                dispatch()
                return
            }
            if (line.isEmpty()) {
                if (!dispatch()) return
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> eventName = value
                "data" -> {
                    data.append(value).append('\n')
                    hasData = true
                    if (data.length > MAX_LINE_BYTES) throw IOException("SSE event too large")
                }
                else -> Unit // id:/retry: and unknown fields are irrelevant to our use
            }
        }
    }
}
