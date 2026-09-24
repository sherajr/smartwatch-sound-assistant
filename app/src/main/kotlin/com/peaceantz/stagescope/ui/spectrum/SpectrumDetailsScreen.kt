package com.peaceantz.stagescope.ui.spectrum

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun SpectrumDetailsScreen(viewModel: SpectrumViewModel, onOpenSnapshots: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Spectrum details", color = MaterialTheme.colorScheme.onBackground) }

        val frame = when (val s = state) {
            is SpectrumUiState.Measuring -> s.frame
            is SpectrumUiState.Frozen -> s.frame
            else -> null
        }

        item {
            DetailButton(onClick = viewModel::saveSnapshot, enabled = frame != null, modifier = Modifier.fillMaxWidth()) { Text("Save snapshot") }
        }
        item { DetailButton(onClick = onOpenSnapshots, modifier = Modifier.fillMaxWidth()) { Text("Snapshots") } }
        item { DetailButton(onClick = viewModel::clearPeakHold, modifier = Modifier.fillMaxWidth()) { Text("Clear peak hold") } }

        if (frame != null) {
            item { InfoLine("Sample rate", "${frame.sampleRate} Hz") }
            item { InfoLine("FFT size", "${frame.fftSize} (Hann, 50% overlap)") }
            item { InfoLine("Resolution", "%.2f Hz/bin".format(frame.binWidthHz)) }
            item { InfoLine("Nyquist", "${frame.nyquistHz.toInt()} Hz") }
            item { InfoLine("Source", frame.sourceLabel) }
            item { InfoLine("Scale", "one-sided amplitude, dBFS") }
        } else {
            item { InfoLine("Status", "Not currently measuring") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
