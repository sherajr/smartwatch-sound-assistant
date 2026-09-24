package com.peaceantz.stagescope.ui.settings

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.CalibrationState
import com.peaceantz.stagescope.dsp.CalibrationSampleAccumulator
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.dsp.LevelMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CalibrationStep { PREPARE, ENTER_REFERENCE, MEASURE, REVIEW, SUCCESS }

sealed interface CaptureAvailability {
    data object NotStarted : CaptureAvailability
    data object PermissionDenied : CaptureAvailability
    data class Unavailable(val reason: String) : CaptureAvailability
    data class Error(val message: String) : CaptureAvailability
    data object Running : CaptureAvailability
}

data class SamplingProgress(val elapsedMs: Long, val totalMs: Long, val liveRawRmsDbfs: Double, val clippedSoFar: Boolean)

/** One completed ~3s measurement window's result -- energy-averaged once, never dB-averaged. */
data class MeasurementResult(
    val averagedRawRmsDbfs: Double,
    val blockSpreadDb: Double,
    val clippedDuringWindow: Boolean,
    val completedAtElapsedRealtime: Long,
    val configFingerprintAtCompletion: String,
    val isDemoAtCompletion: Boolean,
)

sealed interface CalibrationValidity {
    data object Valid : CalibrationValidity
    data class Invalid(val reason: String) : CalibrationValidity
}

/**
 * Guided reference-alignment flow (Prepare -> Enter reference -> Measure -> Review/Save), reusing
 * the shared [CaptureSession] rather than opening a second microphone. The only number this ever
 * derives from a single latest block is the live "still listening" badge during Prepare/Enter --
 * the actual calibration math ([MeasurementResult.averagedRawRmsDbfs]) always comes from
 * energy-averaging a full ~3s window and converting to dB exactly once (see [measureReference]).
 */
class CalibrationViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val liveMeter = LevelMeter()

    private val _availability = MutableStateFlow<CaptureAvailability>(CaptureAvailability.NotStarted)
    val availability: StateFlow<CaptureAvailability> = _availability.asStateFlow()

    private val _step = MutableStateFlow(CalibrationStep.PREPARE)
    val step: StateFlow<CalibrationStep> = _step.asStateFlow()

    private val _liveRawRmsDbfs = MutableStateFlow(DbScale.FLOOR_DBFS)
    val liveRawRmsDbfs: StateFlow<Double> = _liveRawRmsDbfs.asStateFlow()

    private val _isDemoActive = MutableStateFlow(false)
    val isDemoActive: StateFlow<Boolean> = _isDemoActive.asStateFlow()

    private val _targetDb = MutableStateFlow(DEFAULT_EXAMPLE_TARGET_DB)
    val targetDb: StateFlow<Double> = _targetDb.asStateFlow()

    /** True once the user has explicitly touched/confirmed [targetDb] -- an untouched example
     *  value must never be silently saved as if it were a real meter reading. */
    private val _referenceTouched = MutableStateFlow(false)
    val referenceTouched: StateFlow<Boolean> = _referenceTouched.asStateFlow()

    private val _sampling = MutableStateFlow<SamplingProgress?>(null)
    val sampling: StateFlow<SamplingProgress?> = _sampling.asStateFlow()

    private val _measurement = MutableStateFlow<MeasurementResult?>(null)
    val measurement: StateFlow<MeasurementResult?> = _measurement.asStateFlow()

    private var currentConfig: CaptureConfig? = null
    private var isSampling = false
    private var samplingStartElapsed = 0L
    private var accumulator = CalibrationSampleAccumulator()

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    /** Called each time the guided flow is (re)entered -- never resumes a stale mid-flow state. */
    fun resetToStart() {
        _step.value = CalibrationStep.PREPARE
        _targetDb.value = DEFAULT_EXAMPLE_TARGET_DB
        _referenceTouched.value = false
        _measurement.value = null
        _sampling.value = null
        isSampling = false
    }

    fun start() = session.start()

    fun goToEnterReference() {
        _step.value = CalibrationStep.ENTER_REFERENCE
    }

    fun goToMeasure() {
        _step.value = CalibrationStep.MEASURE
    }

    fun backToPrepare() {
        _step.value = CalibrationStep.PREPARE
    }

    fun backToEnterReference() {
        _measurement.value = null
        _step.value = CalibrationStep.ENTER_REFERENCE
    }

    fun adjustTarget(deltaDb: Double) {
        _targetDb.value = (_targetDb.value + deltaDb).coerceIn(30.0, 130.0)
        _referenceTouched.value = true
    }

    /** Explicit confirmation that the shown example value IS the real meter reading, verbatim. */
    fun confirmShownValueIsMeasured() {
        _referenceTouched.value = true
    }

    fun measureReference() {
        if (currentConfig == null || isSampling) return
        samplingStartElapsed = SystemClock.elapsedRealtime()
        accumulator = CalibrationSampleAccumulator()
        isSampling = true
        _measurement.value = null
        _sampling.value = SamplingProgress(0L, MEASURE_DURATION_MS, DbScale.FLOOR_DBFS, clippedSoFar = false)
    }

    /** Sends the flow back to the Measure step (a real step transition, not just a field reset) so
     *  the Review screen's own recomposition -- which is keyed on [step] -- reliably updates. */
    fun retryMeasurement() {
        _measurement.value = null
        isSampling = false
        _sampling.value = null
        _step.value = CalibrationStep.MEASURE
    }

    /** Re-checked here (not just at measurement time) so a change since completion is still caught. */
    fun currentValidity(): CalibrationValidity {
        val m = _measurement.value ?: return CalibrationValidity.Invalid("No completed measurement yet")
        val config = currentConfig ?: return CalibrationValidity.Invalid("Not currently measuring")
        return when {
            config.isDemo || m.isDemoAtCompletion ->
                CalibrationValidity.Invalid("Measured in Demo mode — turn Demo off and measure a real reference")
            config.fingerprint() != m.configFingerprintAtCompletion ->
                CalibrationValidity.Invalid("Microphone configuration changed since measuring — measure again")
            m.clippedDuringWindow ->
                CalibrationValidity.Invalid("Signal clipped during measurement — lower the level and retry")
            m.blockSpreadDb > STABILITY_THRESHOLD_DB ->
                CalibrationValidity.Invalid("Signal too variable — hold a steadier tone or noise and retry")
            SystemClock.elapsedRealtime() - m.completedAtElapsedRealtime > STALE_MS ->
                CalibrationValidity.Invalid("Measurement is stale — measure again")
            !_referenceTouched.value ->
                CalibrationValidity.Invalid("Confirm the reference meter reading first")
            else -> CalibrationValidity.Valid
        }
    }

    fun confirmSave() {
        val validity = currentValidity()
        if (validity !is CalibrationValidity.Valid) return
        val m = _measurement.value ?: return
        val config = currentConfig ?: return
        val offset = _targetDb.value - m.averagedRawRmsDbfs
        viewModelScope.launch {
            container.settingsRepository.setCalibration(
                CalibrationState(
                    offsetDb = offset,
                    referenceMeterReadingDb = _targetDb.value,
                    configFingerprint = config.fingerprint(),
                    timestampMillis = System.currentTimeMillis(),
                )
            )
            _step.value = CalibrationStep.SUCCESS
        }
    }

    fun offsetPreview(): Double? {
        val m = _measurement.value ?: return null
        return _targetDb.value - m.averagedRawRmsDbfs
    }

    override fun onCleared() {
        session.removeListener(listener)
        super.onCleared()
    }

    private fun handleStatus(status: CaptureStatus) {
        when (status) {
            is CaptureStatus.Running -> {
                currentConfig = status.config
                _isDemoActive.value = status.config.isDemo
                _availability.value = CaptureAvailability.Running
            }
            CaptureStatus.PermissionDenied -> {
                abortSampling()
                _availability.value = CaptureAvailability.PermissionDenied
            }
            is CaptureStatus.Unavailable -> {
                abortSampling()
                _availability.value = CaptureAvailability.Unavailable(status.reason)
            }
            is CaptureStatus.Error -> {
                abortSampling()
                _availability.value = CaptureAvailability.Error(status.message)
            }
            CaptureStatus.Stopped -> {
                abortSampling()
                currentConfig = null
                _availability.value = CaptureAvailability.NotStarted
            }
            CaptureStatus.Idle -> Unit
        }
    }

    /** A dropout/stop mid-window must not silently finalize a partial average as if it were valid. */
    private fun abortSampling() {
        if (isSampling) {
            isSampling = false
            _sampling.value = null
        }
    }

    private fun onBlock(block: FloatArray) {
        val config = currentConfig ?: return
        val result = liveMeter.processBlock(block)
        _liveRawRmsDbfs.value = result.rmsDbfs

        if (!isSampling) return

        accumulator.accumulate(block)

        val elapsed = SystemClock.elapsedRealtime() - samplingStartElapsed
        _sampling.value = SamplingProgress(elapsed.coerceAtMost(MEASURE_DURATION_MS), MEASURE_DURATION_MS, result.rmsDbfs, accumulator.clipped)

        if (elapsed >= MEASURE_DURATION_MS) {
            isSampling = false
            _sampling.value = null
            _measurement.value = MeasurementResult(
                averagedRawRmsDbfs = accumulator.averagedRmsDbfs(),
                blockSpreadDb = accumulator.blockSpreadDb(),
                clippedDuringWindow = accumulator.clipped,
                completedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                configFingerprintAtCompletion = config.fingerprint(),
                isDemoAtCompletion = config.isDemo,
            )
            _step.value = CalibrationStep.REVIEW
        }
    }

    companion object {
        const val DEFAULT_EXAMPLE_TARGET_DB = 70.0
        const val MEASURE_DURATION_MS = 3_000L
        const val STABILITY_THRESHOLD_DB = 6.0
        const val STALE_MS = 90_000L
    }
}
