package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.dev.DevAdapter
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ProviderServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var data: PhoneData
    private val credentials = FakeCredentials()
    private var changed = 0
    private lateinit var service: ProviderService
    private val key = "sk-test-ABCDEFGHIJKLMNOP-1234567890"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        data = PhoneData(tmp.newFolder())
        val http = ProviderHttp(OkHttpClient(), setOf(server.hostName), requireHttps = false)
        service = ProviderService(
            data, credentials, http, onChanged = { changed++ }, clock = { 1234L },
            baseUrls = ProviderId.entries.associateWith { server.url("/").toString() },
        )
    }

    @After
    fun tearDown() { runCatching { server.shutdown() } }

    @Test
    fun `saving a key stores it, resets any earlier check and tells the watch`() = runBlocking {
        data.settings.update { it.copy(keyStatus = mapOf(ProviderId.OPENAI to com.peaceantz.stagescope.phone.data.KeyStatus(validated = true))) }
        assertTrue(service.saveKey(ProviderId.OPENAI, key).isSuccess)
        assertTrue(service.hasKey(ProviderId.OPENAI))
        assertFalse("a new key has not been checked yet", data.settings.value.keyStatus[ProviderId.OPENAI]!!.validated)
        assertEquals(1, changed)
        assertNotNull(service.adapter(ProviderId.OPENAI))
    }

    @Test
    fun `a malformed key is refused and nothing is stored`() = runBlocking {
        val r = service.saveKey(ProviderId.OPENAI, "not a key")
        assertTrue(r.isFailure)
        assertFalse(service.hasKey(ProviderId.OPENAI))
        assertNull(service.adapter(ProviderId.OPENAI))
    }

    @Test
    fun `validation records the vendor's model list, flags an unavailable model and never swaps it`() = runBlocking {
        service.saveKey(ProviderId.OPENAI, key)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-6-luna"},{"id":"text-embedding-x"}]}"""))
        val check = service.validate(ProviderId.OPENAI)
        assertTrue(check.ok)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer $key", recorded.getHeader("Authorization"))

        val status = data.settings.value.keyStatus[ProviderId.OPENAI]!!
        assertTrue(status.validated)
        assertEquals(1234L, status.checkedAtEpochMs)
        assertEquals(listOf("gpt-6-luna", "text-embedding-x"), status.availableModels)

        // The default model isn't in this key's list: the app says so; it does NOT pick another one for the person.
        assertEquals("gpt-6.1-sol", data.settings.value.modelFor(ProviderId.OPENAI))
        assertTrue(service.modelLooksUnavailable(ProviderId.OPENAI))
        service.setModel(ProviderId.OPENAI, "gpt-6-luna")
        assertFalse(service.modelLooksUnavailable(ProviderId.OPENAI))
    }

    @Test
    fun `a rejected key is recorded as invalid and never as working`() = runBlocking {
        service.saveKey(ProviderId.ANTHROPIC, key)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val check = service.validate(ProviderId.ANTHROPIC)
        assertFalse(check.ok)
        assertEquals(TurnErrorKind.INVALID_KEY, check.kind)
        val status = data.settings.value.keyStatus[ProviderId.ANTHROPIC]!!
        assertFalse(status.validated)
        assertEquals(TurnErrorKind.INVALID_KEY, status.lastErrorKind)
        assertFalse("the key never appears in what is stored or shown", status.lastErrorMessage.orEmpty().contains(key))
    }

    @Test
    fun `validating with no key says so without any network call`() = runBlocking {
        val check = service.validate(ProviderId.XAI)
        assertFalse(check.ok)
        assertEquals(TurnErrorKind.MISSING_KEY, check.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `only catalog models can be selected, and a provider's model is never another provider's`() = runBlocking {
        service.setModel(ProviderId.OPENAI, "claude-opus-5-5")
        service.setModel(ProviderId.OPENAI, "made-up-model")
        assertEquals(ModelCatalog.defaultFor(ProviderId.OPENAI).id, data.settings.value.modelFor(ProviderId.OPENAI))
        service.setModel(ProviderId.OPENAI, "gpt-6-astra")
        assertEquals("gpt-6-astra", data.settings.value.modelFor(ProviderId.OPENAI))
    }

    @Test
    fun `removing a key removes the adapter and its check`() = runBlocking {
        service.saveKey(ProviderId.GEMINI, key)
        assertNotNull(service.adapter(ProviderId.GEMINI))
        service.removeKey(ProviderId.GEMINI)
        assertNull(service.adapter(ProviderId.GEMINI))
        assertNull(data.settings.value.keyStatus[ProviderId.GEMINI])
    }

    @Test
    fun `a stored key that can't be decrypted behaves like no key`() = runBlocking {
        service.saveKey(ProviderId.OPENAI, key)
        credentials.unreadable += ProviderId.OPENAI
        assertNull(service.adapter(ProviderId.OPENAI))
    }

    @Test
    fun `test mode serves the offline fake and never needs a key`() = runBlocking {
        assertNull(service.adapter(ProviderId.OPENAI))
        data.settings.update { it.copy(devMode = true) }
        assertTrue(service.adapter(ProviderId.OPENAI) is DevAdapter)
        assertTrue(service.adapter(ProviderId.GEMINI) is DevAdapter)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider thorough and web-search choices are stored per provider`() = runBlocking {
        service.setThorough(ProviderId.OPENAI, true)
        service.setWebSearch(ProviderId.GEMINI, true)
        val s = data.settings.value
        assertTrue(s.thorough[ProviderId.OPENAI] == true && s.thorough[ProviderId.GEMINI] != true)
        assertTrue(s.webSearchFor(ProviderId.GEMINI) && !s.webSearchFor(ProviderId.OPENAI))
    }
}
