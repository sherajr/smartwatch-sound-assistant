package com.peaceantz.stagescope.ui.level

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.LevelBar
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.StageScopeColors
import com.peaceantz.stagescope.ui.theme.primaryLevelStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle

@Composable
fun LevelScreen(viewModel: LevelViewModel, onOpenDetails: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is LevelUiState.Measuring
    KeepScreenOnEffect(enabled = isMeasuring)

    ModePageScaffold(
        modeTitle = "LEVEL",
        onOpenDetails = onOpenDetails,
        lowerActions = {
            if (isMeasuring) {
                CompactGlyphButton(glyph = "■", contentDescription = "Stop measuring", onClick = viewModel::stop)
            } else {
                CompactGlyphButton(glyph = "▶", contentDescription = "Start measuring", onClick = requestStart)
            }
        },
    ) {
        when (val s = state) {
            LevelUiState.NotStarted -> StateBadge("●", "Ready", MaterialTheme.colorScheme.onSurfaceVariant)
            LevelUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", StageScopeColors.Held)
            is LevelUiState.Unavailable -> StateBadge("⚠", "Unavailable", StageScopeColors.Held)
            is LevelUiState.Error -> StateBadge("⚠", "Error", StageScopeColors.Held)
            LevelUiState.Paused -> StateBadge("●", "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
            is LevelUiState.Measuring -> LevelReadout(s.measurement)
        }
    }
}

@Composable
private fun LevelReadout(m: LevelMeasurement) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (m.isSuspiciousSilence) {
            StateBadge("⚠", "Silence — check mic", StageScopeColors.Held)
            Spacer(Modifier.height(4.dp))
        } else if (m.isClippingNow) {
            StateBadge("⚠", "CLIP", StageScopeColors.Held)
            Spacer(Modifier.height(4.dp))
        } else if (m.isDemo) {
            StateBadge("◆", "DEMO", MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }

        Text(
            text = formatDb(m.rmsDisplayDbfs),
            style = primaryLevelStyle(),
            color = if (m.isClippingNow) StageScopeColors.Held else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { contentDescription = "RMS level ${formatDb(m.rmsDisplayDbfs)} ${m.unitLabel}" },
        )
        Text(text = m.unitLabel, style = secondaryStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))
        LevelBar(currentDbfs = m.rmsDisplayDbfs, isClipping = m.isClippingNow, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricChip("PK", "Sample peak", formatDb(m.peakDbfs))
            MetricChip("MAX", "Maximum RMS", formatDb(m.maxDisplayDbfs))
            MetricChip("AVG", "Session average", formatDb(m.sessionAverageDisplayDbfs))
        }
        if (m.elapsedSeconds > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatElapsed(m.elapsedSeconds),
                style = secondaryStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Session duration ${formatElapsed(m.elapsedSeconds)}" },
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, accessibleName: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = "$accessibleName $value" },
    ) {
        Text(text = label, style = secondaryStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = secondaryStyle(), color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun formatDb(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(value)}"

private fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
