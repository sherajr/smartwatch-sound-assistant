package com.peaceantz.stagescope.phone.ai.core

import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The only hosts StageScope ever talks to with a credential or a bearer token. */
object ApprovedHosts {
    const val OPENAI = "api.openai.com"
    const val GEMINI = "generativelanguage.googleapis.com"
    const val XAI = "api.x.ai"
    const val ANTHROPIC = "api.anthropic.com"
    const val GMAIL = "gmail.googleapis.com"
    const val GOOGLE_APIS = "www.googleapis.com"

    val PRODUCTION: Set<String> = setOf(OPENAI, GEMINI, XAI, ANTHROPIC, GMAIL, GOOGLE_APIS)
}

/**
 * Blocks any request whose host is not on the allow-list (or that is not https, outside tests) *before*
 * it leaves the process. Redirects are disabled on the client, so a 3xx can never carry a key to
 * another host either.
 */
class FixedDestinationInterceptor(
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.host !in allowedHosts) throw IOException("Blocked: host not on StageScope's approved list")
        if (requireHttps && !url.isHttps) throw IOException("Blocked: https required")
        return chain.proceed(chain.request())
    }
}

object Redactor {
    private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "proxy-authorization", "cookie", "set-cookie")
    private val KEY_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("sk-ant-[A-Za-z0-9_\\-]{8,}"),
        Regex("xai-[A-Za-z0-9_\\-]{8,}"),
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("ya29\\.[0-9A-Za-z_\\-]{10,}"),
        Regex("Bearer\\s+[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE),
    )

    fun header(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) "[redacted]" else value

    /** Scrubs anything key-shaped from text that may be shown or logged (error bodies, exceptions). */
    fun text(raw: String): String = KEY_PATTERNS.fold(raw) { acc, p -> acc.replace(p, "[redacted]") }

    fun mask(key: String): String = when {
        key.length <= 8 -> "••••"
        else -> key.take(3) + "…" + key.takeLast(4)
    }
}

data class HttpResult(val status: Int, val body: String, val retryAfterMs: Long?)

/**
 * The one OkHttp wrapper under every adapter, Gmail and Calendar: cancellation closes the socket,
 * failures map to [ProviderException] with a plain-language message, and nothing here ever logs
 * headers or request bodies.
 */
