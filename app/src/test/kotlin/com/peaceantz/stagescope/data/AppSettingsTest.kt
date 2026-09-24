package com.peaceantz.stagescope.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `settings saved before theming was added still decode, defaulting to Phosphor Green`() {
        // Exactly what an existing install's settings.json looked like before this pass -- no
        // "theme" key at all.
        val legacyJson = """{"demoModeEnabled":false,"dimAppearanceEnabled":true,"ringAutoHoldSeconds":20}"""
        val settings = json.decodeFromString<AppSettings>(legacyJson)
        assertEquals(AppTheme.PHOSPHOR_GREEN, settings.theme)
        assertEquals(true, settings.dimAppearanceEnabled)
        assertEquals(20, settings.ringAutoHoldSeconds)
    }

    @Test
    fun `a theme choice round-trips through serialization`() {
        val settings = AppSettings(theme = AppTheme.NIGHT_RED)
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertEquals(AppTheme.NIGHT_RED, decoded.theme)
    }

    @Test
    fun `unknown future keys are ignored rather than failing to decode`() {
        val futureJson = """{"demoModeEnabled":false,"theme":"ICE_CYAN","someFutureField":123}"""
        val settings = json.decodeFromString<AppSettings>(futureJson)
        assertEquals(AppTheme.ICE_CYAN, settings.theme)
    }
}
