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
 * Persists [AppSettings] (demo mode flag, calibration offset) as a small JSON file. No database,
 * no DataStore dependency -- a handful of scalar fields does not need either.
 */
class SettingsRepository(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _settings = MutableStateFlow(loadFromDisk())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadFromDisk(): AppSettings {
        if (!file.exists()) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())
    }

    private suspend fun persist(newSettings: AppSettings) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(newSettings)) }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        val updated = _settings.value.copy(demoModeEnabled = enabled)
        _settings.value = updated
        persist(updated)
    }

    suspend fun setDimAppearance(enabled: Boolean) {
        val updated = _settings.value.copy(dimAppearanceEnabled = enabled)
        _settings.value = updated
        persist(updated)
    }

    suspend fun setRingAutoHoldSeconds(seconds: Int) {
        val updated = _settings.value.copy(ringAutoHoldSeconds = seconds)
        _settings.value = updated
        persist(updated)
    }

    suspend fun setTheme(theme: AppTheme) {
        val updated = _settings.value.copy(theme = theme)
        _settings.value = updated
        persist(updated)
    }

    suspend fun setCalibration(calibration: CalibrationState?) {
        val updated = _settings.value.copy(calibration = calibration)
        _settings.value = updated
        persist(updated)
    }

    /** Clears the stored calibration if it was measured against a different input configuration. */
    suspend fun invalidateCalibrationIfMismatched(currentFingerprint: String) {
        val current = _settings.value.calibration ?: return
        if (current.configFingerprint != currentFingerprint) {
            setCalibration(null)
        }
    }
}
