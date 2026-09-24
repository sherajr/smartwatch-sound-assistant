package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.ui.theme.StageScopeColors

@Composable
fun RingCapturesScreen(viewModel: RingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history = (state as? RingUiState.Measuring)?.measurement?.snapshot?.history ?: emptyList()
    val selectedId = (state as? RingUiState.Measuring)?.measurement?.snapshot?.heroCapture?.id
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 18.dp),
    ) {
        item { Text("Captures (${history.size}/8)", color = MaterialTheme.colorScheme.onBackground) }

        if (history.isEmpty()) {
            item { Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(history, key = { it.id }) { capture ->
            CaptureRow(
                capture = capture,
                isSelected = capture.id == selectedId,
                onSelect = { viewModel.selectCapture(capture.id) },
                onPinToggle = { if (capture.pinned) viewModel.unpin() else viewModel.pin(capture.id) },
            )
        }

        item { DetailButton(onClick = viewModel::clearAll, modifier = Modifier.fillMaxWidth()) { Text("Clear all") } }
    }
}

@Composable
private fun CaptureRow(capture: RingCapture, isSelected: Boolean, onSelect: () -> Unit, onPinToggle: () -> Unit) {
    Column {
        Text(
            text = formatHz(capture.frequencyHz) + if (isSelected) " (selected)" else "",
            color = if (capture.pinned) StageScopeColors.Held else MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailButton(onClick = onSelect) { Text("Select") }
            DetailButton(onClick = onPinToggle) { Text(if (capture.pinned) "Unpin" else "Pin") }
        }
    }
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
