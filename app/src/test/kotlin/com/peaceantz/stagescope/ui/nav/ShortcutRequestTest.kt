package com.peaceantz.stagescope.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRequestTest {

    @Test
    fun `legacy LEVEL shortcut routes to the combined Analyzer page`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_OPEN_LEVEL, ringCaptureId = null, requestId = 1L)
        assertEquals(ModePage.ANALYZER, request!!.page)
        assertEquals(false, request.startMeasure)
    }

    @Test
    fun `legacy SPECTRUM shortcut also routes to the combined Analyzer page`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_OPEN_SPECTRUM, ringCaptureId = null, requestId = 1L)
        assertEquals(ModePage.ANALYZER, request!!.page)
    }

    @Test
    fun `measure shortcut opens Analyzer and requests a measurement start`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_MEASURE, ringCaptureId = null, requestId = 1L)
        assertEquals(ModePage.ANALYZER, request!!.page)
        assertTrue(request.startMeasure)
    }

    @Test
    fun `ring shortcut still routes to RING and carries the capture id through`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_OPEN_RING, ringCaptureId = 42L, requestId = 1L)
        assertEquals(ModePage.RING, request!!.page)
        assertEquals(42L, request.ringCaptureId)
    }

    @Test
    fun `ring shortcut without a capture id is still valid navigation`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_OPEN_RING, ringCaptureId = null, requestId = 1L)
        assertEquals(ModePage.RING, request!!.page)
        assertNull(request.ringCaptureId)
    }

    @Test
    fun `unknown shortcut string produces no navigation request`() {
        assertNull(ShortcutRequest.forShortcut("not-a-real-shortcut", ringCaptureId = null, requestId = 1L))
        assertNull(ShortcutRequest.forShortcut(null, ringCaptureId = null, requestId = 1L))
    }
}
