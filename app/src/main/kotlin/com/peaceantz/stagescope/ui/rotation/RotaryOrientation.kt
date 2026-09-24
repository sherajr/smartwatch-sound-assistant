package com.peaceantz.stagescope.ui.rotation

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive

/**
 * Receives crown events on the active instrument page, including after a page swipe or returning
 * from Details. Wear's pager/navigation hierarchy owns focus; a one-shot requestFocus in a
 * LaunchedEffect can be overridden by that hierarchy or by a retained off-screen page.
 *
 * The event handler MUST precede focusable: rotary events travel through the focused target's
 * ancestors, so a handler after that target never receives its events. Keep the handler above
 * child buttons too, so interacting with a tile does not disable crown rotation.
 */
fun Modifier.rotaryOrientation(
    locked: Boolean,
    onRotaryDelta: (Float) -> Unit,
): Modifier = this
    .onRotaryScrollEvent { event ->
        if (!locked) onRotaryDelta(event.verticalScrollPixels)
        // A locked instrument still owns the crown; don't let the event page/scroll an ancestor.
        true
    }
    .requestFocusOnHierarchyActive()
    .focusable()
