package com.peaceantz.stagescope.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One pinned Ring capture's metadata, persisted across restarts -- frequency only, never audio. */
@Serializable
data class PersistedRingCapture(
    val id: Long,
    val frequencyHz: Double,
    val savedAtMillis: Long,
)

@Serializable
data class RingBank(val pinned: List<PersistedRingCapture> = emptyList())

/**
 * Persists only PINNED Ring captures (id/frequency/save-time, up to the five-slot bank) as a small
 * plain-JSON file -- same pattern as [SettingsRepository]/[SurfaceSummaryRepository], no DB. Read
 * once at startup and replayed into [com.peaceantz.stagescope.dsp.RingTracker.restoreCapture] so a
 * pin survives an app restart without ever storing audio. Unpinned captures are session-only by
 * design; every write here is a full replace of the pinned set (never partial), so a clear/unpin
 * that drops below five entries is reflected exactly, not merged with stale rows.
 */
class RingBankRepository(context: Context) {

    private val file = File(context.filesDir, "ring_bank.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _bank = MutableStateFlow(loadFromDisk())
    val bank: StateFlow<RingBank> = _bank.asStateFlow()

    private fun loadFromDisk(): RingBank {
        if (!file.exists()) return RingBank()
        return runCatching { json.decodeFromString<RingBank>(file.readText()) }.getOrDefault(RingBank())
    }

    suspend fun savePinned(pinned: List<PersistedRingCapture>) = withContext(Dispatchers.IO) {
        val updated = RingBank(pinned)
        _bank.value = updated
        runCatching { file.writeText(json.encodeToString(updated)) }
    }
}
