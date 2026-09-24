package com.peaceantz.stagescope.ui.level

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.ui.settings.SettingsViewModel

@Composable
fun LevelDetailsScreen(
    container: AppContainer,
    viewModel: LevelViewModel,
    onOpenCalibration: () -> Unit,
    onOpenWatchShortcuts: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel { SettingsViewModel(container) },
) {
    val levelState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Level details", color = MaterialTheme.colorScheme.onBackground) }

        item { DetailButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Reset") } }

        val measurement = (levelState as? LevelUiState.Measuring)?.measurement
        if (measurement != null) {
            item { InfoLine("Source", measurement.sourceLabel) }
            item { InfoLine("Keep-awake", "${measurement.keepAwakeRemainingSeconds}s remaining") }
            if (measurement.hasClippedEver) {
                item { InfoLine("Clipping", "Occurred this session") }
            }
        } else {
            item { InfoLine("Status", "Not currently measuring") }
        }

        item { DetailButton(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) { Text("Calibrate SPL") } }
        val calibration = settings.calibration
        if (calibration != null) {
            item {
                InfoLine(
                    "Calibrated",
                    "offset ${"%.1f".format(calibration.offsetDb)} dB (meter ${"%.1f".format(calibration.referenceMeterReadingDb)} dB)",
                )
            }
            item { DetailButton(onClick = settingsViewModel::clearCalibration, modifier = Modifier.fillMaxWidth()) { Text("Clear calibration") } }
        } else {
            item { InfoLine("Calibration", "Not calibrated — shows dBFS") }
        }

        item {
            DetailButton(onClick = { settingsViewModel.setDemoMode(!settings.demoModeEnabled) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (settings.demoModeEnabled) "Demo: ON" else "Demo: OFF")
            }
        }
        item {
            Text(
                "Demo mode feeds a synthetic signal through the analysis pipeline without using the microphone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            DetailButton(onClick = { settingsViewModel.setDimAppearance(!settings.dimAppearanceEnabled) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (settings.dimAppearanceEnabled) "Dim: ON" else "Dim: OFF")
            }
        }
        item {
            Text(
                "Lowers on-screen luminance for a dark theatre. Does not change system brightness.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { DetailButton(onClick = onOpenWatchShortcuts, modifier = Modifier.fillMaxWidth()) { Text("Watch shortcuts") } }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
