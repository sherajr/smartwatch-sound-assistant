package com.peaceantz.stagescope.phone.ai.core

import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import java.time.LocalDate

/**
 * USD per million tokens. A price may change on a known date ([nextFrom]/[next...]) -- Gemini 3.8
 * Flash is cheaper through 2026-12-31, for example -- so estimates use the price in force on the
 * day of the request, never a stale single number.
 */
data class PricePoint(
    val inputPerMTok: Double,
    val cachedInputPerMTok: Double?,
    val outputPerMTok: Double,
)

data class ModelInfo(
    val provider: ProviderId,
    val id: String,
    val displayName: String,
    val blurb: String,
    val contextTokens: Long,
    val maxOutputTokens: Long,
    val price: PricePoint,
    /** A scheduled price change: from [changesOn] onward [priceAfter] applies. */
    val changesOn: LocalDate? = null,
    val priceAfter: PricePoint? = null,
    val supportsWebSearch: Boolean,
    /** What a search costs beyond tokens, in words, for the usage screen. */
    val webSearchNote: String? = null,
    val supportsTools: Boolean = true,
    /** The recommended starting model for this provider. */
    val isDefault: Boolean = false,
    /** Cheaper/faster choice for the "fast" preference where the provider offers one. */
    val tier: Tier = Tier.BALANCED,
    /** Provider-native effort/thinking value for each [Effort]; null when the model takes no such setting. */
    val efforts: Map<Effort, String>? = null,
    val priceSource: String,
) {
    enum class Tier { FAST, BALANCED, THOROUGH }

    fun priceOn(day: LocalDate): PricePoint =
        if (changesOn != null && priceAfter != null && !day.isBefore(changesOn)) priceAfter else price
}

/**
 * The single maintainable catalog of model ids, capabilities and price metadata. Everything here
 * was verified against each vendor's official documentation on [VERIFIED_ON]; the strings in
 * [ModelInfo.priceSource] say where. A model that is in this list but rejected by the vendor for a
 * given key surfaces as "model unavailable -- pick another" and is **never** silently replaced.
 * Ids and prices change: update this file and [VERIFIED_ON] together.
 */
object ModelCatalog {
    const val VERIFIED_ON = "2026-10-01"

    private val LOW_MED_HIGH = mapOf(Effort.FAST to "low", Effort.BALANCED to "medium", Effort.THOROUGH to "high")
    private val ANTHROPIC_EFFORT = mapOf(Effort.FAST to "low", Effort.BALANCED to "medium", Effort.THOROUGH to "xhigh")

    private const val OPENAI_SRC = "developers.openai.com/api/docs/pricing (checked 2026-10-01)"
    private const val GEMINI_SRC = "ai.google.dev/gemini-api/docs/pricing (checked 2026-10-01)"
    private const val XAI_SRC = "docs.x.ai/developers/models (checked 2026-10-01)"
    private const val ANTHROPIC_SRC = "platform.claude.com pricing via the Claude API model table (checked 2026-09-25)"

