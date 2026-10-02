package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.ai.anthropic.AnthropicAdapter
import com.peaceantz.stagescope.phone.ai.core.KeyCheck
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.ai.core.ProviderAdapter
import com.peaceantz.stagescope.phone.ai.core.ProviderHttp
import com.peaceantz.stagescope.phone.ai.dev.DevAdapter
import com.peaceantz.stagescope.phone.ai.gemini.GeminiAdapter
import com.peaceantz.stagescope.phone.ai.responses.ResponsesAdapter
import com.peaceantz.stagescope.phone.data.KeyStatus
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.security.CredentialStore
import com.peaceantz.stagescope.phone.security.EncryptedFileCredentialStore
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind

/** Official pages where a person creates their own key (verified 2026-10-01). */
object KeyPages {
    fun url(p: ProviderId): String = when (p) {
        ProviderId.OPENAI -> "https://platform.openai.com/api-keys"
        ProviderId.GEMINI -> "https://aistudio.google.com/app/apikey"
        ProviderId.XAI -> "https://console.x.ai/"
        ProviderId.ANTHROPIC -> "https://platform.claude.com/settings/keys"
    }

    fun billingNote(p: ProviderId): String = when (p) {
        ProviderId.OPENAI -> "Billed per use to your OpenAI API account (separate from a ChatGPT subscription)."
        ProviderId.GEMINI -> "Billed per use to your Google AI Studio / Cloud project (paid tier needed for production use)."
        ProviderId.XAI -> "Billed per use to your xAI API account."
        ProviderId.ANTHROPIC -> "Billed per use to your Anthropic API account (separate from a Claude subscription)."
    }
}

/**
 * Per-provider setup: save/remove a key (Keystore-encrypted), validate it with a free model-list call,
 * choose a model, and hand out the real adapter. A missing key yields `null` -- an actionable setup
 * state, never a fake result.
 */
class ProviderService(
    private val data: PhoneData,
    private val credentials: CredentialStore,
    private val http: ProviderHttp,
    private val onChanged: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Test seam only: aims an adapter at a local server. Production leaves this empty, so only the fixed vendor hosts are used. */
    private val baseUrls: Map<ProviderId, String> = emptyMap(),
) {
    fun hasKey(p: ProviderId): Boolean = credentials.has(p)

    fun maskedKey(p: ProviderId): String? = credentials.key(p)?.let(EncryptedFileCredentialStore::masked)

    fun adapter(provider: ProviderId): ProviderAdapter? {
        if (data.settings.value.devMode) return DevAdapter(provider)
        // A key that is stored but can't be decrypted is the same as no key: the caller reports "set up", not a mystery auth error.
        if (!credentials.has(provider) || credentials.isUnreadable(provider)) return null
        return when (provider) {
            ProviderId.OPENAI -> ResponsesAdapter(provider, http, credentials, baseUrls[provider] ?: "https://api.openai.com/", ResponsesAdapter.Flavor.OPENAI)
            ProviderId.XAI -> ResponsesAdapter(provider, http, credentials, baseUrls[provider] ?: "https://api.x.ai/", ResponsesAdapter.Flavor.XAI)
            ProviderId.ANTHROPIC -> baseUrls[provider]?.let { AnthropicAdapter(http, credentials, it) } ?: AnthropicAdapter(http, credentials)
            ProviderId.GEMINI -> baseUrls[provider]?.let { GeminiAdapter(http, credentials, it) } ?: GeminiAdapter(http, credentials)
        }
    }

    suspend fun saveKey(provider: ProviderId, raw: String): Result<Unit> = runCatching {
        credentials.put(provider, raw)
        data.settings.update { it.copy(keyStatus = it.keyStatus + (provider to KeyStatus())) }
        onChanged()
    }

    suspend fun removeKey(provider: ProviderId) {
        credentials.remove(provider)
        data.settings.update { it.copy(keyStatus = it.keyStatus - provider) }
        onChanged()
    }

    /** A free, authenticated read; also fetches which models this key can use, so an unavailable model can be flagged. */
    suspend fun validate(provider: ProviderId): KeyCheck {
        val adapter = adapter(provider) ?: return KeyCheck(false, TurnErrorKind.MISSING_KEY, "No key entered.")
        val check = adapter.validateKey()
        data.settings.update {
            it.copy(
                keyStatus = it.keyStatus + (provider to KeyStatus(
                    validated = check.ok, checkedAtEpochMs = clock(), lastErrorKind = check.kind, lastErrorMessage = check.message,
                    availableModels = check.models.takeIf { m -> m.isNotEmpty() },
                )),
            )
        }
        onChanged()
        return check
    }

    suspend fun select(provider: ProviderId) { data.settings.update { it.copy(selectedProvider = provider) }; onChanged() }

    suspend fun setModel(provider: ProviderId, modelId: String) {
        if (ModelCatalog.find(provider, modelId) == null) return
        data.settings.update { it.copy(models = it.models + (provider to modelId)) }
        onChanged()
    }

    suspend fun setThorough(provider: ProviderId, on: Boolean) { data.settings.update { it.copy(thorough = it.thorough + (provider to on)) }; onChanged() }
    suspend fun setWebSearch(provider: ProviderId, on: Boolean) { data.settings.update { it.copy(webSearch = it.webSearch + (provider to on)) }; onChanged() }

    /** True when the vendor's own list says the selected model isn't available to this key. */
    fun modelLooksUnavailable(provider: ProviderId): Boolean {
        val s = data.settings.value
        val models = s.keyStatus[provider]?.availableModels ?: return false
        val chosen = s.modelFor(provider)
        return models.none { it == chosen || it.endsWith("/$chosen") }
    }
}
