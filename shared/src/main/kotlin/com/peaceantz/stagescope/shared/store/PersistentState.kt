package com.peaceantz.stagescope.shared.store

import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** On-disk wrapper: lets a file declare its own schema version and be migrated on read. */
@Serializable
private data class VersionedFile(val schemaVersion: Int, val data: JsonElement)

/**
 * A single versioned JSON file with **serial execution** and **atomic writes**:
 * - every read-modify-write runs under one [Mutex], so concurrent callers cannot interleave and
 *   lose an update (WearableListenerService callbacks, WorkManager workers and UI all touch these);
 * - a write goes to a temp file, is fsynced, then renamed over the target, so a crash/kill/power
 *   loss leaves either the old or the new file intact, never a truncated one;
 * - a file that no longer decodes is quarantined (renamed `*.corrupt-<n>`) instead of being
 *   overwritten, so a bad build never silently destroys the user's data;
 * - a legacy file with no wrapper is adopted as schema version 0 and rewritten wrapped on the next
 *   write, which is how existing installs keep loading after an upgrade.
 *
 * Deliberately not Room/DataStore (see CLAUDE.md conventions) -- plain JSON files.
 */
class PersistentState<T : Any>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val schemaVersion: Int,
    private val default: () -> T,
    private val json: Json = StageScopeJson,
    /** Upgrades the raw `data` of an older file. Called only when the stored version is older. */
    private val migrate: (fromVersion: Int, data: JsonElement) -> JsonElement = { _, data -> data },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(loadFromDisk())

    /** Latest committed value. Safe to read from any thread. */
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    /** Applies [transform] under the lock, persists the result atomically, then publishes it. */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            persist(next)
            _state.value = next
        }
        next
    }

    /** Like [update] but also returns a value computed from the same snapshot (e.g. "was it new?"). */
    suspend fun <R> updateWithResult(transform: (T) -> Pair<T, R>): R = mutex.withLock {
        val (next, result) = transform(_state.value)
        if (next != _state.value) {
            persist(next)
            _state.value = next
        }
        result
    }

    /** Re-reads the file under the lock. Only needed if something else could have written it. */
    suspend fun reload(): T = mutex.withLock {
        val loaded = loadFromDisk()
        _state.value = loaded
        loaded
    }

    private fun loadFromDisk(): T {
        if (!file.exists()) return default()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return default()
        return try {
            val element = json.parseToJsonElement(text)
            val (version, data) = unwrap(element)
            val migrated = if (version < schemaVersion) migrate(version, data) else data
            json.decodeFromJsonElement(serializer, migrated)
        } catch (e: Exception) {
            quarantine()
            default()
        }
    }

    private fun unwrap(element: JsonElement): Pair<Int, JsonElement> {
        if (element is JsonObject && "schemaVersion" in element && "data" in element) {
            val wrapped = json.decodeFromJsonElement(VersionedFile.serializer(), element)
            return wrapped.schemaVersion to wrapped.data
        }
        return 0 to element
    }

    private fun persist(value: T) {
        val data = json.encodeToJsonElement(serializer, value)
        val text = json.encodeToString(VersionedFile.serializer(), VersionedFile(schemaVersion, data))
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun quarantine() {
        runCatching {
            var n = 0
            var target = File(file.absolutePath + ".corrupt-$n")
            while (target.exists()) {
                n++
                target = File(file.absolutePath + ".corrupt-$n")
            }
            Files.move(file.toPath(), target.toPath())
        }
    }
}
