package com.peaceantz.stagescope.phone.ai.dev

import com.peaceantz.stagescope.phone.ai.core.EventSink
import com.peaceantz.stagescope.phone.ai.core.KeyCheck
import com.peaceantz.stagescope.phone.ai.core.ProviderAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderEvent
import com.peaceantz.stagescope.phone.ai.core.ProviderRequest
import com.peaceantz.stagescope.phone.ai.core.ProviderTurn
import com.peaceantz.stagescope.phone.ai.core.StopReason
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary

/**
 * A clearly labelled **development-only** stand-in so the watch<->phone flow can be exercised with
 * no API key and no network cost. It is selectable only while "Development mode" is on in Setup,
 * every reply says so, it never calls a tool, and while dev mode is on `ActionExecutor` refuses to
 * run any Gmail/Calendar action at all. It is never used in place of a real provider that failed.
 */
class DevAdapter(override val id: ProviderId) : ProviderAdapter {
    override suspend fun runTurn(request: ProviderRequest, onEvent: EventSink): ProviderTurn {
        val last = request.history.lastOrNull()?.text.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)
        val text = "<watch_summary>DEV MODE — fake reply, not from ${id.label}.</watch_summary>\n" +
            "This is a development-only test reply. No AI provider was contacted and nothing was sent or created.\n\nYou said: \"$last\""
        onEvent(ProviderEvent.TextDelta(text))
        return ProviderTurn(text, emptyList(), StopReason.END_TURN, UsageSummary(), emptyList(), false, null, "dev-fake")
    }

    override suspend fun validateKey() = KeyCheck(true, message = "Development mode: no key is checked.")
}
