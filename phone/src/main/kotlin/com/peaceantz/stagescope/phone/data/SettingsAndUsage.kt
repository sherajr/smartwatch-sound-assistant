package com.peaceantz.stagescope.phone.data

import com.peaceantz.stagescope.phone.ai.core.Effort
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnErrorKind
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import com.peaceantz.stagescope.shared.store.PersistentState
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class KeyStatus(
    val validated: Boolean = false,
    val checkedAtEpochMs: Long? = null,
    val lastErrorKind: TurnErrorKind? = null,
    val lastErrorMessage: String? = null,
    /** Model ids this key can use, from the vendor's free list call (null = never fetched). */
    val availableModels: List<String>? = null,
)

/** Which provider (if any) transcribes watch recordings in the cloud. Off by default: it costs money. */
@Serializable
enum class SpeechProviderChoice { NONE, OPENAI }

@Serializable
data class UsageLimits(
    val maxRequestsPerDay: Int = 150,
    /** Optional soft budget for this app's own recorded usage (an estimate, not the provider's invoice). */
    val monthlyBudgetUsd: Double? = null,
    val warnAtPercent: Int = 80,
    val maxOutputTokens: Int = 6_000,
    /** Bound on the conversation text sent as context (characters). */
    val maxContextChars: Int = 40_000,
    val maxToolLoops: Int = 6,
)

@Serializable
data class PhoneSettings(
    val phoneInstallId: String = UUID.randomUUID().toString(),
    val selectedProvider: ProviderId = ProviderId.OPENAI,
    val models: Map<ProviderId, String> = emptyMap(),
    val thorough: Map<ProviderId, Boolean> = emptyMap(),
    /** Web search/grounding is opt-in per provider: it costs extra and is only claimed when it ran. */
    val webSearch: Map<ProviderId, Boolean> = emptyMap(),
    val keyStatus: Map<ProviderId, KeyStatus> = emptyMap(),
    val limits: UsageLimits = UsageLimits(),
    val speechProvider: SpeechProviderChoice = SpeechProviderChoice.NONE,
    /** Development/test mode may use the fake provider; it can never execute Gmail/Calendar actions. */
    val devMode: Boolean = false,
    val watchInstallId: String? = null,
    val watchAppVersion: String? = null,
    val lastWatchContactEpochMs: Long? = null,
    val negotiatedProtocolVersion: Int? = null,
    val googleAccountEmail: String? = null,
    val googleAccountId: String? = null,
    val gmailGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val calendarListGranted: Boolean = false,
    val defaultCalendarId: String = "primary",
    val defaultCalendarLabel: String = "Primary calendar",
) {
    fun modelFor(provider: ProviderId): String = models[provider]?.takeIf { it.isNotBlank() } ?: ModelCatalog.defaultFor(provider).id
    fun effortFor(provider: ProviderId): Effort = if (thorough[provider] == true) Effort.THOROUGH else Effort.BALANCED
    fun webSearchFor(provider: ProviderId): Boolean = webSearch[provider] == true
}

class PhoneSettingsRepository(dir: File) {
    private val store = PersistentState(File(dir, "settings.json"), PhoneSettings.serializer(), 1, { PhoneSettings() })
    val state get() = store.state
    val value: PhoneSettings get() = store.value
    suspend fun update(transform: (PhoneSettings) -> PhoneSettings) = store.update(transform)
}

// ---------------------------------------------------------------------------------------- usage

@Serializable
data class UsageEntry(
    val requestId: String,
    val atEpochMs: Long,
    val providerId: ProviderId,
    val modelId: String,
    val usage: UsageSummary,
    val kind: String = "chat",
    val succeeded: Boolean = true,
)

@Serializable
data class UsageState(val entries: List<UsageEntry> = emptyList())

class UsageRepository(dir: File) {
    private val store = PersistentState(File(dir, "usage.json"), UsageState.serializer(), 1, { UsageState() })
    val state get() = store.state

    suspend fun record(entry: UsageEntry) = store.update { s -> UsageState((s.entries + entry).takeLast(MAX_ENTRIES)) }

    companion object {
        const val MAX_ENTRIES = 3_000
    }
}

sealed interface UsageVerdict {
    data object Ok : UsageVerdict
    /** Allowed, but worth saying: approaching the soft budget. */
    data class Warn(val message: String) : UsageVerdict
    /** Not sent: a limit set in this app was reached. */
    data class Blocked(val message: String) : UsageVerdict
}

/**
 * Per-app usage controls. These are limits on what *StageScope itself* records and sends -- they are
 * not, and are never described as, a billing cap at the provider: a request already in flight, or
 * usage from other apps on the same account, can exceed a locally recorded threshold. Costs shown are
 * the app's estimates (dated price table) or a provider-reported cost, never an invoice.
 */
object UsageGuard {
    fun check(limits: UsageLimits, entries: List<UsageEntry>, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): UsageVerdict {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val todaysRequests = entries.count { it.kind == "chat" && day(it.atEpochMs, zone) == today }
        if (todaysRequests >= limits.maxRequestsPerDay) {
            return UsageVerdict.Blocked("Daily limit of ${limits.maxRequestsPerDay} AI requests reached. Raise it in Usage settings to continue.")
        }
        val budget = limits.monthlyBudgetUsd
        if (budget != null && budget > 0) {
            val spent = monthSpendUsd(entries, nowEpochMs, zone)
            if (spent >= budget) {
                return UsageVerdict.Blocked("Your monthly budget of \$${"%.2f".format(budget)} (as recorded by StageScope) is used up. Raise it in Usage settings to continue.")
            }
            if (spent >= budget * limits.warnAtPercent / 100.0) {
                return UsageVerdict.Warn("About \$${"%.2f".format(spent)} of your \$${"%.2f".format(budget)} monthly budget used (StageScope's estimate, not the provider's invoice).")
            }
        }
        return UsageVerdict.Ok
    }

    /** Provider-reported cost wins; otherwise the dated estimate; unknown models add nothing (and show as unknown). */
    fun monthSpendUsd(entries: List<UsageEntry>, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        return entries.filter { val d = day(it.atEpochMs, zone); d.year == now.year && d.month == now.month }
            .sumOf { (it.usage.reportedCostMicros ?: it.usage.estimatedCostMicros ?: 0L) / 1_000_000.0 }
    }

    private fun day(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
