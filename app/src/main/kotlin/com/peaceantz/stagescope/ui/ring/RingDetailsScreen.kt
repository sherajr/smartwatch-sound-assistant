package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.layout.Arrangement
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun RingDetailsScreen(viewModel: RingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isMeasuring = state is RingUiState.Measuring
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Ring details", color = MaterialTheme.colorScheme.onBackground) }
        item {
            Text(
                "Possible ring: a narrowband peak that keeps reappearing near the same frequency. " +
                    "A steady musical tone can trigger this just as easily as feedback build-up. " +
                    "This does not identify the source or suggest an EQ cut.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            if (isMeasuring) {
                DetailButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                DetailButton(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            }
        }
        item { DetailButton(onClick = viewModel::clearAll, modifier = Modifier.fillMaxWidth()) { Text("Clear all") } }

        item { Text("Auto-hold duration", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            val current = viewModel.autoHoldSeconds()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (seconds in listOf(10, 20, 30)) {
                    DetailButton(onClick = { viewModel.setAutoHoldSeconds(seconds) }) {
                        Text(if (seconds == current) "[${seconds}s]" else "${seconds}s")
                    }
                }
            }
        }
    }
}
