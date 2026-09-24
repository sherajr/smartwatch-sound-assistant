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
            val requestId = System.nanoTime()
            return when (shortcut) {
                SHORTCUT_MEASURE -> ShortcutRequest(requestId, ModePage.LEVEL, startMeasure = true)
                SHORTCUT_OPEN_LEVEL -> ShortcutRequest(requestId, ModePage.LEVEL)
                SHORTCUT_OPEN_SPECTRUM -> ShortcutRequest(requestId, ModePage.SPECTRUM)
                SHORTCUT_OPEN_RING -> ShortcutRequest(
                    requestId = requestId,
                    page = ModePage.RING,
                    ringCaptureId = intent.getLongExtra(EXTRA_RING_CAPTURE_ID, -1L).takeIf { it >= 0 },
                )
                else -> null
            }
        }
    }
}
