package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.compactReadoutStyle
import com.peaceantz.stagescope.ui.theme.primaryLevelStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle
import kotlin.math.atan2

@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel,
    compareSnapshotId: String?,
    onOpenDetails: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val compareSnapshot by viewModel.compareSnapshot.collectAsStateWithLifecycle()
    LaunchedEffect(compareSnapshotId) { viewModel.setCompareSnapshot(compareSnapshotId) }

    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is AnalyzerUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isMeasuring) { if (isMeasuring) focusRequester.requestFocus() }

    val palette = LocalStageScopePalette.current

    ModePageScaffold(
        modeTitle = "ANALYZER",
        onOpenDetails = onOpenDetails,
        lowerActions = {
            when (val s = state) {
                is AnalyzerUiState.Measuring -> {
                    val held = s.reading.spectrum?.isHeld == true
                    CompactGlyphButton(glyph = "■", contentDescription = "Stop measuring", onClick = viewModel::stop)
                    CompactGlyphButton(
                        glyph = if (held) "▶" else "‖",
                        contentDescription = if (held) "Resume spectrum" else "Freeze spectrum",
                        onClick = if (held) viewModel::resume else viewModel::freeze,
                    )
                }
                else -> CompactGlyphButton(glyph = "▶", contentDescription = "Start measuring", onClick = requestStart)
            }
        },
    ) {
        when (val s = state) {
            AnalyzerUiState.NotStarted -> StateBadge("●", "Ready", MaterialTheme.colorScheme.onSurfaceVariant)
            AnalyzerUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", palette.Held)
            is AnalyzerUiState.Unavailable -> StateBadge("⚠", "Unavailable", palette.Held)
            is AnalyzerUiState.Error -> StateBadge("⚠", "Error", palette.Held)
            AnalyzerUiState.Paused -> StateBadge("●", "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
            is AnalyzerUiState.Measuring -> AnalyzerDial(
                reading = s.reading,
                compareSnapshot = compareSnapshot,
                focusRequester = focusRequester,
                onMoveCursor = viewModel::moveCursor,
                onTapArcFraction = viewModel::setCursorArcFraction,
            )
        }
    }
}

@Composable
private fun AnalyzerDial(
    reading: AnalyzerReading,
    compareSnapshot: SpectrumSnapshot?,
    focusRequester: FocusRequester,
    onMoveCursor: (Int) -> Unit,
    onTapArcFraction: (Float) -> Unit,
) {
    val palette = LocalStageScopePalette.current
    val spectrum = reading.spectrum
    val compatible = compareSnapshot != null &&
        spectrum != null &&
        compareSnapshot.sampleRate == spectrum.sampleRate &&
        compareSnapshot.fftSize == spectrum.fftSize

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                onMoveCursor(if (event.verticalScrollPixels > 0) 1 else -1)
                true
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val angleDeg = Math.toDegrees(
                        atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())
                    ).toFloat()
                    RadialMapping.fractionForAngle(angleDeg)?.let(onTapArcFraction)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (spectrum != null) {
            RadialSpectrumCanvas(
                spectrum = spectrum,
                compareSnapshot = compareSnapshot,
                isComparisonCompatible = compatible,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (reading.isClippingNow) {
                StateBadge("⚠", "CLIP", palette.Held)
            } else if (reading.isSuspiciousSilence) {
                StateBadge("⚠", "Silence — check mic", palette.Held)
            }

            Text(
                text = formatDb(reading.rmsDisplayDbfs),
                style = primaryLevelStyle(),
                color = if (reading.isClippingNow) palette.Held else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = unitLine(reading),
                style = secondaryStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (spectrum != null) {
                val band = spectrum.bands.getOrNull(spectrum.cursorBandIndex)
                if (band != null) {
                    val freqHz = band.peakBin * spectrum.binWidthHz
                    Text(
                        text = "${formatHz(freqHz)} · ${"%.0f".format(band.magnitudeDbfs)} dBFS",
                        style = compactReadoutStyle(),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

private fun unitLine(reading: AnalyzerReading): String {
    val tags = buildList {
        if (reading.isDemo) add("DEMO")
        if (reading.spectrum?.isHeld == true) add("SPECTRUM HELD")
    }
    return if (tags.isEmpty()) reading.unitLabel else "${reading.unitLabel} · ${tags.joinToString(" · ")}"
}

private fun formatDb(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(value)}"

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
