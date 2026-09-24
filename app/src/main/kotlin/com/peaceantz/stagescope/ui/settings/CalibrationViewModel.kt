package com.peaceantz.stagescope.ui.settings

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.CalibrationState
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.dsp.LevelMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CalibrationUiState {
    data object NotStarted : CalibrationUiState
    data object PermissionDenied : CalibrationUiState
    data class Unavailable(val reason: String) : CalibrationUiState
    data class Error(val message: String) : CalibrationUiState
    data object Paused : CalibrationUiState
    data class Measuring(val liveRawRmsDbfs: Double, val sourceLabel: String, val isDemo: Boolean) : CalibrationUiState
}

/**
 * Manual reference-alignment flow, reusing the shared [CaptureSession] (Level/Spectrum/Ring's
 * session) rather than opening a second AudioRecord: play a steady, unweighted tone/noise, read
 * the SPL off a trusted meter, dial that number in here, and Confirm stores
 * offsetDb = target - liveRawRmsDbfs alongside the exact input configuration.
 */
class CalibrationViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val meter = LevelMeter()

    private val _uiState = MutableStateFlow<CalibrationUiState>(CalibrationUiState.NotStarted)
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    private val _targetDb = MutableStateFlow(70.0)
    val targetDb: StateFlow<Double> = _targetDb.asStateFlow()

    private var currentConfig: CaptureConfig? = null
    private var lastPublishMillis = 0L
    private var lastRawRmsDbfs = DbScale.FLOOR_DBFS

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    fun start() {
        meter.reset()
        session.start()
    }

    fun stop() = session.stop()

    fun adjustTarget(deltaDb: Double) {
        _targetDb.value = (_targetDb.value + deltaDb).coerceIn(30.0, 130.0)
    }

    fun confirmCalibration() {
        val config = currentConfig ?: return
        val offset = _targetDb.value - lastRawRmsDbfs
        viewModelScope.launch {
            container.settingsRepository.setCalibration(
                CalibrationState(
                    offsetDb = offset,
                    referenceMeterReadingDb = _targetDb.value,
                    configFingerprint = config.fingerprint(),
                    timestampMillis = System.currentTimeMillis(),
                )
            )
        }
        stop()
    }

    override fun onCleared() {
        session.removeListener(listener)
        super.onCleared()
    }

    private fun handleStatus(status: CaptureStatus) {
        when (status) {
            is CaptureStatus.Running -> currentConfig = status.config
            CaptureStatus.PermissionDenied -> _uiState.value = CalibrationUiState.PermissionDenied
            is CaptureStatus.Unavailable -> _uiState.value = CalibrationUiState.Unavailable(status.reason)
            is CaptureStatus.Error -> _uiState.value = CalibrationUiState.Error(status.message)
            CaptureStatus.Stopped -> _uiState.value = CalibrationUiState.NotStarted
            CaptureStatus.Idle -> Unit
        }
    }

    private fun onBlock(block: FloatArray) {
        val config = currentConfig ?: return
        val result = meter.processBlock(block)
        lastRawRmsDbfs = result.rmsDbfs

        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now

        _uiState.value = CalibrationUiState.Measuring(
            liveRawRmsDbfs = result.rmsDbfs,
            sourceLabel = config.sourceLabel,
            isDemo = config.isDemo,
        )
    }

    companion object {
        private const val PUBLISH_INTERVAL_MILLIS = 100L
    }
}
