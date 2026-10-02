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
    fun `the Ask AI shortcut opens the Assistant page and can never start a measurement or a microphone`() {
        val request = ShortcutRequest.forShortcut(SHORTCUT_ASK_AI, ringCaptureId = null, requestId = 1L)
        assertEquals(ModePage.ASSISTANT, request!!.page)
        assertEquals("opening the page is all it does", false, request.startMeasure)
        assertNull(request.ringCaptureId)
    }

    @Test
    fun `the pager order is Analyzer then Ring then Assistant`() {
        assertEquals(listOf(0, 1, 2), listOf(ModePage.ANALYZER, ModePage.RING, ModePage.ASSISTANT))
        assertEquals(3, ModePage.COUNT)
    }

    @Test
    fun `no existing shortcut string changed meaning`() {
        assertEquals("measure", SHORTCUT_MEASURE)
        assertEquals("level", SHORTCUT_OPEN_LEVEL)
        assertEquals("spectrum", SHORTCUT_OPEN_SPECTRUM)
        assertEquals("ring", SHORTCUT_OPEN_RING)
    }

    @Test
    fun `unknown shortcut string produces no navigation request`() {
        assertNull(ShortcutRequest.forShortcut("not-a-real-shortcut", ringCaptureId = null, requestId = 1L))
        assertNull(ShortcutRequest.forShortcut(null, ringCaptureId = null, requestId = 1L))
    }
}
