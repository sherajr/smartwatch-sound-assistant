package com.peaceantz.stagescope.phone.google

import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.show.EmailAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.TimeUnit

class MimeBuilderTest {
    private val now = ZonedDateTime.of(2026, 3, 14, 20, 0, 0, 0, ZoneOffset.UTC)

    private fun build(subject: String = "Sound report", body: String = "Hello\nWorld", to: List<EmailAddress> = listOf(EmailAddress("dana@theatre.example", "Dana Whitfield"))) =
        MimeBuilder.build("sound@example.com", to, listOf(EmailAddress("pm@theatre.example")), listOf(EmailAddress("archive@theatre.example")), subject, body, now)

    @Test
    fun `headers are complete and the body is UTF-8 base64 with CRLF`() {
        val m = build()
        val (head, body) = m.split("\r\n\r\n", limit = 2)
        assertTrue(head.contains("From: sound@example.com"))
        assertTrue(head.contains("To: Dana Whitfield <dana@theatre.example>"))
        assertTrue(head.contains("Cc: pm@theatre.example"))
        assertTrue(head.contains("Bcc: archive@theatre.example"))
        assertTrue(head.contains("Subject: Sound report"))
        assertTrue(head.contains("MIME-Version: 1.0"))
        assertTrue(head.contains("Content-Type: text/plain; charset=\"UTF-8\""))
        assertTrue(head.contains("Content-Transfer-Encoding: base64"))
        assertTrue(Regex("Message-ID: <[0-9a-f-]+@stagescope.local>").containsMatchIn(head))
        assertTrue(head.contains("Date: Sat, 14 Mar 2026 20:00:00 GMT"))
        val decoded = String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
        assertEquals("Hello\r\nWorld", decoded)
    }

    @Test
    fun `non-ASCII subject and names become RFC 2047 encoded words that decode back`() {
        val m = build(subject = "Répétition générale — son", to = listOf(EmailAddress("dana@theatre.example", "Dänä Whitfield")))
        val head = m.substringBefore("\r\n\r\n")
        assertFalse("headers must be pure ASCII", head.any { it.code > 126 })
        val subjectLine = head.lines().first { it.startsWith("Subject:") }.removePrefix("Subject: ")
        val words = Regex("=\\?UTF-8\\?B\\?([^?]+)\\?=").findAll(head.substringAfter("Subject: ").substringBefore("\r\nDate:")).map { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) }.joinToString("")
        assertEquals("Répétition générale — son", words)
        assertTrue(subjectLine.startsWith("=?UTF-8?B?"))
        assertTrue(head.contains("=?UTF-8?B?"))
    }

    @Test
    fun `line breaks in any header value are refused - no header injection`() {
        listOf("Subject\r\nBcc: evil@example.com", "Hi\nthere").forEach {
            try { build(subject = it); fail("expected InvalidMessage") } catch (_: MimeBuilder.InvalidMessage) { }
        }
        try { build(to = listOf(EmailAddress("a@b.com\r\nBcc: evil@example.com"))); fail() } catch (_: MimeBuilder.InvalidMessage) { }
        try { build(to = listOf(EmailAddress("dana@theatre.example", "Dana\r\nBcc: evil@example.com"))); fail() } catch (_: MimeBuilder.InvalidMessage) { }
        try { build(to = emptyList()); fail() } catch (_: MimeBuilder.InvalidMessage) { }
    }

    @Test
    fun `gmail raw is base64url of the whole message`() {
        val m = build()
        val raw = MimeBuilder.toGmailRaw(m)
        assertFalse(raw.contains('+') || raw.contains('/'))
        assertEquals(m, String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8))
    }
}

class GmailClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GmailClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = GmailClient(ProviderHttp(OkHttpClient(), setOf(server.hostName), requireHttps = false), server.url("/").toString())
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun send() = runBlocking { client.send("tok-123", "UkFX") }

    @Test
    fun `success returns the real message id and posts the raw message with a bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"18c0ffee","threadId":"t-1","labelIds":["SENT"]}"""))
        val r = send() as GmailSendResult.Sent
        assertEquals("18c0ffee", r.messageId)
        assertEquals("t-1", r.threadId)
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/gmail/v1/users/me/messages/send", req.path)
        assertEquals("Bearer tok-123", req.getHeader("Authorization"))
        assertEquals("UkFX", Json.parseToJsonElement(req.body.readUtf8()).jsonObject["raw"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 200 without a message id is uncertain, not success`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(send() is GmailSendResult.Uncertain)
    }

    @Test
    fun `client errors are definitely not submitted`() {
        mapOf(400 to "rejected", 401 to "auth", 403 to "permission", 429 to "rate_limit").forEach { (status, code) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"code":$status,"message":"nope"}}"""))
            val r = send() as GmailSendResult.NotSubmitted
            assertEquals(code, r.code)
            assertTrue(r.message.contains("Nothing was sent"))
        }
    }

    @Test
    fun `a server error or a dropped connection after sending is uncertain and says so`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"backend error"}}"""))
        assertTrue(send() is GmailSendResult.Uncertain)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = send()
        assertTrue("a reset after the request was written may have reached Gmail: $r", r is GmailSendResult.Uncertain)
        assertTrue((r as GmailSendResult.Uncertain).message.contains("unknown whether"))
    }

    @Test
    fun `failing to connect at all is definitely not submitted`() {
        server.shutdown()
        val r = send()
        assertTrue("connection refused never reached Gmail: $r", r is GmailSendResult.NotSubmitted)
    }

    @Test
    fun `a dropped connection is never silently re-sent by the HTTP layer`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // If OkHttp retried behind our back, this would be consumed and the email would go out twice.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"would-be-a-duplicate"}"""))
        val r = send()
        assertTrue(r is GmailSendResult.Uncertain)
        assertEquals("exactly one request may ever be made for one send", 1, server.requestCount)
    }

    @Test
    fun `a server error is not retried either`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"would-be-a-duplicate"}"""))
        assertTrue(send() is GmailSendResult.Uncertain)
        assertEquals(1, server.requestCount)
    }
}

class CalendarClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CalendarClient
    private val delays = mutableListOf<Long>()

    private val draft = CalendarDraft(
        title = "Sound check", eventId = "ss0123456789abcdef0123456789abcd", timezoneId = "America/New_York",
        startLocal = "2026-03-14T16:00", endLocal = "2026-03-14T17:00", startOffset = "-04:00", endOffset = "-04:00",
        calendarId = "primary", calendarLabel = "Primary calendar",
    )

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = CalendarClient(ProviderHttp(OkHttpClient(), setOf(server.hostName), requireHttps = false), server.url("/").toString(), sleeper = { delays += it })
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun create(d: CalendarDraft = draft) = runBlocking { client.create("tok", d, "action-1") }

    @Test
    fun `creates with the persisted id, an explicit zone and offset, and a private marker`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ss0123456789abcdef0123456789abcd","htmlLink":"https://calendar.example/e/1"}"""))
        val r = create() as CalendarCreateResult.Created
        assertEquals("https://calendar.example/e/1", r.htmlLink)
        assertFalse(r.reconciled)
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/calendar/v3/calendars/primary/events?sendUpdates=none", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("ss0123456789abcdef0123456789abcd", body["id"]!!.jsonPrimitive.content)
        assertEquals("2026-03-14T16:00:00-04:00", body["start"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)
        assertEquals("America/New_York", body["start"]!!.jsonObject["timeZone"]!!.jsonPrimitive.content)
        assertEquals("action-1", body["extendedProperties"]!!.jsonObject["private"]!!.jsonObject["stagescopeActionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invitees are added only when present and then updates are sent`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x"}"""))
        create(draft.copy(invitees = listOf(EmailAddress("guest@example.com"))))
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.endsWith("sendUpdates=all"))
        assertEquals("guest@example.com", Json.parseToJsonElement(req.body.readUtf8()).jsonObject["attendees"]!!.jsonArray.single().jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an all-day event uses dates, not date-times`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x"}"""))
        create(draft.copy(allDay = true, startLocal = "2026-03-14T00:00", endLocal = "2026-03-15T00:00"))
        val body = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("2026-03-14", body["start"]!!.jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2026-03-15", body["end"]!!.jsonObject["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a duplicate id is reconciled - our own event counts as created, a foreign one is a conflict`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"message":"The requested identifier already exists."}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ss0123456789abcdef0123456789abcd","summary":"Sound check","status":"confirmed","htmlLink":"https://calendar.example/e/1","extendedProperties":{"private":{"stagescopeActionId":"action-1"}}}"""))
        val ours = create() as CalendarCreateResult.Created
        assertTrue(ours.reconciled)

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"message":"exists"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ss0123456789abcdef0123456789abcd","summary":"Someone else's event","status":"confirmed"}"""))
        val foreign = create() as CalendarCreateResult.NotCreated
        assertEquals("conflict", foreign.code)
    }

    @Test
    fun `a network failure retries with the same id and then confirms rather than guessing`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ss0123456789abcdef0123456789abcd"}"""))
        val r = create() as CalendarCreateResult.Created
        assertTrue("a retry after a failure is marked reconciled", r.reconciled)
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(
            Json.parseToJsonElement(first.body.readUtf8()).jsonObject["id"],
            Json.parseToJsonElement(second.body.readUtf8()).jsonObject["id"],
        )
        assertEquals(listOf(1000L), delays)
    }

    @Test
    fun `when nothing answers, the outcome is uncertain`() {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) // the reconcile lookup also fails
        assertTrue(create() is CalendarCreateResult.Uncertain)
    }

    @Test
    fun `auth permission and bad requests are definite failures with actionable text`() {
        mapOf(400 to "rejected", 401 to "auth", 403 to "permission", 404 to "not_found", 429 to "rate_limit").forEach { (status, code) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"message":"x"}}"""))
            assertEquals(code, (create() as CalendarCreateResult.NotCreated).code)
        }
    }
}
