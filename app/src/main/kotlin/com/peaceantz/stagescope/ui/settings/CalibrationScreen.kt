package com.peaceantz.stagescope.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.StageScopeColors

@Composable
fun CalibrationScreen(viewModel: CalibrationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val targetDb by viewModel.targetDb.collectAsStateWithLifecycle()
    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is CalibrationUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isMeasuring) { if (isMeasuring) focusRequester.requestFocus() }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Calibrate", color = MaterialTheme.colorScheme.onBackground) }
        item {
            Text(
                "Play a steady, unweighted (Z) tone or noise. Read its level off a trusted meter, " +
                    "dial that number in with the crown, then Confirm. A single offset cannot fix " +
                    "mic response, gain changes, or overload.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            when (val s = state) {
                CalibrationUiState.NotStarted -> StateBadge("●", "Press Start to measure", MaterialTheme.colorScheme.onSurfaceVariant)
                CalibrationUiState.PermissionDenied -> StateBadge("⚠", "Microphone permission denied", StageScopeColors.Held)
                is CalibrationUiState.Unavailable -> StateBadge("⚠", "Unavailable: ${s.reason}", StageScopeColors.Held)
                is CalibrationUiState.Error -> StateBadge("⚠", "Error: ${s.message}", StageScopeColors.Held)
                CalibrationUiState.Paused -> StateBadge("●", "Paused — press Start", MaterialTheme.colorScheme.onSurfaceVariant)
                is CalibrationUiState.Measuring -> {
                    Text("Live raw: ${"%.1f".format(s.liveRawRmsDbfs)} dBFS", color = MaterialTheme.colorScheme.onBackground)
                    if (s.isDemo) StateBadge("◆", "DEMO", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onRotaryScrollEvent { event ->
                        viewModel.adjustTarget(if (event.verticalScrollPixels > 0) 0.5 else -0.5)
                        true
                    },
            ) {
                Text("Target: ${"%.1f".format(targetDb)} dB", color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DetailButton(onClick = { viewModel.adjustTarget(-1.0) }) { Text("−") }
                DetailButton(onClick = { viewModel.adjustTarget(1.0) }) { Text("+") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (isMeasuring) {
                    DetailButton(onClick = viewModel::stop) { Text("Stop") }
                } else {
                    DetailButton(onClick = requestStart) { Text("Start") }
                }
                DetailButton(onClick = viewModel::confirmCalibration, enabled = isMeasuring) { Text("Confirm") }
            }
        }
    }
}
