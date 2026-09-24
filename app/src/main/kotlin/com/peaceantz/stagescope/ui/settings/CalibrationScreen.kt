package com.peaceantz.stagescope.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.components.DetailButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette

@Composable
fun CalibrationScreen(viewModel: CalibrationViewModel, onExit: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.resetToStart() }

    val step by viewModel.step.collectAsStateWithLifecycle()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val isRunning = availability is CaptureAvailability.Running
    KeepScreenOnEffect(enabled = isRunning)

    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { StepHeader(step) }

        when (step) {
            CalibrationStep.PREPARE -> prepareStep(this, viewModel, availability, requestStart, onExit)
            CalibrationStep.ENTER_REFERENCE -> enterReferenceStep(this, viewModel)
            CalibrationStep.MEASURE -> measureStep(this, viewModel, availability, requestStart)
            CalibrationStep.REVIEW -> reviewStep(this, viewModel)
            CalibrationStep.SUCCESS -> successStep(this, onExit)
        }
    }
}

@Composable
private fun StepHeader(step: CalibrationStep) {
    val label = when (step) {
        CalibrationStep.PREPARE -> "Calibrate · 1 of 4 — Prepare"
        CalibrationStep.ENTER_REFERENCE -> "Calibrate · 2 of 4 — Reference reading"
        CalibrationStep.MEASURE -> "Calibrate · 3 of 4 — Measure"
        CalibrationStep.REVIEW -> "Calibrate · 4 of 4 — Review"
        CalibrationStep.SUCCESS -> "Calibrate · Done"
    }
    Text(label, color = MaterialTheme.colorScheme.onBackground)
}

