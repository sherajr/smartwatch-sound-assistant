package com.peaceantz.stagescope.ui.analyzer

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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.ui.components.DetailButton
import com.peaceantz.stagescope.ui.rotation.OrientationViewModel
import com.peaceantz.stagescope.ui.settings.SettingsViewModel

@Composable
fun AnalyzerDetailsScreen(
    container: AppContainer,
    viewModel: AnalyzerViewModel,
    orientationViewModel: OrientationViewModel,
    onOpenCalibration: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenWatchShortcuts: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel { SettingsViewModel(container) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val orientationLocked by orientationViewModel.locked.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Analyzer details", color = MaterialTheme.colorScheme.onBackground) }

        item { DetailButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Reset") } }
        item { DetailButton(onClick = viewModel::clearPeakHold, modifier = Modifier.fillMaxWidth()) { Text("Clear peak hold") } }

        val reading = (state as? AnalyzerUiState.Measuring)?.reading
        if (reading != null) {
            item { InfoLine("Sample peak (PK)", formatDb(reading.peakDbfs)) }
            item { InfoLine("Maximum RMS (MAX)", formatDb(reading.maxDisplayDbfs)) }
            item { InfoLine("Session average (AVG)", formatDb(reading.sessionAverageDisplayDbfs)) }
            item { InfoLine("Source", reading.sourceLabel) }
            item { InfoLine("Keep-awake", "${reading.keepAwakeRemainingSeconds}s remaining") }
            if (reading.hasClippedEver) {
                item { InfoLine("Clipping", "Occurred this session") }
            }
            val spectrum = reading.spectrum
            if (spectrum != null) {
                item { InfoLine("Sample rate", "${spectrum.sampleRate} Hz") }
                item { InfoLine("FFT size", "${spectrum.fftSize} (Hann, 50% overlap)") }
                item { InfoLine("Resolution", "%.2f Hz/bin".format(spectrum.binWidthHz)) }
                item { InfoLine("Nyquist", "${spectrum.nyquistHz.toInt()} Hz") }
                item { InfoLine("Display bands", "${RadialMapping.DISPLAY_BAND_COUNT} (log-spaced, max-aggregated)") }
                item { InfoLine("Scale", "one-sided amplitude, dBFS") }
            }
        } else {
            item { InfoLine("Status", "Not currently measuring") }
        }
        item { InfoLine("Level meter range", LevelMeterScale.rangeLabel(isCalibrated = reading?.isCalibrated == true)) }

        item {
            DetailButton(onClick = viewModel::saveSnapshot, enabled = reading?.spectrum != null, modifier = Modifier.fillMaxWidth()) {
                Text("Save snapshot")
            }
        }
        item { DetailButton(onClick = onOpenSnapshots, modifier = Modifier.fillMaxWidth()) { Text("Snapshots") } }

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

        item { DetailButton(onClick = onOpenAppearance, modifier = Modifier.fillMaxWidth()) { Text("Appearance") } }
        item { DetailButton(onClick = onOpenWatchShortcuts, modifier = Modifier.fillMaxWidth()) { Text("Watch shortcuts") } }

        item { Text("Orientation", color = MaterialTheme.colorScheme.onBackground) }
        item {
            Text(
                "Turn the crown to rotate the whole instrument to whatever angle is easiest to read " +
                    "from your wrist. Shared across Analyzer, Ring, and this screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { DetailButton(onClick = orientationViewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Reset orientation") } }
        item {
            DetailButton(
                onClick = { orientationViewModel.setLocked(!orientationLocked) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (orientationLocked) "Unlock orientation" else "Lock orientation") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatDb(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(value)} dB"
