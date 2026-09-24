package com.peaceantz.stagescope.ui.spectrum

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.ModePageScaffold
import com.peaceantz.stagescope.ui.components.StateBadge
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.theme.StageScopeColors
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.ui.theme.frequencyStyle

@Composable
fun SpectrumScreen(
    viewModel: SpectrumViewModel,
    compareSnapshotId: String?,
    onOpenDetails: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val compareSnapshot by viewModel.compareSnapshot.collectAsStateWithLifecycle()
    LaunchedEffect(compareSnapshotId) { viewModel.setCompareSnapshot(compareSnapshotId) }

    val requestStart = rememberAudioPermissionRequester(onGranted = viewModel::start)
    val isMeasuring = state is SpectrumUiState.Measuring
    val isFrozen = state is SpectrumUiState.Frozen
    KeepScreenOnEffect(enabled = isMeasuring)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isMeasuring) { if (isMeasuring) focusRequester.requestFocus() }

    ModePageScaffold(
        modeTitle = "SPECTRUM",
        onOpenDetails = onOpenDetails,
        lowerActions = {
            when {
                isMeasuring -> CompactGlyphButton(glyph = "‖", contentDescription = "Freeze spectrum", onClick = viewModel::freeze)
                isFrozen -> CompactGlyphButton(glyph = "▶", contentDescription = "Resume spectrum", onClick = viewModel::resume)
                else -> CompactGlyphButton(glyph = "▶", contentDescription = "Start measuring", onClick = requestStart)
            }
        },
    ) {
        when (val s = state) {
            SpectrumUiState.NotStarted -> StateBadge("●", "Ready", MaterialTheme.colorScheme.onSurfaceVariant)
            SpectrumUiState.PermissionDenied -> StateBadge("⚠", "Mic permission denied", StageScopeColors.Held)
            is SpectrumUiState.Unavailable -> StateBadge("⚠", "Unavailable", StageScopeColors.Held)
            is SpectrumUiState.Error -> StateBadge("⚠", "Error", StageScopeColors.Held)
            SpectrumUiState.Paused -> StateBadge("●", "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
            is SpectrumUiState.Measuring -> SpectrumContent(
                frame = s.frame,
                compareSnapshot = compareSnapshot,
                frozen = false,
                focusRequester = focusRequester,
                onMoveCursor = viewModel::moveCursor,
                onTapFraction = viewModel::setCursorFraction,
            )
            is SpectrumUiState.Frozen -> SpectrumContent(
                frame = s.frame,
                compareSnapshot = compareSnapshot,
                frozen = true,
                focusRequester = focusRequester,
                onMoveCursor = viewModel::moveCursor,
                onTapFraction = viewModel::setCursorFraction,
            )
        }
    }
}

@Composable
private fun ColumnScope.SpectrumContent(
    frame: DisplayFrame,
    compareSnapshot: SpectrumSnapshot?,
    frozen: Boolean,
    focusRequester: FocusRequester,
    onMoveCursor: (Int) -> Unit,
    onTapFraction: (Float) -> Unit,
) {
    val compatible = compareSnapshot != null &&
        compareSnapshot.sampleRate == frame.sampleRate &&
        compareSnapshot.fftSize == frame.fftSize

    val cursorFreq = frame.cursorBinIndex * frame.binWidthHz
    val cursorDb = frame.magnitudesDbfs.getOrNull(frame.cursorBinIndex) ?: DbScale.FLOOR_DBFS
    val readoutHz = frame.dominantFrequencyHz ?: cursorFreq
    val readoutDb = frame.dominantFrequencyHz?.let { frame.magnitudesDbfs.getOrNull(frame.cursorBinIndex) } ?: cursorDb

    Text(
        text = "${formatHz(readoutHz)} · ${"%.0f".format(readoutDb)} dBFS",
        style = frequencyStyle(),
        color = MaterialTheme.colorScheme.onBackground,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                onMoveCursor(if (event.verticalScrollPixels > 0) 1 else -1)
                true
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onTapFraction(offset.x / size.width) }
            },
    ) {
        SpectrumCanvas(
            frame = frame,
            compareSnapshot = compareSnapshot,
            isComparisonCompatible = compatible,
            modifier = Modifier.fillMaxSize(),
        )
        if (frozen) {
            StateBadge("◆", "HELD", StageScopeColors.Held)
        }
    }

    if (compareSnapshot != null) {
        if (compatible) {
            StateBadge("- -", "held reference", StageScopeColors.Held, modifier = Modifier)
        } else {
            Text("Incompatible comparison (rate/size differ)", style = chartAnnotationStyle(), color = StageScopeColors.Held)
        }
    }
    if (frame.isDemo) {
        Text("DEMO — synthetic signal", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "%.2f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)
