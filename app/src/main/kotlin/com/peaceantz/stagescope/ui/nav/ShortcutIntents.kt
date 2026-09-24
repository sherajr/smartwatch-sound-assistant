package com.peaceantz.stagescope.ui.nav

import android.content.Intent

/**
 * The intent contract Tile taps, complication taps, and any future shortcut share to reach
 * [com.peaceantz.stagescope.MainActivity]. A String extra (not a distinct Intent action) is used
 * because a Tile's declarative `ActionBuilders.launchAction` can only carry package/class/extras,
 * not a custom action string -- so both surfaces route through the same key for one parsing path.
 */
const val EXTRA_SHORTCUT = "com.peaceantz.stagescope.extra.SHORTCUT"
const val EXTRA_RING_CAPTURE_ID = "com.peaceantz.stagescope.extra.RING_CAPTURE_ID"

const val SHORTCUT_MEASURE = "measure"
const val SHORTCUT_OPEN_LEVEL = "level"
const val SHORTCUT_OPEN_SPECTRUM = "spectrum"
const val SHORTCUT_OPEN_RING = "ring"

/**
 * One external launch request (Tile tap, complication tap) waiting to be applied to the shared
 * pager/session. [requestId] gives each delivery a distinct identity so a `LaunchedEffect` keyed
 * on it fires exactly once per genuine tap -- see [com.peaceantz.stagescope.MainActivity] for how
 * cold launch vs. recreation vs. a fresh `onNewIntent` are told apart.
 */
data class ShortcutRequest(
    val requestId: Long,
    val page: Int,
    val startMeasure: Boolean = false,
    val ringCaptureId: Long? = null,
) {
    companion object {
        fun fromIntent(intent: Intent?): ShortcutRequest? {
            val shortcut = intent?.getStringExtra(EXTRA_SHORTCUT) ?: return null
            val ringCaptureId = intent.getLongExtra(EXTRA_RING_CAPTURE_ID, -1L).takeIf { it >= 0 }
            return forShortcut(shortcut, ringCaptureId, requestId = System.nanoTime())
        }

        /**
         * Pure routing logic, separated from [fromIntent]'s `android.content.Intent` parsing so it
         * is unit-testable without an Android framework/Robolectric dependency. LEVEL and SPECTRUM
         * shortcuts both predate the combined Analyzer page -- existing Tile/complication
         * `PendingIntent`s (and this constant contract) still send those shortcut strings, so both
         * keep working by routing to [ModePage.ANALYZER] rather than requiring every surface to be
         * rebuilt in lockstep with this redesign.
         */
        fun forShortcut(shortcut: String?, ringCaptureId: Long?, requestId: Long): ShortcutRequest? = when (shortcut) {
            SHORTCUT_MEASURE -> ShortcutRequest(requestId, ModePage.ANALYZER, startMeasure = true)
            SHORTCUT_OPEN_LEVEL -> ShortcutRequest(requestId, ModePage.ANALYZER)
            SHORTCUT_OPEN_SPECTRUM -> ShortcutRequest(requestId, ModePage.ANALYZER)
            SHORTCUT_OPEN_RING -> ShortcutRequest(requestId = requestId, page = ModePage.RING, ringCaptureId = ringCaptureId)
            else -> null
        }
    }
}
