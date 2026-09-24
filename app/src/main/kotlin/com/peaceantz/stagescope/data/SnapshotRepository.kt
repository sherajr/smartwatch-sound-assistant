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

sealed interface SaveSnapshotResult {
    data class Saved(val snapshot: SpectrumSnapshot) : SaveSnapshotResult
    data object LimitReached : SaveSnapshotResult
}

/** Persists up to [MAX_SNAPSHOTS] named spectrum snapshots as a single JSON file. */
class SnapshotRepository(context: Context) {

    private val file = File(context.filesDir, "snapshots.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _snapshots = MutableStateFlow(loadFromDisk())
    val snapshots: StateFlow<List<SpectrumSnapshot>> = _snapshots.asStateFlow()

    private fun loadFromDisk(): List<SpectrumSnapshot> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<SpectrumSnapshot>>(file.readText()) }.getOrDefault(emptyList())
    }

    private suspend fun persist(list: List<SpectrumSnapshot>) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    suspend fun save(snapshot: SpectrumSnapshot): SaveSnapshotResult {
        val current = _snapshots.value
        if (current.size >= MAX_SNAPSHOTS) return SaveSnapshotResult.LimitReached
        val updated = current + snapshot
        _snapshots.value = updated
        persist(updated)
        return SaveSnapshotResult.Saved(snapshot)
    }

    suspend fun rename(id: String, newName: String) {
        val updated = _snapshots.value.map { if (it.id == id) it.copy(name = newName) else it }
        _snapshots.value = updated
        persist(updated)
    }

    suspend fun delete(id: String) {
        val updated = _snapshots.value.filterNot { it.id == id }
        _snapshots.value = updated
        persist(updated)
    }
}