class ProviderHttp(
    base: OkHttpClient = OkHttpClient(),
    allowedHosts: Set<String> = ApprovedHosts.PRODUCTION,
    requireHttps: Boolean = true,
) {
    val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // OkHttp would otherwise silently re-send a request on a new connection after some connection
        // failures. For a POST that is a hidden duplicate (a second email, a second billed AI call), so
        // retrying is only ever done by our own code, which knows what is safe to repeat.
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Idle gap between bytes. Reasoning models can think for a while before the first token.
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .addInterceptor(FixedDestinationInterceptor(allowedHosts, requireHttps))
        .build()

    /**
     * For state-changing calls (Gmail send, Calendar insert): a dedicated client that never reuses a
     * pooled connection, so a stale keep-alive socket can't fail a send ambiguously, and a failure to
     * connect is unambiguously *before* anything was transmitted.
     */
    private val freshConnectionClient: OkHttpClient by lazy {
        client.newBuilder().connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build()
    }

    fun jsonPost(url: HttpUrl, headers: Map<String, String>, json: String, stream: Boolean = false): Request =
        Request.Builder().url(url)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Content-Type", "application/json")
                if (stream) header("Accept", "text/event-stream")
            }
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

    fun get(url: HttpUrl, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()

    /** A non-streaming call. Non-2xx statuses are returned, not thrown, so callers can read the error body. */
    suspend fun execute(request: Request, freshConnection: Boolean = false): HttpResult = withContext(Dispatchers.IO) {
        val response = await((if (freshConnection) freshConnectionClient else client).newCall(request))
        response.use {
            val text = it.body.string()
            HttpResult(it.code, text, retryAfter(it))
        }
    }

    /**
     * Streams a 2xx response through [consume] (given the open response) on the IO dispatcher.
     * Non-2xx is read (bounded) and thrown as a [ProviderException] via [errorMapper].
     *
     * Cancellation: a blocking socket read is not interruptible by a coroutine, so a watcher child
     * suspended in `awaitCancellation()` calls `Call.cancel()` the instant the parent is cancelled;
     * that closes the socket and unblocks the read (the user pressing Cancel on the watch must stop
     * the stream -- and the billing -- promptly, not after the model finishes).
     */
    suspend fun <T> stream(
        request: Request,
        errorMapper: (status: Int, body: String, retryAfterMs: Long?) -> ProviderException,
        consume: (Response) -> T,
    ): T = coroutineScope {
        val call = client.newCall(request)
        val watcher = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    // A socket closed by our own Call.cancel() is a cancellation, not a network error.
                    ensureActive()
                    throw mapIo(e, afterOutput = false)
                }
                response.use {
                    if (!it.isSuccessful) {
                        val body = runCatching { it.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrDefault("")
                        throw errorMapper(it.code, body, retryAfter(it))
                    }
                    try {
                        consume(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        throw e
                    } catch (e: IOException) {
                        ensureActive()
                        throw mapIo(e, afterOutput = true)
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(mapIo(e, afterOutput = false))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    private fun retryAfter(response: Response): Long? {
        response.header("retry-after-ms")?.toLongOrNull()?.let { return it }
        response.header("retry-after")?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return null
    }

    companion object {
        const val MAX_ERROR_BODY_BYTES = 8_192L

        /** Network failure -> kind. [afterOutput] = bytes of the response had already arrived. */
        fun mapIo(e: IOException, afterOutput: Boolean): ProviderException = when (e) {
            is UnknownHostException, is ConnectException ->
                ProviderException(TurnErrorKind.NETWORK, "Can't reach the service. Check the phone's internet connection.", retryable = !afterOutput, cause = e)
            is SocketTimeoutException ->
                ProviderException(TurnErrorKind.TIMEOUT, "The service took too long to respond.", retryable = !afterOutput, cause = e)
            is SSLException ->
                ProviderException(TurnErrorKind.NETWORK, "A secure connection couldn't be established.", retryable = false, cause = e)
            else -> if (e.message?.contains("Canceled", ignoreCase = true) == true) {
                ProviderException(TurnErrorKind.CANCELLED, "Cancelled.", cause = e)
            } else if (e.message?.startsWith("Blocked:") == true) {
                ProviderException(TurnErrorKind.BAD_REQUEST, "StageScope blocked a request to an unapproved address.", cause = e)
            } else {
                ProviderException(TurnErrorKind.NETWORK, "The connection was interrupted.", retryable = !afterOutput, cause = e)
            }
        }
    }
}

/** Shared HTTP-status -> user-facing error mapping; each adapter supplies its own body parsing. */
object HttpErrors {
    fun map(status: Int, vendorMessage: String?, vendorCode: String?, retryAfterMs: Long?): ProviderException {
        val msg = vendorMessage?.let { Redactor.text(it).take(240) }
        val code = vendorCode?.lowercase().orEmpty()
        val lower = (msg ?: "").lowercase()
        return when {
            status == 401 -> ProviderException(TurnErrorKind.INVALID_KEY, "The API key was rejected. Check or replace it in Providers.", status)
            status == 403 -> ProviderException(TurnErrorKind.PERMISSION_DENIED, "This key isn't allowed to use that model or feature.${msg?.let { " ($it)" } ?: ""}", status)
            status == 402 || code.contains("insufficient_quota") || code.contains("billing") || lower.contains("exceeded your current quota") || lower.contains("credit balance") ->
                ProviderException(TurnErrorKind.QUOTA_EXHAUSTED, "The account is out of credit or over its quota. Add credit with the provider.", status)
            status == 404 || code.contains("model_not_found") || (status == 400 && lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist"))) ->
                ProviderException(TurnErrorKind.MODEL_UNAVAILABLE, "That model isn't available for this key. Pick another model in Providers — StageScope won't switch models for you.", status)
            status == 408 -> ProviderException(TurnErrorKind.TIMEOUT, "The service timed out.", status, retryAfterMs, retryable = true)
            status == 413 || lower.contains("context length") || lower.contains("too long") || lower.contains("maximum context") || code.contains("context_length") ->
                ProviderException(TurnErrorKind.CONTEXT_TOO_LONG, "The conversation is too long for this model. Start a new conversation.", status)
            status == 429 -> ProviderException(TurnErrorKind.RATE_LIMITED, "Rate limit reached. Wait a moment and try again.", status, retryAfterMs, retryable = true)
            status == 529 || status == 503 -> ProviderException(TurnErrorKind.OVERLOADED, "The service is overloaded right now.", status, retryAfterMs, retryable = true)
            status in 500..599 -> ProviderException(TurnErrorKind.SERVER_ERROR, "The service had an internal error.", status, retryAfterMs, retryable = true)
            status == 400 || status == 422 -> ProviderException(TurnErrorKind.BAD_REQUEST, "The service rejected the request.${msg?.let { " ($it)" } ?: ""}", status)
            else -> ProviderException(TurnErrorKind.UNKNOWN, "Unexpected response ($status).", status)
        }
    }
}
