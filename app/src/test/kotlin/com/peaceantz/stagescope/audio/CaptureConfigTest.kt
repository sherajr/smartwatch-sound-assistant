package com.peaceantz.stagescope.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaptureConfigTest {

    private fun config(sampleRate: Int, source: Int) = CaptureConfig(
        sampleRate = sampleRate,
        audioSource = source,
        sourceLabel = "test",
        isUnprocessedSource = false,
        effects = emptyList(),
    )

    @Test
    fun `fingerprint is stable for identical configuration`() {
        assertEquals(config(48000, 9).fingerprint(), config(48000, 9).fingerprint())
    }

    @Test
    fun `fingerprint changes when sample rate changes`() {
        assertNotEquals(config(48000, 9).fingerprint(), config(44100, 9).fingerprint())
    }

    @Test
    fun `fingerprint changes when audio source changes`() {
        assertNotEquals(config(48000, 9).fingerprint(), config(48000, 6).fingerprint())
    }
}
