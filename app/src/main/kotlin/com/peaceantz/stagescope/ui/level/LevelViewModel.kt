package com.peaceantz.stagescope.ui.level

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.LastReadingSummary
import com.peaceantz.stagescope.dsp.LevelMeter
import com.peaceantz.stagescope.widget.notifyWatchSurfacesChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class LevelMeasurement(
    val rmsDisplayDbfs: Double,
    val peakDbfs: Double,
    val maxDisplayDbfs: Double,
    val sessionAverageDisplayDbfs: Double,
    val elapsedSeconds: Long,
    val isClippingNow: Boolean,
    val hasClippedEver: Boolean,
    val unitLabel: String,
    val isCalibrated: Boolean,
    val isDemo: Boolean,
    val isSuspiciousSilence: Boolean,
    val sourceLabel: String,
    val keepAwakeRemainingSeconds: Int,
)

sealed interface LevelUiState {
    data object NotStarted : LevelUiState
    data object PermissionDenied : LevelUiState
    data class Unavailable(val reason: String) : LevelUiState
    data class Error(val message: String) : LevelUiState
    data object Paused : LevelUiState
    data class Measuring(val measurement: LevelMeasurement) : LevelUiState
}

/** Owned above the pager: registers on the shared [CaptureSession] and survives page swipes. */
class LevelViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val meter = LevelMeter()

    private val _uiState = MutableStateFlow<LevelUiState>(LevelUiState.NotStarted)
    val uiState: StateFlow<LevelUiState> = _uiState.asStateFlow()

    private val _keepAwakeRemainingSeconds = MutableStateFlow(0)

    private var currentConfig: CaptureConfig? = null
    private var lastPublishMillis = 0L
    private var lastBlockRealtime = 0L
    private var elapsedActiveMillis = 0L
    private var silentBlockStreak = 0
    private var keepAwakeJob: kotlinx.coroutines.Job? = null

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    fun start() {
        lastBlockRealtime = 0L
        session.start()
        startKeepAwakeCountdown()
    }

    fun stop() {
        session.stop()
        keepAwakeJob?.cancel()
    }

    fun reset() {
        meter.reset()
        elapsedActiveMillis = 0L
        silentBlockStreak = 0
    }

    override fun onCleared() {
        session.removeListener(listener)
        super.onCleared()
    }

    private fun handleStatus(status: CaptureStatus) {
        when (status) {
            is CaptureStatus.Running -> {
                currentConfig = status.config
                viewModelScope.launch {
                    container.settingsRepository.invalidateCalibrationIfMismatched(status.config.fingerprint())
                }
            }
            CaptureStatus.PermissionDenied -> _uiState.value = LevelUiState.PermissionDenied
            is CaptureStatus.Unavailable -> _uiState.value = LevelUiState.Unavailable(status.reason)
            is CaptureStatus.Error -> _uiState.value = LevelUiState.Error(status.message)
            CaptureStatus.Stopped -> {
                keepAwakeJob?.cancel()
                persistLastReadingIfMeasuring()
                _uiState.value = LevelUiState.NotStarted
            }
            CaptureStatus.Idle -> Unit
        }
    }

    /**
     * Writes the last RMS reading to [com.peaceantz.stagescope.data.SurfaceSummaryRepository] for
     * the Tile/complication -- only at Stop (a "meaningful event", not per audio frame), and never
     * for a demo-mode reading (demo data must never look like a real saved measurement).
     */
    private fun persistLastReadingIfMeasuring() {
        val measurement = (_uiState.value as? LevelUiState.Measuring)?.measurement ?: return
        if (measurement.isDemo) return
        viewModelScope.launch {
            container.surfaceSummaryRepository.setLastReading(
                LastReadingSummary(
                    rmsDisplayDbfs = measurement.rmsDisplayDbfs,
                    unitLabel = measurement.unitLabel,
                    timestampMillis = System.currentTimeMillis(),
                )
            )
            notifyWatchSurfacesChanged(container.appContext)
        }
    }

    private fun onBlock(block: FloatArray) {
        val config = currentConfig ?: return
        val result = meter.processBlock(block)

        val now = SystemClock.elapsedRealtime()
        if (lastBlockRealtime != 0L) {
            elapsedActiveMillis += (now - lastBlockRealtime).coerceAtMost(500L)
        }
        lastBlockRealtime = now

        val trueZero = block.all { abs(it) < 1e-6f }
        silentBlockStreak = if (trueZero) silentBlockStreak + 1 else 0

        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now

        val settings = container.settingsRepository.settings.value
        val calibration = settings.calibration
        val isCalibrated = calibration != null && calibration.configFingerprint == config.fingerprint()
        val offset = if (isCalibrated) calibration!!.offsetDb else 0.0

        _uiState.value = LevelUiState.Measuring(
            LevelMeasurement(
                rmsDisplayDbfs = result.rmsDbfs + offset,
                peakDbfs = result.peakDbfs,
                maxDisplayDbfs = meter.maxRmsDbfsSoFar() + offset,
                sessionAverageDisplayDbfs = meter.sessionEnergyAverageDbfs() + offset,
                elapsedSeconds = elapsedActiveMillis / 1000,
                isClippingNow = result.clipped,
                hasClippedEver = meter.hasClippedSinceReset(),
                unitLabel = if (isCalibrated) "Estimated SPL" else "dBFS",
                isCalibrated = isCalibrated,
                isDemo = config.isDemo,
                isSuspiciousSilence = silentBlockStreak >= SILENT_STREAK_THRESHOLD,
                sourceLabel = config.sourceLabel,
                keepAwakeRemainingSeconds = _keepAwakeRemainingSeconds.value,
            )
        )
    }

    private fun startKeepAwakeCountdown() {
        keepAwakeJob?.cancel()
        _keepAwakeRemainingSeconds.value = KEEP_AWAKE_SECONDS
        keepAwakeJob = viewModelScope.launch {
            var remaining = KEEP_AWAKE_SECONDS
            while (remaining > 0) {
                delay(1000)
                remaining--
                _keepAwakeRemainingSeconds.value = remaining
            }
            stop()
        }
    }

    companion object {
        private const val PUBLISH_INTERVAL_MILLIS = 100L
        private const val SILENT_STREAK_THRESHOLD = 20
        private const val KEEP_AWAKE_SECONDS = 120
    }
}