    val ALL: List<ModelInfo> = listOf(
        // --- OpenAI (Responses API) ---
        ModelInfo(
            ProviderId.OPENAI, "gpt-6.1-sol", "GPT-6.1 Sol", "Near-flagship quality at a lower cost; the everyday default.",
            1_050_000, 128_000, PricePoint(2.00, 0.10, 10.00),
            supportsWebSearch = true, webSearchNote = "Web search tool calls are billed per call in addition to tokens.",
            isDefault = true, tier = ModelInfo.Tier.BALANCED, efforts = LOW_MED_HIGH, priceSource = OPENAI_SRC,
        ),
        ModelInfo(
            ProviderId.OPENAI, "gpt-6-astra", "GPT-6 Astra", "Most capable; slower and about 5x the price of Sol.",
            1_050_000, 128_000, PricePoint(10.00, 1.00, 50.00),
            supportsWebSearch = true, webSearchNote = "Web search tool calls are billed per call in addition to tokens.",
            tier = ModelInfo.Tier.THOROUGH, efforts = LOW_MED_HIGH, priceSource = OPENAI_SRC,
        ),
        ModelInfo(
            ProviderId.OPENAI, "gpt-6-luna", "GPT-6 Luna", "Most efficient; good for quick, simple replies.",
            1_050_000, 128_000, PricePoint(0.10, 0.01, 0.50),
            supportsWebSearch = true, webSearchNote = "Web search tool calls are billed per call in addition to tokens.",
            tier = ModelInfo.Tier.FAST, efforts = LOW_MED_HIGH, priceSource = OPENAI_SRC,
        ),
        // --- Google Gemini (Interactions API) ---
        ModelInfo(
            ProviderId.GEMINI, "gemini-3.8-flash", "Gemini 3.8 Flash", "Google's most intelligent Flash model; the default.",
            1_048_576, 65_536, PricePoint(0.75, 0.075, 3.75),
            changesOn = LocalDate.of(2027, 1, 1), priceAfter = PricePoint(1.50, 0.15, 7.50),
            supportsWebSearch = true, webSearchNote = "Grounding: 5,000 free searches/month shared across Gemini 3 models, then \$14 per 1,000.",
            isDefault = true, tier = ModelInfo.Tier.BALANCED, efforts = LOW_MED_HIGH, priceSource = GEMINI_SRC,
        ),
        ModelInfo(
            ProviderId.GEMINI, "gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", "Higher reasoning quality; a preview model.",
            1_048_576, 65_536, PricePoint(2.00, 0.20, 12.00),
            supportsWebSearch = true, webSearchNote = "Grounding: 5,000 free searches/month shared across Gemini 3 models, then \$14 per 1,000.",
            tier = ModelInfo.Tier.THOROUGH, efforts = LOW_MED_HIGH, priceSource = GEMINI_SRC,
        ),
        ModelInfo(
            ProviderId.GEMINI, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Cost-efficient for short, simple replies.",
            1_048_576, 65_536, PricePoint(0.30, 0.03, 2.50),
            supportsWebSearch = true, webSearchNote = "Grounding: 5,000 free searches/month shared across Gemini 3 models, then \$14 per 1,000.",
            tier = ModelInfo.Tier.FAST, efforts = LOW_MED_HIGH, priceSource = GEMINI_SRC,
        ),
        // --- xAI Grok (Responses API) ---
        ModelInfo(
            ProviderId.XAI, "grok-4.7", "Grok 4.7", "xAI's most capable model; the default.",
            500_000, 128_000, PricePoint(2.00, 0.50, 6.00),
            supportsWebSearch = true, webSearchNote = "Server-side web/X search is billed per tool call by xAI; the response reports the exact cost.",
            isDefault = true, tier = ModelInfo.Tier.BALANCED, efforts = LOW_MED_HIGH, priceSource = XAI_SRC,
        ),
        ModelInfo(
            ProviderId.XAI, "grok-4.3", "Grok 4.3", "Lower-cost Grok with a long context.",
            1_000_000, 128_000, PricePoint(1.25, 0.20, 2.50),
            supportsWebSearch = true, webSearchNote = "Server-side web/X search is billed per tool call by xAI; the response reports the exact cost.",
            tier = ModelInfo.Tier.FAST, priceSource = XAI_SRC,
        ),
        // --- Anthropic Claude (Messages API) ---
        ModelInfo(
            ProviderId.ANTHROPIC, "claude-opus-5-5", "Claude Opus 5.5", "Anthropic's current Opus; strong reasoning at \$4 / \$20.",
            1_000_000, 128_000, PricePoint(4.00, 0.20, 20.00),
            supportsWebSearch = true, webSearchNote = "Web search is billed per search in addition to tokens.",
            isDefault = true, tier = ModelInfo.Tier.BALANCED, efforts = ANTHROPIC_EFFORT, priceSource = ANTHROPIC_SRC,
        ),
        ModelInfo(
            ProviderId.ANTHROPIC, "claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable for everyday work at \$2 / \$10.",
            1_000_000, 128_000, PricePoint(2.00, 0.20, 10.00),
            supportsWebSearch = true, webSearchNote = "Web search is billed per search in addition to tokens.",
            tier = ModelInfo.Tier.FAST, efforts = ANTHROPIC_EFFORT, priceSource = ANTHROPIC_SRC,
        ),
        ModelInfo(
            ProviderId.ANTHROPIC, "claude-fable-5-1", "Claude Fable 5.1", "Most capable Claude; slower and \$10 / \$50.",
            1_000_000, 128_000, PricePoint(10.00, null, 50.00),
            supportsWebSearch = true, webSearchNote = "Web search is billed per search in addition to tokens.",
            tier = ModelInfo.Tier.THOROUGH, efforts = ANTHROPIC_EFFORT, priceSource = ANTHROPIC_SRC,
        ),
        ModelInfo(
            ProviderId.ANTHROPIC, "claude-haiku-4-5", "Claude Haiku 4.5", "Smallest and cheapest Claude (older generation).",
            200_000, 64_000, PricePoint(1.00, null, 5.00),
            supportsWebSearch = true, webSearchNote = "Web search is billed per search in addition to tokens.",
            tier = ModelInfo.Tier.FAST, efforts = null, priceSource = ANTHROPIC_SRC,
        ),
    )

    fun forProvider(provider: ProviderId): List<ModelInfo> = ALL.filter { it.provider == provider }

    fun find(provider: ProviderId, modelId: String): ModelInfo? = ALL.firstOrNull { it.provider == provider && it.id == modelId }

    fun defaultFor(provider: ProviderId): ModelInfo = forProvider(provider).first { it.isDefault }

    /**
     * Estimated cost in micro-USD from a dated price table, or null when the model isn't in the
     * catalog (an unknown model shows "unknown", never an invented price). Provider-reported cost, when
     * the API supplies one, is kept separately and wins in the UI.
     */
    fun estimateMicros(provider: ProviderId, modelId: String, usage: UsageSummary, day: LocalDate = LocalDate.now()): Long? {
        val info = find(provider, modelId) ?: return null
        val p = info.priceOn(day)
        val freshInput = (usage.inputTokens - usage.cachedInputTokens).coerceAtLeast(0)
        val inputUsd = freshInput * p.inputPerMTok / 1_000_000.0
        val cachedUsd = usage.cachedInputTokens * (p.cachedInputPerMTok ?: p.inputPerMTok) / 1_000_000.0
        // Reasoning tokens are billed as output on every provider here.
        val outputUsd = (usage.outputTokens + usage.reasoningTokens.takeIf { !reasoningIncludedInOutput(provider) }.orZero()) * p.outputPerMTok / 1_000_000.0
        return Math.round((inputUsd + cachedUsd + outputUsd) * 1_000_000.0)
    }

    /** OpenAI/xAI report `output_tokens` *including* reasoning; Gemini reports thought tokens separately. */
    private fun reasoningIncludedInOutput(provider: ProviderId) = provider != ProviderId.GEMINI

    private fun Long?.orZero() = this ?: 0L
}
