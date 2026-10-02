package com.peaceantz.stagescope.phone.google

import com.peaceantz.stagescope.phone.ai.core.HttpErrors
import com.peaceantz.stagescope.phone.ai.core.ProviderException
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.core.get
import com.peaceantz.stagescope.phone.ai.core.obj
import com.peaceantz.stagescope.phone.ai.core.parseObject
import com.peaceantz.stagescope.phone.ai.core.arr
import com.peaceantz.stagescope.phone.ai.core.str
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The narrow scopes StageScope asks for -- only what an implemented feature needs. */
object GoogleScopes {
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    /** Create/edit events on calendars the person owns; narrower than calendar.events. */
    const val CALENDAR_EVENTS_OWNED = "https://www.googleapis.com/auth/calendar.events.owned"
    /** Only to offer a calendar picker. */
    const val CALENDAR_LIST_READONLY = "https://www.googleapis.com/auth/calendar.calendarlist.readonly"
    /** Identity scopes, only so the review card can show WHICH account will send / create. */
    const val OPENID = "openid"
    const val EMAIL = "email"

    val GMAIL_FEATURE = listOf(GMAIL_SEND, OPENID, EMAIL)
    val CALENDAR_FEATURE = listOf(CALENDAR_EVENTS_OWNED, OPENID, EMAIL)
    val CALENDAR_PICKER = listOf(CALENDAR_LIST_READONLY)
}

sealed interface GmailSendResult {
    /** The API returned a message id: a real receipt. */
    data class Sent(val messageId: String, val threadId: String?) : GmailSendResult

    /** The request definitely did not reach Gmail's send (rejected, or never connected). Safe to fix and retry by hand. */
    data class NotSubmitted(val code: String, val message: String) : GmailSendResult

    /** The request may have gone through (timeout, reset, 5xx). NEVER auto-retried: a second send could duplicate the email. */
    data class Uncertain(val message: String) : GmailSendResult
}

/**
 * Sends one MIME message with `users.messages.send`. The outcome is classified conservatively:
 * Gmail offers no general exactly-once guarantee from a client-side id, so anything ambiguous after
 * the request was written is *uncertain*, not failed -- and the caller must not retry it.
 */
class GmailClient(
    private val http: ProviderHttp,
    baseUrl: String = "https://gmail.googleapis.com/",
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun send(accessToken: String, rawBase64Url: String): GmailSendResult {
        val request = http.jsonPost(
            base.newBuilder().addPathSegments("gmail/v1/users/me/messages/send").build(),
            mapOf("Authorization" to "Bearer $accessToken"),
            buildJsonObject { put("raw", rawBase64Url) }.toString(),
        )
        val result = try {
            http.execute(request, freshConnection = true)
        } catch (e: ProviderException) {
            return if (neverReachedService(e)) GmailSendResult.NotSubmitted("network", "Couldn't connect to Gmail. Nothing was sent.")
            else GmailSendResult.Uncertain("The connection dropped while sending, so it's unknown whether Gmail accepted the email.")
        }
        val json = parseObject(result.body)
        return when (result.status) {
            200 -> {
                val id = json?.get("id").str()
                if (id == null) GmailSendResult.Uncertain("Gmail answered without a message id, so it's unknown whether the email was sent.")
                else GmailSendResult.Sent(id, json?.get("threadId").str())
            }
            400 -> GmailSendResult.NotSubmitted("rejected", "Gmail rejected the message: ${googleMessage(json) ?: "invalid request"}. Nothing was sent.")
            401 -> GmailSendResult.NotSubmitted("auth", "Google sign-in expired or was revoked. Reconnect Gmail in Setup. Nothing was sent.")
            403 -> GmailSendResult.NotSubmitted("permission", "Google didn't allow sending (${googleMessage(json) ?: "permission denied"}). Reconnect Gmail in Setup. Nothing was sent.")
            429 -> GmailSendResult.NotSubmitted("rate_limit", "Gmail is rate limiting this account. Try again in a few minutes. Nothing was sent.")
            else -> GmailSendResult.Uncertain("Gmail returned an unexpected error (${result.status}), so it's unknown whether the email was sent.")
        }
    }

    private fun neverReachedService(e: ProviderException): Boolean =
        e.cause is UnknownHostException || e.cause is ConnectException || e.cause is SSLHandshakeException ||
            e.cause?.message?.startsWith("Blocked:") == true

    private fun googleMessage(json: kotlinx.serialization.json.JsonObject?): String? = json?.get("error", "message").str()?.take(160)
}

sealed interface CalendarCreateResult {
    data class Created(val eventId: String, val htmlLink: String?, val reconciled: Boolean) : CalendarCreateResult
    data class NotCreated(val code: String, val message: String) : CalendarCreateResult
    data class Uncertain(val message: String) : CalendarCreateResult
}

data class CalendarInfo(val id: String, val summary: String, val primary: Boolean)

/**
 * Creates one event with `events.insert` using the draft's persisted, action-derived event id. A
 * network failure retries with the **same id**; a 409 (id already exists) is *reconciled* -- the
 * existing event is fetched and checked against what we meant to create -- never assumed to be ours.
 */
