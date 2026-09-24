package com.peaceantz.stagescope.ui.analyzer

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.LastReadingSummary
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.dsp.LevelMeter
import com.peaceantz.stagescope.dsp.SpectrumAnalyzer
import com.peaceantz.stagescope.widget.notifyWatchSurfacesChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** The radial spectrum's data, already aggregated to [RadialMapping.DISPLAY_BAND_COUNT] bands. */
data class SpectrumDisplay(
    val sampleRate: Int,
    val fftSize: Int,
    /** Raw, full FFT-bin-resolution magnitudes -- kept only for Details info and snapshot saving. */
    val magnitudesDbfs: DoubleArray,
    val bands: Array<RadialMapping.BandValue>,
    val peakHoldBandsDbfs: DoubleArray,
    val nyquistHz: Double,
    val binWidthHz: Double,
    val cursorBandIndex: Int,
    /** True while Freeze is active -- the annulus stops updating; Level/Ring keep running. */
    val isHeld: Boolean,
)

data class AnalyzerReading(
    val rmsDisplayDbfs: Double,
    /** Always raw dBFS, never calibration-offset -- safe to feed into a dBFS-scaled gauge. */
    val rmsRawDbfs: Double,
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
    val spectrum: SpectrumDisplay?,
)

sealed interface AnalyzerUiState {
    data object NotStarted : AnalyzerUiState
    data object PermissionDenied : AnalyzerUiState
    data class Unavailable(val reason: String) : AnalyzerUiState
    data class Error(val message: String) : AnalyzerUiState
    data object Paused : AnalyzerUiState
    data class Measuring(val reading: AnalyzerReading) : AnalyzerUiState
}

/**
 * Owned above the pager: the combined ANALYZER page merges what used to be separate Level and
 * Spectrum ViewModels onto the ONE shared [CaptureSession], so both a steady RMS reading and a
 * radial spectrum come from the exact same audio blocks. Freeze is spectrum-only -- [LevelMeter]
 * keeps processing every block regardless of [userFrozen], per the existing "Freeze never touches
 * the shared session" contract this app already relies on for Ring.
 */
class AnalyzerViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val meter = LevelMeter()
    private val analyzer = SpectrumAnalyzer()
    private var peakHoldBands = DoubleArray(RadialMapping.DISPLAY_BAND_COUNT) { DbScale.FLOOR_DBFS }

    private val _uiState = MutableStateFlow<AnalyzerUiState>(AnalyzerUiState.NotStarted)
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    val snapshots: StateFlow<List<SpectrumSnapshot>> = container.snapshotRepository.snapshots
    private val _compareSnapshot = MutableStateFlow<SpectrumSnapshot?>(null)
    val compareSnapshot: StateFlow<SpectrumSnapshot?> = _compareSnapshot.asStateFlow()

    private var currentConfig: CaptureConfig? = null
    private var lastPublishMillis = 0L
    private var lastBlockRealtime = 0L
    private var elapsedActiveMillis = 0L
    private var silentBlockStreak = 0
    private var keepAwakeJob: kotlinx.coroutines.Job? = null

    private var userFrozen = false
    private var cursorBandIndex = RadialMapping.DISPLAY_BAND_COUNT / 3 // a useful default before any data
    private var userMovedCursor = false
    private var lastReading: AnalyzerReading? = null

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    fun start() {
        lastBlockRealtime = 0L
        userFrozen = false
        userMovedCursor = false
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
        clearPeakHold()
    }

    /** Stops the radial spectrum from ingesting new frames; Level/Ring keep running unaffected. */
    fun freeze() {
        userFrozen = true
        republish()
    }

    fun resume() {
        userFrozen = false
        if (!session.isRunning) session.start()
        republish()
    }

    fun moveCursor(deltaBands: Int) {
        userMovedCursor = true
        cursorBandIndex = (cursorBandIndex + deltaBands).coerceIn(0, RadialMapping.DISPLAY_BAND_COUNT - 1)
        republish()
    }

    /** [arcFraction] is 0..1 along the arc, as resolved from a tap's angle by the screen/canvas. */
    fun setCursorArcFraction(arcFraction: Float) {
        userMovedCursor = true
        cursorBandIndex = RadialMapping.bandForFraction(arcFraction)
        republish()
    }

    fun clearPeakHold() {
        peakHoldBands = DoubleArray(RadialMapping.DISPLAY_BAND_COUNT) { DbScale.FLOOR_DBFS }
    }

    fun saveSnapshot() {
        val spectrum = lastReading?.spectrum ?: return
        val config = currentConfig ?: return
        val calibration = container.settingsRepository.settings.value.calibration
        val isCalibrated = calibration != null && calibration.configFingerprint == config.fingerprint()
        val name = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val snapshot = SpectrumSnapshot(
            id = UUID.randomUUID().toString(),
            name = name,
            timestampMillis = System.currentTimeMillis(),
            sampleRate = spectrum.sampleRate,
            fftSize = spectrum.fftSize,
            sourceLabel = config.sourceLabel,
            isDemo = config.isDemo,
            magnitudesDbfs = spectrum.magnitudesDbfs.toList(),
            nyquistHz = spectrum.nyquistHz,
            binWidthHz = spectrum.binWidthHz,
            calibrationApplied = isCalibrated,
            calibrationOffsetDb = if (isCalibrated) calibration!!.offsetDb else null,
        )
        viewModelScope.launch { container.snapshotRepository.save(snapshot) }
    }

    fun renameSnapshot(id: String, newName: String) {
        viewModelScope.launch { container.snapshotRepository.rename(id, newName) }
    }

    fun deleteSnapshot(id: String) {
        if (_compareSnapshot.value?.id == id) _compareSnapshot.value = null
        viewModelScope.launch { container.snapshotRepository.delete(id) }
    }

    fun setCompareSnapshot(id: String?) {
        _compareSnapshot.value = snapshots.value.firstOrNull { it.id == id }
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
            CaptureStatus.PermissionDenied -> _uiState.value = AnalyzerUiState.PermissionDenied
            is CaptureStatus.Unavailable -> _uiState.value = AnalyzerUiState.Unavailable(status.reason)
            is CaptureStatus.Error -> _uiState.value = AnalyzerUiState.Error(status.message)
            CaptureStatus.Stopped -> {
                keepAwakeJob?.cancel()
                persistLastReadingIfMeasuring()
                _uiState.value = AnalyzerUiState.NotStarted
            }
            CaptureStatus.Idle -> Unit
        }
    }

    private fun persistLastReadingIfMeasuring() {
        val reading = (_uiState.value as? AnalyzerUiState.Measuring)?.reading ?: return
        if (reading.isDemo) return
        viewModelScope.launch {
            container.surfaceSummaryRepository.setLastReading(
                LastReadingSummary(
                    rmsDisplayDbfs = reading.rmsDisplayDbfs,
                    unitLabel = reading.unitLabel,
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

        if (!userFrozen) analyzer.pushSamples(block)

        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now

        val settings = container.settingsRepository.settings.value
        val calibration = settings.calibration
        val isCalibrated = calibration != null && calibration.configFingerprint == config.fingerprint()
        val offset = if (isCalibrated) calibration!!.offsetDb else 0.0

        val spectrum = buildSpectrumDisplay(config)

        val reading = AnalyzerReading(
            rmsDisplayDbfs = result.rmsDbfs + offset,
            rmsRawDbfs = result.rmsDbfs,
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
            keepAwakeRemainingSeconds = keepAwakeRemaining,
            spectrum = spectrum ?: lastReading?.spectrum,
        )
        lastReading = reading
        _uiState.value = AnalyzerUiState.Measuring(reading)
    }

    /** Returns null (keep showing the previous frame) while frozen or before the first full frame. */
    private fun buildSpectrumDisplay(config: CaptureConfig): SpectrumDisplay? {
        if (userFrozen) return null
        val frame = analyzer.computeFrame(config.sampleRate) ?: return null

        val bands = RadialMapping.aggregateBands(frame.magnitudesDbfs)
        for (i in bands.indices) {
            peakHoldBands[i] = maxOf(bands[i].magnitudeDbfs, peakHoldBands[i] - PEAK_HOLD_DECAY_DB)
        }
        if (!userMovedCursor) {
            cursorBandIndex = bands.indices.maxByOrNull { bands[it].magnitudeDbfs } ?: cursorBandIndex
        }

        return SpectrumDisplay(
            sampleRate = frame.sampleRate,
            fftSize = frame.fftSize,
            magnitudesDbfs = frame.magnitudesDbfs,
            bands = bands,
            peakHoldBandsDbfs = peakHoldBands.copyOf(),
            nyquistHz = frame.nyquistHz,
            binWidthHz = frame.binWidthHz,
            cursorBandIndex = cursorBandIndex,
            isHeld = userFrozen,
        )
    }

    /** Re-publishes the current reading after a cursor move or freeze/resume, without new audio. */
    private fun republish() {
        val current = lastReading ?: return
        val spectrum = current.spectrum?.copy(cursorBandIndex = cursorBandIndex, isHeld = userFrozen)
        val updated = current.copy(spectrum = spectrum)
        lastReading = updated
        if (_uiState.value is AnalyzerUiState.Measuring) {
            _uiState.value = AnalyzerUiState.Measuring(updated)
        }
    }

    private val keepAwakeRemaining: Int get() = _keepAwakeRemainingSeconds.value
    private val _keepAwakeRemainingSeconds = MutableStateFlow(0)

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
        private const val PEAK_HOLD_DECAY_DB = 0.3
        private const val SILENT_STREAK_THRESHOLD = 20
        private const val KEEP_AWAKE_SECONDS = 120
    }
}
