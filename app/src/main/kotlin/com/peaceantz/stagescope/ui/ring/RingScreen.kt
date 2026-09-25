package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.rotation.rotaryOrientation
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle

@Composable
fun RingScreen(
    viewModel: RingViewModel,
    angleDegrees: Float,
    orientationLocked: Boolean,
    onRotaryDelta: (Float) -> Unit,
    onOpenDetails: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isActive by viewModel.isActive.collectAsStateWithLifecycle()
    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    KeepScreenOnEffect(enabled = isActive)
    val palette = LocalStageScopePalette.current

    // Drives whether "Clear unpinned" is enabled -- disabled rather than hidden, so the control's
    // position in the lower action row never shifts as the bank fills/empties.
    val hasUnpinned = (state as? RingUiState.Measuring)
        ?.measurement?.snapshot?.history?.any { !it.pinned } == true

    ModePageScaffold(
        modeTitle = "RING",
        onOpenDetails = onOpenDetails,
        modifier = Modifier
            .graphicsLayer { rotationZ = angleDegrees }
            .rotaryOrientation(locked = orientationLocked, onRotaryDelta = onRotaryDelta),
        lowerActions = {
            if (isActive) {
                CompactGlyphButton(glyph = "■", contentDescription = "Stop measuring", onClick = viewModel::stop)
            } else {
                CompactGlyphButton(glyph = "▶", contentDescription = "Start measuring", onClick = requestStart)
            }
            CompactGlyphButton(
                glyph = "✕",
                contentDescription = "Clear unpinned measurements",
                onClick = viewModel::clearUnpinned,
                enabled = hasUnpinned,
            )
        },
    ) {
        when (val s = state) {
            RingUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", palette.Held)
            is RingUiState.Unavailable -> StateBadge("⚠", "Unavailable", palette.Held)
            is RingUiState.Error -> StateBadge("⚠", "Error", palette.Held)
            is RingUiState.Measuring -> RingGridContent(
                measurement = s.measurement,
                autoHoldMs = viewModel.autoHoldSeconds() * 1000L,
                onTogglePin = { capture -> if (capture.pinned) viewModel.unpin(capture.id) else viewModel.pin(capture.id) },
            )
        }
    }
}

@Composable
private fun RingGridContent(
    measurement: RingMeasurement,
    autoHoldMs: Long,
    onTogglePin: (RingCapture) -> Unit,
) {
    val snap = measurement.snapshot
    val palette = LocalStageScopePalette.current

    // Sized from the FULL available content area (grid + any extra status lines below it), not
    // just the grid's own intrinsic size -- otherwise the bottom row of tiles (and/or the status
    // lines) can silently overflow past the page's content bounds on a round display. The
    // "all pinned" message is long enough to wrap to two lines at this width, so it gets a larger
    // reservation than the single-line demo tag.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val reservedForExtras = (if (snap.allSlotsPinned) 34.dp else 0.dp) + (if (measurement.isDemo) 16.dp else 0.dp)
        val gridHeightBudget = (maxHeight - reservedForExtras).coerceAtLeast(0.dp)
        val rowSpacing = 5.dp
        val tileSize = minOf(maxWidth / 2.7f, (gridHeightBudget - rowSpacing * 2) / 3f).coerceIn(30.dp, 60.dp)

        Column(
            // The Box spans the page, but a wrap-content Column would be placed at its left edge
            // whenever neither status line is present. Give the grid the same center in all states.
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RingTilesGrid(
                slotCaptureIds = measurement.slotCaptureIds,
                captures = snap.history,
                mostProminentCaptureId = snap.mostProminentCaptureId,
                autoHoldMs = autoHoldMs,
                tileSize = tileSize,
                rowSpacing = rowSpacing,
                onTogglePin = onTogglePin,
            )
            if (snap.allSlotsPinned) {
                Text(
                    text = "All 5 pinned — unpin or clear one to capture another",
                    style = chartAnnotationStyle(),
                    color = palette.Held,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (measurement.isDemo) {
                Text(
                    "DEMO — synthetic signal",
                    style = chartAnnotationStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
