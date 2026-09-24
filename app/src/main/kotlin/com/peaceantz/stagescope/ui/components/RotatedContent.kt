package com.peaceantz.stagescope.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Visually rotates a whole screen's content around the box center by the shared crown-driven
 * orientation angle -- used for every secondary screen reached from the main pager (Details,
 * Calibration, Appearance, Snapshots, Ring Captures/Details) so the display angle stays consistent
 * across navigation, per `OrientationViewModel`.
 *
 * Purely a render transform: Compose already re-expresses touch and focus input in this box's
 * LOCAL (rotated) coordinate space for its children, so nothing here needs its own gesture handling
 * or a second inverse transform, and a child's own rotary/scroll wiring (e.g. a ScalingLazyColumn's
 * crown-scroll) is untouched since rotary events aren't positional. Deliberately applied INSIDE each
 * nav destination's own content -- never around the pager or the nav host itself -- so the pager's
 * swipe-to-page and the nav host's swipe-to-dismiss gestures stay in true screen space.
 */
@Composable
fun RotatedContent(angleDegrees: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().graphicsLayer { rotationZ = angleDegrees }) {
        content()
    }
}
