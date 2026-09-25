package com.peaceantz.stagescope.ui.rotation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One shared crown-driven rotation angle for the whole app -- the Analyzer/Ring pages and every
 * secondary screen reached from them (Details, Calibration, Appearance, Snapshots, Ring Details)
 * all read the same value, scoped to the `"main"` nav backstack entry (see
 * StageScopeNavHost) so it survives page swipes and pushed routes exactly like [CaptureSession][
 * com.peaceantz.stagescope.audio.CaptureSession] does.
 *
 * [angleDegrees] is kept UNWRAPPED (see [OrientationMath]) so the live `rotationZ` transform never
 * jumps at the 0/360 seam; only the persisted, settled value is normalized. Persistence is
 * debounced -- a burst of crown ticks only writes `settings.json` once, [SETTLE_DELAY_MILLIS] after
 * the crown stops moving -- never per individual event.
 */
class OrientationViewModel(private val container: AppContainer) : ViewModel() {

    private val _angleDegrees = MutableStateFlow(
        container.settingsRepository.settings.value.instrumentOrientationDegrees
    )
    val angleDegrees: StateFlow<Float> = _angleDegrees.asStateFlow()

    private val _locked = MutableStateFlow(
        container.settingsRepository.settings.value.instrumentOrientationLocked
    )
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var settleJob: Job? = null

    /** Ignored while [locked] -- a locked orientation simply stops responding to the crown. */
    fun onRotaryDelta(rotaryScrollPixels: Float) {
        if (_locked.value) return
        _angleDegrees.value = OrientationMath.applyDelta(_angleDegrees.value, rotaryScrollPixels)
        scheduleSettle()
    }

    fun reset() {
        _angleDegrees.value = 0f
        scheduleSettle()
    }

    fun setLocked(locked: Boolean) {
        _locked.value = locked
        viewModelScope.launch { container.settingsRepository.setInstrumentOrientationLocked(locked) }
    }

    private fun scheduleSettle() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(SETTLE_DELAY_MILLIS)
            container.settingsRepository.setInstrumentOrientation(OrientationMath.normalize(_angleDegrees.value))
        }
    }

    override fun onCleared() {
        settleJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val SETTLE_DELAY_MILLIS = 800L
    }
}
