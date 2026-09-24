package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.PrimaryActionRow
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.rotation.rotaryOrientation
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.StageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.ui.theme.compactReadoutStyle
import com.peaceantz.stagescope.ui.theme.primaryLevelStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle
import kotlin.math.atan2
import kotlin.math.min

/**
 * The Analyzer page: a full-bleed circular composition (see [InstrumentGeometry]) with the
 * circumference [LevelMeterRing], the [RadialSpectrumCanvas] annulus just inside it, and all text
 * and controls confined to the inner "safe zone" circle -- never a Canvas nested inside a
 * header/footer Column that shrinks its usable size. The whole thing (title, readings, spectrum,
 * meter, buttons) rotates together as one rigid unit driven by the crown, via [angleDegrees]; band
 * selection stays available through touch (tap the arc) exactly as before.
 */
@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel,
    compareSnapshotId: String?,
    angleDegrees: Float,
    orientationLocked: Boolean,
    onRotaryDelta: (Float) -> Unit,
    onOpenDetails: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val compareSnapshot by viewModel.compareSnapshot.collectAsStateWithLifecycle()
    LaunchedEffect(compareSnapshotId) { viewModel.setCompareSnapshot(compareSnapshotId) }

    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is AnalyzerUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)

    val palette = LocalStageScopePalette.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = angleDegrees }
            .rotaryOrientation(locked = orientationLocked, onRotaryDelta = onRotaryDelta),
        contentAlignment = Alignment.Center,
    ) {
        val usableRadiusDp = min(maxWidth.value, maxHeight.value) / 2f
        val radii = InstrumentGeometry.compute(usableRadiusDp)
        val centerDiameter = (radii.centerSafeRadius * 2).dp

        val reading = (state as? AnalyzerUiState.Measuring)?.reading
        val spectrum = reading?.spectrum
        val currentCompare = compareSnapshot

        if (reading != null && spectrum != null) {
            val compatible = currentCompare != null &&
                currentCompare.sampleRate == spectrum.sampleRate &&
                currentCompare.fftSize == spectrum.fftSize

            LevelMeterRing(
                levelFraction = LevelMeterScale.fraction(reading.rmsDisplayDbfs, reading.isCalibrated),
                isClipping = reading.isClippingNow,
                modifier = Modifier.fillMaxSize(),
            )
            RadialSpectrumCanvas(
                spectrum = spectrum,
                compareSnapshot = currentCompare,
                isComparisonCompatible = compatible,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val angleDeg = Math.toDegrees(
                                atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())
                            ).toFloat()
                            RadialMapping.fractionForAngle(angleDeg)?.let(viewModel::setCursorArcFraction)
                        }
                    },
            )
        }

        Column(
            modifier = Modifier.size(centerDiameter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "ANALYZER ›",
                style = chartAnnotationStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable(onClick = onOpenDetails)
                    .semantics {
                        contentDescription = "Open ANALYZER details and actions"
                        role = Role.Button
                    },
            )
            AnalyzerCenterBody(state = state, palette = palette)
        }

        PrimaryActionRow(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
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
        }
    }
}

@Composable
private fun AnalyzerCenterBody(state: AnalyzerUiState, palette: StageScopePalette) {
    when (state) {
        AnalyzerUiState.NotStarted -> StateBadge("●", "Ready", MaterialTheme.colorScheme.onSurfaceVariant)
        AnalyzerUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", palette.Held)
        is AnalyzerUiState.Unavailable -> StateBadge("⚠", "Unavailable", palette.Held)
        is AnalyzerUiState.Error -> StateBadge("⚠", "Error", palette.Held)
        AnalyzerUiState.Paused -> StateBadge("●", "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
        is AnalyzerUiState.Measuring -> {
            val reading = state.reading
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

            val spectrum = reading.spectrum
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
