package com.peaceantz.stagescope.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [SurfaceSummary] as a small JSON file, same pattern as [SettingsRepository] and
 * [SnapshotRepository] -- no DB/DataStore. Read by the Tile and complication provider services,
 * which may run in a fresh process with no capture session at all; this repository never touches
 * the microphone or [SettingsRepository]/[SnapshotRepository] to answer a request.
 */
class SurfaceSummaryRepository(context: Context) {

    private val file = File(context.filesDir, "surface_summary.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _summary = MutableStateFlow(loadFromDisk())
    val summary: StateFlow<SurfaceSummary> = _summary.asStateFlow()

    private fun loadFromDisk(): SurfaceSummary {
        if (!file.exists()) return SurfaceSummary()
        return runCatching { json.decodeFromString<SurfaceSummary>(file.readText()) }.getOrDefault(SurfaceSummary())
    }

    private suspend fun persist(updated: SurfaceSummary) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(updated)) }
    }

    suspend fun setLastReading(reading: LastReadingSummary) {
        val updated = _summary.value.copy(lastReading = reading)
        _summary.value = updated
        persist(updated)
    }

    suspend fun setRingSummary(ring: RingSummaryState?) {
        val updated = _summary.value.copy(ringSummary = ring)
        _summary.value = updated
        persist(updated)
    }
}
