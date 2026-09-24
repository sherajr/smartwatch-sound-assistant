package com.peaceantz.stagescope.ui.spectrum

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.SpectrumSnapshot
import com.peaceantz.stagescope.dsp.DbScale
import com.peaceantz.stagescope.dsp.FrequencyBands
import com.peaceantz.stagescope.dsp.SpectrumAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DisplayFrame(
    val sampleRate: Int,
    val fftSize: Int,
    val magnitudesDbfs: DoubleArray,
    val peakHoldDbfs: DoubleArray,
    val dominantFrequencyHz: Double?,
    val nyquistHz: Double,
    val binWidthHz: Double,
    val cursorBinIndex: Int,
    val sourceLabel: String,
    val isDemo: Boolean,
)

sealed interface SpectrumUiState {
    data object NotStarted : SpectrumUiState
    data object PermissionDenied : SpectrumUiState
    data class Unavailable(val reason: String) : SpectrumUiState
    data class Error(val message: String) : SpectrumUiState
    data object Paused : SpectrumUiState
    data class Measuring(val frame: DisplayFrame) : SpectrumUiState
    data class Frozen(val frame: DisplayFrame) : SpectrumUiState
}

/** Owned above the pager: registers on the shared [CaptureSession]; Freeze is local-only. */
class SpectrumViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val analyzer = SpectrumAnalyzer()
    private var peakHold: DoubleArray = DoubleArray(analyzer.fftSize / 2 + 1) { DbScale.FLOOR_DBFS }

    // A useful in-range default (~1 kHz) rather than the first near-DC bin.
    private var cursorBinIndex = (DEFAULT_CURSOR_HZ / (48000.0 / analyzer.fftSize)).toInt().coerceAtLeast(1)

    private val _uiState = MutableStateFlow<SpectrumUiState>(SpectrumUiState.NotStarted)
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    val snapshots: StateFlow<List<SpectrumSnapshot>> = container.snapshotRepository.snapshots
    private val _compareSnapshot = MutableStateFlow<SpectrumSnapshot?>(null)
    val compareSnapshot: StateFlow<SpectrumSnapshot?> = _compareSnapshot.asStateFlow()

    private var currentConfig: CaptureConfig? = null
    private var lastPublishMillis = 0L
    private var userFrozen = false
    private var lastFrame: DisplayFrame? = null

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    fun start() = session.start()

    fun stop() = session.stop()

    /** Stops THIS page's analyzer from ingesting new blocks; the shared session keeps running. */
    fun freeze() {
        userFrozen = true
        lastFrame?.let { _uiState.value = SpectrumUiState.Frozen(it) }
    }

    fun resume() {
        userFrozen = false
        if (!session.isRunning) session.start()
    }

    fun moveCursor(deltaBins: Int) {
        val maxBin = analyzer.fftSize / 2
        cursorBinIndex = (cursorBinIndex + deltaBins).coerceIn(1, maxBin)
        republishCurrentFrame()
    }

    fun setCursorFraction(fraction: Float) {
        val maxBin = analyzer.fftSize / 2
        val logMin = kotlin.math.ln(1.0)
        val logMax = kotlin.math.ln(maxBin.toDouble())
        val logBin = logMin + fraction.coerceIn(0f, 1f) * (logMax - logMin)
        cursorBinIndex = kotlin.math.exp(logBin).toInt().coerceIn(1, maxBin)
        republishCurrentFrame()
    }

    fun clearPeakHold() {
        peakHold = DoubleArray(analyzer.fftSize / 2 + 1) { DbScale.FLOOR_DBFS }
    }

    fun saveSnapshot() {
        val frame = lastFrame ?: return
        val config = currentConfig ?: return
        val calibration = container.settingsRepository.settings.value.calibration
        val isCalibrated = calibration != null && calibration.configFingerprint == config.fingerprint()
        val name = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val snapshot = SpectrumSnapshot(
            id = UUID.randomUUID().toString(),
            name = name,
            timestampMillis = System.currentTimeMillis(),
            sampleRate = frame.sampleRate,
            fftSize = frame.fftSize,
            sourceLabel = frame.sourceLabel,
            isDemo = frame.isDemo,
            magnitudesDbfs = frame.magnitudesDbfs.toList(),
            nyquistHz = frame.nyquistHz,
            binWidthHz = frame.binWidthHz,
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
            is CaptureStatus.Running -> currentConfig = status.config
            CaptureStatus.PermissionDenied -> _uiState.value = SpectrumUiState.PermissionDenied
            is CaptureStatus.Unavailable -> _uiState.value = SpectrumUiState.Unavailable(status.reason)
            is CaptureStatus.Error -> _uiState.value = SpectrumUiState.Error(status.message)
            CaptureStatus.Stopped -> {
                if (userFrozen) {
                    lastFrame?.let { _uiState.value = SpectrumUiState.Frozen(it) }
                } else {
                    _uiState.value = SpectrumUiState.Paused
                }
            }
            CaptureStatus.Idle -> Unit
        }
    }

    private fun onBlock(block: FloatArray) {
        if (userFrozen) return
        val config = currentConfig ?: return
        analyzer.pushSamples(block)
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        val frame = analyzer.computeFrame(config.sampleRate) ?: return
        lastPublishMillis = now

        for (i in frame.magnitudesDbfs.indices) {
            peakHold[i] = maxOf(frame.magnitudesDbfs[i], peakHold[i] - PEAK_HOLD_DECAY_DB)
        }

        val display = DisplayFrame(
            sampleRate = frame.sampleRate,
            fftSize = frame.fftSize,
            magnitudesDbfs = frame.magnitudesDbfs,
            peakHoldDbfs = peakHold.copyOf(),
            dominantFrequencyHz = frame.dominantFrequencyHz,
            nyquistHz = frame.nyquistHz,
            binWidthHz = frame.binWidthHz,
            cursorBinIndex = cursorBinIndex,
            sourceLabel = config.sourceLabel,
            isDemo = config.isDemo,
        )
        lastFrame = display
        _uiState.value = SpectrumUiState.Measuring(display)
    }

    private fun republishCurrentFrame() {
        val frame = lastFrame?.copy(cursorBinIndex = cursorBinIndex) ?: return
        lastFrame = frame
        when (_uiState.value) {
            is SpectrumUiState.Measuring -> _uiState.value = SpectrumUiState.Measuring(frame)
            is SpectrumUiState.Frozen -> _uiState.value = SpectrumUiState.Frozen(frame)
            else -> Unit
        }
    }

    companion object {
        private const val PUBLISH_INTERVAL_MILLIS = 100L
        private const val PEAK_HOLD_DECAY_DB = 0.3
        private const val DEFAULT_CURSOR_HZ = 1000.0
    }
}

fun bandLabelFor(frequencyHz: Double): String? = FrequencyBands.labelFor(frequencyHz)
