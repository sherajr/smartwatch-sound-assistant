package com.peaceantz.stagescope.ui.ring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.ui.theme.frequencyStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle

@Composable
fun RingScreen(viewModel: RingViewModel, onOpenDetails: () -> Unit, onOpenCaptures: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is RingUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)
    val palette = LocalStageScopePalette.current

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
                        onClick = { hero?.let { if (pinned) viewModel.unpin(it.id) else viewModel.pin(it.id) } },
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
            RingUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", palette.Held)
            is RingUiState.Unavailable -> StateBadge("⚠", "Unavailable", palette.Held)
            is RingUiState.Error -> StateBadge("⚠", "Error", palette.Held)
            is RingUiState.Measuring -> RingHero(s.measurement, onOpenCaptures)
        }
    }
}

@Composable
private fun RingHero(measurement: RingMeasurement, onOpenCaptures: () -> Unit) {
    val snap = measurement.snapshot
    val hero = snap.heroCapture
    val palette = LocalStageScopePalette.current

    val (symbol, label, color) = stateBadgeFor(snap.heroState, palette)
    StateBadge(symbol, label, color)

    val heroFreq = hero?.frequencyHz ?: snap.detectingFrequencyHz
    Text(
        text = heroFreq?.let { formatHz(it) } ?: "—",
        style = frequencyStyle(),
        color = if (hero != null) color else MaterialTheme.colorScheme.onBackground,
    )

    if (hero != null) {
        val tag = if (hero.restoredFromDisk) "saved · prominence ${"%.0f".format(hero.prominenceDb)} dB" else "prominence ${"%.0f".format(hero.prominenceDb)} dB"
        Text(text = tag, style = secondaryStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (snap.heroState == RingCaptureState.SEARCHING) {
        Text(
            text = "Listening for a persistent tone",
            style = secondaryStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(6.dp))
    Column(
        modifier = Modifier.clickable(onClick = onOpenCaptures),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Captures ${snap.slotsUsed}/${snap.slotsTotal} ›",
            style = chartAnnotationStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snap.allSlotsPinned) {
            Text(
                text = "All 5 pinned — unpin or clear one to capture another",
                style = chartAnnotationStyle(),
                color = palette.Held,
            )
        }
    }

    if (measurement.isDemo) {
        Text("DEMO — synthetic signal", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stateBadgeFor(
    state: RingCaptureState,
    palette: com.peaceantz.stagescope.ui.theme.StageScopePalette,
): Triple<String, String, androidx.compose.ui.graphics.Color> = when (state) {
    RingCaptureState.SEARCHING -> Triple("●", "SEARCHING", palette.SecondaryText)
    RingCaptureState.DETECTING -> Triple("●", "DETECTING", palette.Live)
    RingCaptureState.LIVE -> Triple("●", "LIVE", palette.Live)
    RingCaptureState.HELD -> Triple("◆", "HELD", palette.Held)
    RingCaptureState.EXPIRED -> Triple("◇", "HISTORICAL", palette.SecondaryText)
    RingCaptureState.PINNED -> Triple("◆", "PINNED", palette.Held)
    RingCaptureState.PAUSED -> Triple("●", "PAUSED", palette.SecondaryText)
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
