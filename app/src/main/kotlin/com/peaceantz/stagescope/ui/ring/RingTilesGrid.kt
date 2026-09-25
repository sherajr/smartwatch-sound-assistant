package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.dsp.SystemMonotonicClock
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.widget.formatFrequencyCompact
import com.peaceantz.stagescope.widget.formatFrequencyReadable

/**
 * All five ring-capture slots at once, in a 2-1-2 arrangement -- replaces the old single giant
 * "hero" frequency: every captured tone is glanceable without navigating anywhere. [slotCaptureIds]
 * (from [RingSlotAssigner]) keeps each populated tile's screen position stable as prominence/
 * recency change, so a tap always lands on the capture the user is looking at. [mostProminentCaptureId]
 * only gets an understated outline -- never a duplicated big number.
 *
 * Interaction: tap a populated tile to toggle ITS pin directly -- that's the only gesture a tile
 * responds to now (no long-press, no separate Captures list to select from first). A pinned tile's
 * fill switches to the theme's held accent (never color-only: the status label also gains a "◆"
 * prefix) so the pinned/unpinned states stay visually distinct at a glance. Empty slots show a
 * restrained "—" and do nothing on tap.
 */
@Composable
fun RingTilesGrid(
    slotCaptureIds: List<Long?>,
    captures: List<RingCapture>,
    mostProminentCaptureId: Long?,
    autoHoldMs: Long,
    tileSize: Dp,
    rowSpacing: Dp = 6.dp,
    onTogglePin: (RingCapture) -> Unit,
) {
    val byId = captures.associateBy { it.id }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RingTile(byId[slotCaptureIds.getOrNull(0)], slotCaptureIds.getOrNull(0) == mostProminentCaptureId, autoHoldMs, tileSize, onTogglePin)
            RingTile(byId[slotCaptureIds.getOrNull(1)], slotCaptureIds.getOrNull(1) == mostProminentCaptureId, autoHoldMs, tileSize, onTogglePin)
        }
        RingTile(byId[slotCaptureIds.getOrNull(2)], slotCaptureIds.getOrNull(2) == mostProminentCaptureId, autoHoldMs, tileSize, onTogglePin)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RingTile(byId[slotCaptureIds.getOrNull(3)], slotCaptureIds.getOrNull(3) == mostProminentCaptureId, autoHoldMs, tileSize, onTogglePin)
            RingTile(byId[slotCaptureIds.getOrNull(4)], slotCaptureIds.getOrNull(4) == mostProminentCaptureId, autoHoldMs, tileSize, onTogglePin)
        }
    }
}

@Composable
private fun RingTile(
    capture: RingCapture?,
    isMostProminent: Boolean,
    autoHoldMs: Long,
    tileSize: Dp,
    onTogglePin: (RingCapture) -> Unit,
) {
    val palette = LocalStageScopePalette.current
    val tileNumeralStyle = chartAnnotationStyle().copy(fontSize = 9.5.sp)
    val tileStatusStyle = chartAnnotationStyle().copy(fontSize = 8.5.sp)

    if (capture == null) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .border(width = 1.dp, color = palette.Grid, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("—", style = tileNumeralStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val now = SystemMonotonicClock.nowMillis()
    val state = capture.stateAt(now, autoHoldMs = autoHoldMs, liveGraceMs = 150)
    val statusLabel = when {
        capture.restoredFromDisk -> "SAVE"
        state == RingCaptureState.LIVE -> "LIVE"
        state == RingCaptureState.HELD || state == RingCaptureState.PINNED -> "HELD"
        else -> "SAVE"
    }
    val accent = if (capture.pinned) palette.Held else palette.Live
    val description = "${formatFrequencyReadable(capture.frequencyHz)}, $statusLabel, " +
        (if (capture.pinned) "pinned. Tap to unpin." else "unpinned. Tap to pin.")

    Box(
        modifier = Modifier
            .size(tileSize)
            .background(if (capture.pinned) palette.HeldDim else palette.Surface, CircleShape)
            .then(
                if (isMostProminent) {
                    Modifier.border(width = 1.5.dp, color = palette.Live, shape = CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = { onTogglePin(capture) })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                formatFrequencyCompact(capture.frequencyHz),
                style = tileNumeralStyle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = (if (capture.pinned) "◆" else "") + statusLabel,
                style = tileStatusStyle,
                color = accent,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
