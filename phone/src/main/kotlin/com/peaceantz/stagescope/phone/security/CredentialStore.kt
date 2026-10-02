package com.peaceantz.stagescope.phone.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.peaceantz.stagescope.phone.ai.core.KeySource
import com.peaceantz.stagescope.phone.ai.core.Redactor
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.store.PersistentState
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider API keys: encrypted with a non-exportable AES-256-GCM key held in the Android Keystore,
 * stored in the app's private no-backup directory. A key is never bundled in an APK, never logged,
 * never sent to the watch, never given to a model, and (with `allowBackup=false` plus data-extraction
 * rules) never included in a backup. This is a personal bring-your-own-key client: nothing here is a
 * shared developer key.
 */
interface CredentialStore : KeySource {
    fun has(provider: ProviderId): Boolean
    suspend fun put(provider: ProviderId, key: String)
    suspend fun remove(provider: ProviderId)

    /** True when a key is stored but can no longer be decrypted (e.g. the Keystore key was invalidated): re-enter it. */
    fun isUnreadable(provider: ProviderId): Boolean = has(provider) && key(provider) == null
}

@Serializable
private data class CredentialFile(val entries: Map<String, String> = emptyMap())

/** The Keystore is injected so the encryption logic itself is unit-testable with a software key. */
class EncryptedFileCredentialStore(
    file: File,
    private val keyProvider: () -> SecretKey,
) : CredentialStore {

    private val state = PersistentState(file, CredentialFile.serializer(), 1, { CredentialFile() })

    override fun key(provider: ProviderId): String? {
        val blob = state.value.entries[provider.name] ?: return null
        return runCatching { decrypt(provider, blob) }.getOrNull()
    }

    override fun has(provider: ProviderId): Boolean = provider.name in state.value.entries

    override suspend fun put(provider: ProviderId, key: String) {
        val clean = normalize(key)
        val blob = encrypt(provider, clean)
        state.update { CredentialFile(it.entries + (provider.name to blob)) }
    }

    override suspend fun remove(provider: ProviderId) {
        state.update { CredentialFile(it.entries - provider.name) }
    }

    private fun encrypt(provider: ProviderId, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Do NOT supply an IV: an Android Keystore key created with randomized encryption required
        // (the secure default) rejects a caller-provided one. The cipher picks a fresh random IV and we
        // read it back; a software key behaves the same way, so tests exercise this exact path.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        // Bind the ciphertext to its provider so entries cannot be swapped between providers.
        cipher.updateAAD(provider.name.toByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(provider: ProviderId, blob: String): String {
        val raw = Base64.getDecoder().decode(blob)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(provider.name.toByteArray())
        return String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
    }

    companion object {
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Pasted keys often carry a trailing newline/space; an inner space or non-ASCII char means it's not a key. */
        fun normalize(raw: String): String {
            val k = raw.trim()
            require(k.length in 16..400) { "That doesn't look like an API key (wrong length)." }
            require(k.all { it.code in 33..126 }) { "That doesn't look like an API key (it contains spaces or unusual characters)." }
            return k
        }

        fun masked(key: String): String = Redactor.mask(key)
    }
}

/** The real Keystore-backed key. Created once; the raw key material never leaves the secure hardware/TEE. */
object AndroidKeystoreKey {
    private const val ALIAS = "stagescope.credentials.v1"

    fun getOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
