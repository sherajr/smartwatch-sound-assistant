package com.peaceantz.stagescope.ui.analyzer

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.peaceantz.stagescope.input.PrimaryGestureSource
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.compactReadoutStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val GESTURE_ID = "stagescope.analyzer.tap_tempo"
private const val BEAT_FLASH_MILLIS = 150L

/**
 * While [isAnalyzerPage] and the Analyzer is measuring, a double pinch taps the beat. Measuring is the "play" state the
 * person asked for, and it is also what keeps the screen on -- the watch never delivers the gesture to a dark or
 * ambient screen. No UI of its own; the readout is [TapTempoTimeText].
 */
@Composable
fun TapTempoGestureEffect(
    gestures: PrimaryGestureSource,
    analyzer: AnalyzerViewModel,
    tempo: TapTempoViewModel,
    isAnalyzerPage: Boolean,
) {
    // Mapped to a Boolean first so this doesn't recompose with every ~10 Hz reading.
    val isMeasuring by remember(analyzer) {
        analyzer.uiState.map { it is AnalyzerUiState.Measuring }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val enabled = isAnalyzerPage && isMeasuring
    val view = LocalView.current
    DisposableEffect(enabled, gestures, view, tempo) {
        val subscription = if (enabled) gestures.subscribe(view, GESTURE_ID, tempo::tap) else null
        tempo.setGestureArmed(subscription != null)
        onDispose {
            subscription?.close()
            tempo.setGestureArmed(false)
        }
    }
}

/**
 * The system time at the top, plus -- on the Analyzer page -- the tempo beside it ("10:42 · 120 BPM"). The curved time
 * text is the one spot on the round dial that's free: the bottom gap holds the two action buttons and the center is at
 * its five-line budget. The label lights up in the live color for a moment on each counted beat.
 */
@Composable
fun TapTempoTimeText(tempo: TapTempoViewModel, isAnalyzerPage: Boolean) {
    val state by tempo.state.collectAsStateWithLifecycle()
    val label = if (isAnalyzerPage) TempoFormatting.timeTextLabel(state) else null
    val flashing = rememberBeatFlash(state.beats)
    val flashStyle = TimeTextDefaults.timeTextStyle(color = LocalStageScopePalette.current.Live)
    TimeText { time ->
        timeTextCurvedText(time)
        if (label != null) {
            timeTextSeparator()
            timeTextCurvedText(label, if (flashing) flashStyle else null)
        }
    }
}

/**
 * The touch way to tap the beat (Analyzer details), for watches without the gesture or with it switched off. The beat
 * is timed at the finger's touch-down, not the lift, so how long the finger rests doesn't add jitter; a drag that
 * becomes a scroll of the list doesn't count.
 */
@Composable
fun TapTempoPad(state: TapTempoState, onTap: (atUptimeMillis: Long) -> Unit, modifier: Modifier = Modifier) {
    val currentOnTap by rememberUpdatedState(onTap)
    val flashing = rememberBeatFlash(state.beats)
    val estimate = state.estimate
    val heading = if (estimate != null) TempoFormatting.bpm(estimate) else "Tap the beat"
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(percent = 50))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (waitForUpOrCancellation() != null) currentOnTap(down.uptimeMillis)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = if (estimate != null) "Tap the beat. Tempo ${TempoFormatting.bpm(estimate)}" else "Tap the beat"
                onClick {
                    currentOnTap(SystemClock.uptimeMillis())
                    true
                }
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = heading,
                color = if (flashing) LocalStageScopePalette.current.Live else MaterialTheme.colorScheme.onSurface,
            )
            if (estimate != null) {
                Text(
                    text = TempoFormatting.detail(estimate),
                    style = compactReadoutStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** True for a moment after [beats] changes -- not on first composition, so returning to a screen doesn't flash. */
@Composable
private fun rememberBeatFlash(beats: Long): Boolean {
    val seenOnEntry = remember { beats }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(beats) {
        if (beats == seenOnEntry) return@LaunchedEffect
        flashing = true
        delay(BEAT_FLASH_MILLIS)
        flashing = false
    }
    return flashing
}
