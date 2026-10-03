package com.peaceantz.stagescope.ui.analyzer

import androidx.lifecycle.ViewModel
import com.peaceantz.stagescope.dsp.TapTempo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class TapTempoState(
    val estimate: TapTempo.Estimate? = null,
    /** Every counted tap bumps this; a change is what flashes the readout, so a person sees each beat land. */
    val beats: Long = 0,
    /** True while a double pinch on the Analyzer page counts as a beat. */
    val gestureArmed: Boolean = false,
)

/**
 * Tap tempo for the Analyzer, scoped above the pager like [com.peaceantz.stagescope.ui.rotation.OrientationViewModel]
 * so the Analyzer page (double pinch) and Analyzer details (touch pad, Clear) share one tempo. Nothing to do with the
 * audio session: tapping never touches the microphone, and stopping measurement keeps the last tempo on screen.
 */
class TapTempoViewModel : ViewModel() {
    private val tempo = TapTempo()
    private val _state = MutableStateFlow(TapTempoState())
    val state: StateFlow<TapTempoState> = _state.asStateFlow()

    /** [atUptimeMillis] is the beat's own timestamp (touch down, or the gesture's event time), not when it reached us. */
    fun tap(atUptimeMillis: Long) {
        if (!tempo.tap(atUptimeMillis)) return
        _state.update { it.copy(estimate = tempo.estimate, beats = it.beats + 1) }
    }

    fun clear() {
        tempo.clear()
        _state.update { it.copy(estimate = null) }
    }

    fun setGestureArmed(armed: Boolean) {
        _state.update { it.copy(gestureArmed = armed) }
    }
}

/** Pure wording for the tempo readouts -- see `TempoFormattingTest`. */
object TempoFormatting {
    /** Next to the clock on the Analyzer page: whole BPM once there is a tempo, a placeholder while a pinch would count. */
    fun timeTextLabel(state: TapTempoState): String? = when {
        state.estimate != null -> "${state.estimate.bpm.roundToInt()} BPM"
        state.gestureArmed -> "-- BPM"
        else -> null
    }

    fun bpm(estimate: TapTempo.Estimate): String = "%.1f BPM".format(estimate.bpm)

    fun detail(estimate: TapTempo.Estimate): String =
        "${"%.0f".format(estimate.beatMillis)} ms/beat · ${estimate.beatCount} taps"
}
