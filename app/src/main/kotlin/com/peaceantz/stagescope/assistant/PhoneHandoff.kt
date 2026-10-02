package com.peaceantz.stagescope.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Asks the paired phone to open StageScope at a saved item (a conversation or an action), by its stored id.
 * Starting a remote activity is only a *request*: it may need the phone unlocked, and the phone can refuse. So
 * this reports "requested", never "opened" -- the watch says "Opened on phone" only after the phone itself
 * confirms the item is on screen (a [com.peaceantz.stagescope.shared.protocol.ContinueReply]).
 */
class PhoneHandoff(private val context: Context) {
    /** True if the Wear OS remote-activity request was handed over. Never throws. */
    suspend fun requestOpen(conversationId: String?, actionId: String?, nodeId: String? = null): Boolean {
        val uri = deepLink(conversationId, actionId)
        val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        return runCatching {
            val helper = RemoteActivityHelper(context, Executor { it.run() })
            withTimeoutOrNull(5_000) { helper.startRemoteActivity(intent, nodeId).awaitResult() }
            true
        }.getOrDefault(false)
    }

    companion object {
        /** `stagescope://task/<conversation|action>/<id>` -- carries only an opaque stored id, never content. */
        fun deepLink(conversationId: String?, actionId: String?): Uri = when {
            actionId != null -> "stagescope://task/action/$actionId".toUri()
            conversationId != null -> "stagescope://task/conversation/$conversationId".toUri()
            // No item: just bring the phone app to its Home (setup/status) screen.
            else -> "stagescope://task/home".toUri()
        }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e.cause ?: e)
            }
        },
        Executor { it.run() },
    )
    cont.invokeOnCancellation { cancel(false) }
}
