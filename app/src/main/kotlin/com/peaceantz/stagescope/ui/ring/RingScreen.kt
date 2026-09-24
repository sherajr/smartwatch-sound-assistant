package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.StageScopeColors
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.ui.theme.frequencyStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle

@Composable
fun RingScreen(viewModel: RingViewModel, onOpenDetails: () -> Unit, onOpenCaptures: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is RingUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)

    ModePageScaffold(
        modeTitle = "RING",
        onOpenDetails = onOpenDetails,
        lowerActions = {
            when (val s = state) {
                is RingUiState.Measuring -> {
                    val hero = s.measurement.snapshot.heroCapture
                    val pinned = hero?.pinned == true
                    CompactGlyphButton(
                        glyph = if (pinned) "◇" else "◆",
                        contentDescription = if (pinned) "Unpin capture" else "Pin capture",
                        enabled = hero != null,
                        onClick = { hero?.let { if (pinned) viewModel.unpin() else viewModel.pin(it.id) } },
                    )
                    CompactGlyphButton(
                        glyph = "✕",
                        contentDescription = "Clear selected capture",
                        enabled = hero != null,
                        onClick = viewModel::clearSelected,
                    )
                }
                else -> CompactGlyphButton(glyph = "▶", contentDescription = "Start measuring", onClick = requestStart)
            }
        },
    ) {
        when (val s = state) {
            RingUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", StageScopeColors.Held)
            is RingUiState.Unavailable -> StateBadge("⚠", "Unavailable", StageScopeColors.Held)
            is RingUiState.Error -> StateBadge("⚠", "Error", StageScopeColors.Held)
            is RingUiState.Measuring -> RingHero(s.measurement, onOpenCaptures)
        }
    }
}

@Composable
private fun RingHero(measurement: RingMeasurement, onOpenCaptures: () -> Unit) {
    val snap = measurement.snapshot
    val hero = snap.heroCapture

    val (symbol, label, color) = stateBadgeFor(snap.heroState)
    StateBadge(symbol, label, color)
    Spacer(Modifier.height(4.dp))

    val heroFreq = hero?.frequencyHz ?: snap.detectingFrequencyHz
    Text(
        text = heroFreq?.let { formatHz(it) } ?: "—",
        style = frequencyStyle(),
        color = if (hero != null) color else MaterialTheme.colorScheme.onBackground,
    )

    if (hero != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "prominence ${"%.0f".format(hero.prominenceDb)} dB",
            style = secondaryStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (snap.heroState == RingCaptureState.SEARCHING) {
        Text(
            text = "Listening for a persistent tone",
            style = secondaryStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (snap.otherCandidates.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.clickable(onClick = onOpenCaptures),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Also:", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (candidate in snap.otherCandidates.take(2)) {
                CandidateLine(candidate)
            }
        }
    }

    if (measurement.isDemo) {
        Spacer(Modifier.height(6.dp))
        Text("DEMO — synthetic signal", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateLine(candidate: RingCapture) {
    Row {
        Text(
            text = formatHz(candidate.frequencyHz),
            style = chartAnnotationStyle(),
            color = if (candidate.pinned) StageScopeColors.Held else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

private fun stateBadgeFor(state: RingCaptureState): Triple<String, String, androidx.compose.ui.graphics.Color> = when (state) {
    RingCaptureState.SEARCHING -> Triple("●", "SEARCHING", StageScopeColors.SecondaryText)
    RingCaptureState.DETECTING -> Triple("●", "DETECTING", StageScopeColors.Live)
    RingCaptureState.LIVE -> Triple("●", "LIVE", StageScopeColors.Live)
    RingCaptureState.HELD -> Triple("◆", "HELD", StageScopeColors.Held)
    RingCaptureState.PINNED -> Triple("◆", "PINNED", StageScopeColors.Held)
    RingCaptureState.PAUSED -> Triple("●", "PAUSED", StageScopeColors.SecondaryText)
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
