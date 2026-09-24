package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.ui.components.DetailButton
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle

@Composable
fun RingCapturesScreen(viewModel: RingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshot = (state as? RingUiState.Measuring)?.measurement?.snapshot
    val history = snapshot?.history ?: emptyList()
    val selectedId = snapshot?.heroCapture?.id
    val listState = rememberScalingLazyListState()
    val palette = LocalStageScopePalette.current

    var clearAllArmed by remember { mutableStateOf(false) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 18.dp),
    ) {
        item { Text("Captures (${history.size}/5)", color = MaterialTheme.colorScheme.onBackground) }
        if (snapshot?.allSlotsPinned == true) {
            item {
                Text(
                    "All 5 pinned — unpin or clear one to capture another",
                    style = chartAnnotationStyle(),
                    color = palette.Held,
                )
            }
        }

        if (history.isEmpty()) {
            item { Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(history, key = { it.id }) { capture ->
            CaptureRow(
                capture = capture,
                isSelected = capture.id == selectedId,
                autoHoldMs = viewModel.autoHoldSeconds() * 1000L,
                onSelect = { viewModel.selectCapture(capture.id) },
                onPinToggle = { if (capture.pinned) viewModel.unpin(capture.id) else viewModel.pin(capture.id) },
            )
        }

        item {
            DetailButton(onClick = viewModel::clearUnpinned, modifier = Modifier.fillMaxWidth()) { Text("Clear unpinned") }
        }
        item {
            if (clearAllArmed) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    DetailButton(onClick = { clearAllArmed = false }) { Text("Cancel") }
                    DetailButton(
                        onClick = {
                            viewModel.clearAll()
                            clearAllArmed = false
                        },
                    ) { Text("Confirm: clear all (incl. pins)") }
                }
            } else {
                DetailButton(onClick = { clearAllArmed = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all") }
            }
        }
    }
}

@Composable
private fun CaptureRow(capture: RingCapture, isSelected: Boolean, autoHoldMs: Long, onSelect: () -> Unit, onPinToggle: () -> Unit) {
    val palette = LocalStageScopePalette.current
    val now = com.peaceantz.stagescope.dsp.SystemMonotonicClock.nowMillis()
    val stateLabel = when (capture.stateAt(now, autoHoldMs = autoHoldMs, liveGraceMs = 150)) {
        RingCaptureState.LIVE -> "LIVE"
        RingCaptureState.HELD -> "HELD"
        RingCaptureState.EXPIRED -> "HISTORICAL"
        RingCaptureState.PINNED -> "PINNED"
        else -> ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) palette.Live else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp),
            )
            .background(if (isSelected) palette.Surface else MaterialTheme.colorScheme.background)
            .padding(8.dp),
    ) {
        Text(
            text = formatHz(capture.frequencyHz) + if (isSelected) " (selected)" else "",
            color = if (capture.pinned) palette.Held else MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stateLabel, style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (capture.restoredFromDisk) {
                Text("SAVED", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailButton(onClick = onSelect, enabled = !isSelected) { Text("Select") }
            DetailButton(onClick = onPinToggle) { Text(if (capture.pinned) "Unpin" else "Pin") }
        }
    }
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