private fun prepareStep(
    scope: androidx.wear.compose.foundation.lazy.ScalingLazyListScope,
    viewModel: CalibrationViewModel,
    availability: CaptureAvailability,
    requestStart: () -> Unit,
    onExit: () -> Unit,
) = with(scope) {
    item {
        Text(
            "Calibration matches this watch's level reading to a trusted sound-level meter. Without " +
                "calibration, StageScope still works and shows dBFS.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item {
        Text(
            "Place the watch and reference meter close together, with both microphones unobstructed. " +
                "Play a steady tone or noise at a comfortable level.",
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
    item {
        Text(
            "StageScope measures an unweighted (Z) signal. Set the reference meter to Z/unweighted, " +
                "steady or slow response -- not A-weighted; an A-weighted reading is not equivalent.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item { ExpandableTechnicalNote() }
    item { CaptureStatusLine(availability, requestStart) }
    item { DetailButton(onClick = viewModel::goToEnterReference, modifier = Modifier.fillMaxWidth()) { Text("Continue") } }
    item { DetailButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Use dBFS (skip calibration)") } }
}

@Composable
private fun ExpandableTechnicalNote() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (expanded) {
            Text(
                "This is reference alignment, not certified meter accuracy. A single offset cannot " +
                    "correct the microphone's frequency response or a later automatic gain change; it " +
                    "only shifts the RMS reading to match the meter at the moment you measured it. Tap " +
                    "to collapse.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { expanded = false },
            )
        } else {
            Text(
                "What this can't fix ▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            )
        }
    }
}

private fun enterReferenceStep(
    scope: androidx.wear.compose.foundation.lazy.ScalingLazyListScope,
    viewModel: CalibrationViewModel,
) = with(scope) {
    item {
        Text(
            "Enter the number shown on the separate meter -- not a number from this watch.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item { Text("Reference meter reading", color = MaterialTheme.colorScheme.onBackground) }
    item { ReferenceReadingControl(viewModel) }
    item {
        val touched by viewModel.referenceTouched.collectAsStateWithLifecycle()
        if (!touched) {
            Text(
                "Example value shown -- adjust it or confirm it matches your meter exactly.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        val touched by viewModel.referenceTouched.collectAsStateWithLifecycle()
        if (!touched) {
            DetailButton(onClick = viewModel::confirmShownValueIsMeasured, modifier = Modifier.fillMaxWidth()) {
                Text("Use shown value as my measured reading")
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DetailButton(onClick = viewModel::backToPrepare) { Text("Back") }
            val touched by viewModel.referenceTouched.collectAsStateWithLifecycle()
            DetailButton(onClick = viewModel::goToMeasure, enabled = touched) { Text("Continue") }
        }
    }
}

@Composable
private fun ReferenceReadingControl(viewModel: CalibrationViewModel) {
    val targetDb by viewModel.targetDb.collectAsStateWithLifecycle()
    val palette = LocalStageScopePalette.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
        Text("${"%.1f".format(targetDb)} dB SPL", color = palette.Live)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DetailButton(onClick = { viewModel.adjustTarget(-1.0) }) { Text("−") }
        DetailButton(onClick = { viewModel.adjustTarget(1.0) }) { Text("+") }
    }
}

private fun measureStep(
    scope: androidx.wear.compose.foundation.lazy.ScalingLazyListScope,
    viewModel: CalibrationViewModel,
    availability: CaptureAvailability,
    requestStart: () -> Unit,
) = with(scope) {
    item {
        Text(
            "Keep playing the steady reference tone or noise. Measure reference samples for about 3 " +
                "seconds and averages the level -- it does not use a single instant reading.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item { CaptureStatusLine(availability, requestStart) }
    item {
        val sampling by viewModel.sampling.collectAsStateWithLifecycle()
        val palette = LocalStageScopePalette.current
        if (sampling != null) {
            val s = sampling!!
            val fraction = (s.elapsedMs.toFloat() / s.totalMs.toFloat()).coerceIn(0f, 1f)
            Text("Sampling… ${(fraction * 100).toInt()}%", color = MaterialTheme.colorScheme.onBackground)
            SamplingProgressBar(fraction, s.clippedSoFar, palette)
            Text(
                text = if (s.clippedSoFar) "Clipping detected -- lower the level" else "Live raw: ${"%.1f".format(s.liveRawRmsDbfs)} dBFS",
                color = if (s.clippedSoFar) palette.Held else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val live by viewModel.liveRawRmsDbfs.collectAsStateWithLifecycle()
            Text("Live raw: ${"%.1f".format(live)} dBFS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        val sampling by viewModel.sampling.collectAsStateWithLifecycle()
        val isRunning = availability is CaptureAvailability.Running
        DetailButton(
            onClick = viewModel::measureReference,
            enabled = isRunning && sampling == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Measure reference") }
    }
    item { DetailButton(onClick = viewModel::backToPrepare, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
}

@Composable
private fun SamplingProgressBar(fraction: Float, clipped: Boolean, palette: com.peaceantz.stagescope.ui.theme.StageScopePalette) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(5.dp)) {
        val h = size.height
        val radius = androidx.compose.ui.geometry.CornerRadius(h / 2)
        drawRoundRect(color = palette.Grid, size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = radius)
        if (fraction > 0f) {
            drawRoundRect(
                color = if (clipped) palette.Held else palette.Live,
                size = androidx.compose.ui.geometry.Size(size.width * fraction, h),
                cornerRadius = radius,
            )
        }
    }
}

private fun reviewStep(
    scope: androidx.wear.compose.foundation.lazy.ScalingLazyListScope,
    viewModel: CalibrationViewModel,
) = with(scope) {
    val measurement = viewModel.measurement.value
    val validity = viewModel.currentValidity()

    if (measurement == null) {
        item { Text("No measurement to review.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { DetailButton(onClick = viewModel::backToEnterReference, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
        return@with
    }

    item {
        val targetDb = viewModel.targetDb.value
        InfoLine("Reference meter reading", "${"%.1f".format(targetDb)} dB SPL")
    }
    item { InfoLine("Measured raw watch level", "${"%.1f".format(measurement.averagedRawRmsDbfs)} dBFS") }
    item {
        val offset = viewModel.offsetPreview() ?: 0.0
        InfoLine("Resulting alignment", "Level will show Estimated SPL (offset ${"%+.1f".format(offset)} dB)")
    }

    if (validity is CalibrationValidity.Invalid) {
        item {
            val palette = LocalStageScopePalette.current
            StateBadge("⚠", validity.reason, palette.Held)
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DetailButton(onClick = viewModel::retryMeasurement) { Text("Retry") }
            DetailButton(onClick = viewModel::confirmSave, enabled = validity is CalibrationValidity.Valid) { Text("Save") }
        }
    }
}

private fun successStep(
    scope: androidx.wear.compose.foundation.lazy.ScalingLazyListScope,
    onExit: () -> Unit,
) = with(scope) {
    item {
        Text(
            "Calibration saved. Level readings now show Estimated SPL.",
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
    item {
        Text(
            "This is reference alignment, not certified meter accuracy -- it cannot correct microphone " +
                "response or automatic gain changes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item { DetailButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
}

@Composable
private fun CaptureStatusLine(availability: CaptureAvailability, requestStart: () -> Unit) {
    val palette = LocalStageScopePalette.current
    when (availability) {
        CaptureAvailability.NotStarted -> DetailButton(onClick = requestStart, modifier = Modifier.fillMaxWidth()) { Text("Start listening") }
        CaptureAvailability.PermissionDenied -> StateBadge("⚠", "Microphone permission denied", palette.Held)
        is CaptureAvailability.Unavailable -> StateBadge("⚠", "Unavailable: ${availability.reason}", palette.Held)
        is CaptureAvailability.Error -> StateBadge("⚠", "Error: ${availability.message}", palette.Held)
        CaptureAvailability.Running -> StateBadge("●", "Listening", palette.Live)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
