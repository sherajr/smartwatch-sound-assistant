package com.peaceantz.stagescope.phone.ui

import android.content.Intent

/**
 * `stagescope://task/<conversation|action>/<id>` -- what a "continue on phone" notification carries.
 * Only an opaque id travels in the link; the content is looked up locally, so a link can't smuggle text.
 */
/** "Scroll this conversation to that action". [nonce] makes a second delivery of the same link count as a new request. */
data class ChatFocus(val actionId: String, val nonce: Long = System.nanoTime())

data class DeepLink(val conversationId: String?, val actionId: String?) {
    companion object {
        fun parse(intent: Intent?): DeepLink? {
            val uri = intent?.data ?: return null
            return parse(uri.scheme, uri.host, uri.pathSegments)
        }

        /** Pure so it can be unit-tested without Android types. */
        fun parse(scheme: String?, host: String?, segments: List<String>): DeepLink? {
            if (scheme != "stagescope" || host != "task" || segments.size != 2) return null
            val id = segments[1].takeIf { it.isNotBlank() && it.length <= 80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } } ?: return null
            return when (segments[0]) {
                "conversation" -> DeepLink(conversationId = id, actionId = null)
                "action" -> DeepLink(conversationId = null, actionId = id)
                else -> null
            }
        }
    }
}
