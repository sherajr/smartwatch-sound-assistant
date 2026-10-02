package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.security.EncryptedFileCredentialStore
import com.peaceantz.stagescope.shared.assistant.ProviderId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption logic with a software AES key standing in for the Keystore key. (The Keystore
 * variant additionally *requires* the cipher to choose the IV, which is why encryption never supplies one.)
 */
class CredentialStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val keyA: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val keyB: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val secret = "sk-test-ABCDEFGHIJKLMNOP-1234567890"
    private val file get() = File(tmp.root, "credentials.json")

    private fun store(key: SecretKey = keyA) = EncryptedFileCredentialStore(file) { key }

    @Test
    fun `a saved key reads back and the file never contains the plaintext`() = runBlocking {
        val s = store()
        s.put(ProviderId.OPENAI, secret)
        assertTrue(s.has(ProviderId.OPENAI))
        assertEquals(secret, s.key(ProviderId.OPENAI))
        val onDisk = file.readText()
        assertFalse(onDisk.contains(secret))
        assertFalse("not even a recognisable fragment", onDisk.contains("ABCDEFGHIJKLMNOP"))
    }

    @Test
    fun `keys survive an app restart`() = runBlocking {
        store().put(ProviderId.ANTHROPIC, secret)
        assertEquals(secret, store().key(ProviderId.ANTHROPIC))
    }

    @Test
    fun `every save uses a fresh IV so the same key never encrypts to the same bytes`() = runBlocking {
        val s = store()
        s.put(ProviderId.OPENAI, secret)
        val first = Regex("\"OPENAI\":\"([^\"]+)\"").find(file.readText())!!.groupValues[1]
        s.put(ProviderId.OPENAI, secret)
        val second = Regex("\"OPENAI\":\"([^\"]+)\"").find(file.readText())!!.groupValues[1]
        assertNotEquals(first, second)
    }

    @Test
    fun `an encrypted entry moved to another provider cannot be read there`() = runBlocking {
        store().put(ProviderId.OPENAI, secret)
        file.writeText(file.readText().replace("\"OPENAI\"", "\"XAI\""))
        val s = store()
        assertTrue(s.has(ProviderId.XAI))
        assertNull("the ciphertext is bound to its provider", s.key(ProviderId.XAI))
    }

    @Test
    fun `a key that can no longer be decrypted is reported as unreadable rather than as a working key`() = runBlocking {
        store(keyA).put(ProviderId.GEMINI, secret)
        val afterKeystoreReset = store(keyB)
        assertTrue(afterKeystoreReset.has(ProviderId.GEMINI))
        assertNull(afterKeystoreReset.key(ProviderId.GEMINI))
        assertTrue(afterKeystoreReset.isUnreadable(ProviderId.GEMINI))
        assertFalse(store(keyA).isUnreadable(ProviderId.GEMINI))
    }

    @Test
    fun `removing a key deletes it`() = runBlocking {
        val s = store()
        s.put(ProviderId.XAI, secret)
        s.remove(ProviderId.XAI)
        assertFalse(s.has(ProviderId.XAI))
        assertNull(s.key(ProviderId.XAI))
        assertFalse(file.readText().contains("XAI"))
    }

    @Test
    fun `keys for different providers are independent`() = runBlocking {
        val s = store()
        s.put(ProviderId.OPENAI, secret)
        s.put(ProviderId.GEMINI, "AIzaSyEXAMPLE-NOT-A-REAL-KEY-0123456789")
        s.remove(ProviderId.OPENAI)
        assertNull(s.key(ProviderId.OPENAI))
        assertEquals("AIzaSyEXAMPLE-NOT-A-REAL-KEY-0123456789", s.key(ProviderId.GEMINI))
    }

    @Test
    fun `pasted keys are trimmed and obviously wrong input is refused before it is stored`() = runBlocking {
        val s = store()
        s.put(ProviderId.OPENAI, "  $secret\n")
        assertEquals(secret, s.key(ProviderId.OPENAI))

        for (bad in listOf("", "short", "has a space inside-ABCDEFGHIJKLMNOP", "ünïcode-ABCDEFGHIJKLMNOPQ", "x".repeat(401))) {
            try {
                s.put(ProviderId.XAI, bad)
                fail("accepted: $bad")
            } catch (expected: IllegalArgumentException) {
                assertFalse("the message never echoes the key", expected.message.orEmpty().contains(bad.trim().ifEmpty { "\u0000" }))
            }
        }
        assertFalse(s.has(ProviderId.XAI))
    }

    @Test
    fun `the masked form never reveals the middle of a key`() {
        val masked = EncryptedFileCredentialStore.masked(secret)
        assertEquals("sk-…7890", masked)
        assertFalse(masked.contains("ABCDEFGHIJKLMNOP"))
        assertEquals("••••", EncryptedFileCredentialStore.masked("short"))
    }
}