class CalendarClient(
    private val http: ProviderHttp,
    baseUrl: String = "https://www.googleapis.com/",
    private val maxAttempts: Int = 3,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun create(accessToken: String, draft: CalendarDraft, actionId: String): CalendarCreateResult {
        val body = eventJson(draft, actionId).toString()
        var lastNetworkError: ProviderException? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) sleeper(500L shl attempt)
            val url = base.newBuilder().addPathSegments("calendar/v3/calendars").addPathSegment(draft.calendarId).addPathSegment("events")
                .addQueryParameter("sendUpdates", if (draft.invitees.isEmpty()) "none" else "all").build()
            val result = try {
                http.execute(http.jsonPost(url, mapOf("Authorization" to "Bearer $accessToken"), body), freshConnection = true)
            } catch (e: ProviderException) {
                lastNetworkError = e
                return@repeat
            }
            val json = parseObject(result.body)
            when (result.status) {
                200 -> return json?.get("id").str()?.let { CalendarCreateResult.Created(it, json?.get("htmlLink").str(), reconciled = attempt > 0) }
                    ?: CalendarCreateResult.Uncertain("Calendar answered without an event id.")
                409 -> return reconcile(accessToken, draft, actionId)
                400 -> return CalendarCreateResult.NotCreated("rejected", "Calendar rejected the event: ${json?.get("error", "message").str()?.take(160) ?: "invalid request"}.")
                401 -> return CalendarCreateResult.NotCreated("auth", "Google sign-in expired or was revoked. Reconnect Calendar in Setup.")
                403 -> return CalendarCreateResult.NotCreated("permission", "Google didn't allow creating events there. Check which calendar is selected and reconnect Calendar in Setup.")
                404 -> return CalendarCreateResult.NotCreated("not_found", "That calendar wasn't found.")
                429 -> return CalendarCreateResult.NotCreated("rate_limit", "Calendar is rate limiting this account. Try again shortly.")
                else -> lastNetworkError = ProviderException(TurnErrorKind.SERVER_ERROR, "Calendar returned ${result.status}.", result.status, retryable = true)
            }
        }
        // Out of attempts without an answer: it may or may not exist. Ask Calendar rather than guess.
        return when (val check = reconcile(accessToken, draft, actionId)) {
            is CalendarCreateResult.Created -> check
            else -> CalendarCreateResult.Uncertain("Calendar didn't answer (${lastNetworkError?.message ?: "no response"}), so it's unknown whether the event was created.")
        }
    }

    /** Fetch the event with our id and verify it is the one this action meant to create. */
    private suspend fun reconcile(accessToken: String, draft: CalendarDraft, actionId: String): CalendarCreateResult {
        val url = base.newBuilder().addPathSegments("calendar/v3/calendars").addPathSegment(draft.calendarId)
            .addPathSegment("events").addPathSegment(draft.eventId).build()
        val result = try {
            http.execute(http.get(url, mapOf("Authorization" to "Bearer $accessToken")))
        } catch (e: ProviderException) {
            return CalendarCreateResult.Uncertain("Couldn't confirm with Calendar whether the event exists.")
        }
        if (result.status == 404) return CalendarCreateResult.NotCreated("not_found", "The event isn't in the calendar.")
        if (result.status != 200) return CalendarCreateResult.Uncertain("Couldn't confirm with Calendar whether the event exists (${result.status}).")
        val json = parseObject(result.body) ?: return CalendarCreateResult.Uncertain("Calendar's answer couldn't be read.")
        val ours = json["extendedProperties", "private", "stagescopeActionId"].str() == actionId &&
            json["summary"].str() == draft.title && json["status"].str() != "cancelled"
        return if (ours) CalendarCreateResult.Created(draft.eventId, json["htmlLink"].str(), reconciled = true)
        else CalendarCreateResult.NotCreated("conflict", "An event with this id already exists but isn't the one StageScope created, so it was left alone.")
    }

    suspend fun listCalendars(accessToken: String): List<CalendarInfo> {
        val url = base.newBuilder().addPathSegments("calendar/v3/users/me/calendarList").addQueryParameter("minAccessRole", "owner").build()
        val result = http.execute(http.get(url, mapOf("Authorization" to "Bearer $accessToken")))
        if (result.status !in 200..299) throw HttpErrors.map(result.status, null, null, null)
        return parseObject(result.body)?.get("items").arr()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            CalendarInfo(o["id"].str() ?: return@mapNotNull null, o["summaryOverride"].str() ?: o["summary"].str() ?: "Calendar", o["primary"]?.str() == "true")
        }.orEmpty()
    }

    internal fun eventJson(d: CalendarDraft, actionId: String) = buildJsonObject {
        put("id", d.eventId)
        put("summary", d.title)
        d.location?.let { put("location", it) }
        d.description?.let { put("description", it) }
        if (d.allDay) {
            putJsonObject("start") { put("date", d.startLocal.take(10)) }
            putJsonObject("end") { put("date", d.endLocal.take(10)) }
        } else {
            putJsonObject("start") { put("dateTime", d.startLocal + ":00" + d.startOffset); put("timeZone", d.timezoneId) }
            putJsonObject("end") { put("dateTime", d.endLocal + ":00" + d.endOffset); put("timeZone", d.timezoneId) }
        }
        if (d.invitees.isNotEmpty()) {
            put("attendees", buildJsonArray { d.invitees.forEach { add(buildJsonObject { put("email", it.address) }) } })
        }
        // Lets a later reconcile prove an event with this id is the one this action created.
        putJsonObject("extendedProperties") { putJsonObject("private") { put("stagescopeActionId", actionId) } }
    }
}
