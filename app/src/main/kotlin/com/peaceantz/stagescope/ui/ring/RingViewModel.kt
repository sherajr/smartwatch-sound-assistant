package com.peaceantz.stagescope.ui.ring

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.RingSummaryState
import com.peaceantz.stagescope.dsp.RingCapture
import com.peaceantz.stagescope.dsp.RingCaptureState
import com.peaceantz.stagescope.dsp.RingSnapshot
import com.peaceantz.stagescope.dsp.RingTracker
import com.peaceantz.stagescope.dsp.SpectrumAnalyzer
import com.peaceantz.stagescope.dsp.SystemMonotonicClock
import com.peaceantz.stagescope.widget.notifyWatchSurfacesChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RingMeasurement(
    val snapshot: RingSnapshot,
    val liveSpectrum: SpectrumAnalyzer.Frame?,
    val sourceLabel: String,
    val isDemo: Boolean,
)

sealed interface RingUiState {
    data object PermissionDenied : RingUiState
    data class Unavailable(val reason: String) : RingUiState
    data class Error(val message: String) : RingUiState
    data class Measuring(val measurement: RingMeasurement) : RingUiState
}

/**
 * Owns the ring analysis session (RingTracker + its own SpectrumAnalyzer) above the pager: it
 * registers on the shared [CaptureSession] and must survive page swipes and Details navigation
 * without restarting capture or losing captures.
 */
class RingViewModel(private val container: AppContainer, private val session: CaptureSession) : ViewModel() {

    private val analyzer = SpectrumAnalyzer()
    private val tracker = RingTracker()

    private val _uiState = MutableStateFlow<RingUiState>(
        RingUiState.Measuring(RingMeasurement(emptySnapshot(), null, "", false))
    )
    val uiState: StateFlow<RingUiState> = _uiState.asStateFlow()

    private var currentConfig: CaptureConfig? = null
    private var lastPublishMillis = 0L
    private var lastPersistedRingKey: Pair<Long, Boolean>? = null

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        applyPersistedAutoHold()
        session.addListener(listener)
        viewModelScope.launch { session.status.collect(::handleStatus) }
    }

    fun start() {
        tracker.setAcquisitionActive(true)
        session.start()
    }

    fun stop() {
        tracker.setAcquisitionActive(false)
        session.stop()
        publishCurrent()
    }

    fun pin(captureId: Long) {
        tracker.pin(captureId)
        publishCurrent()
    }

    fun unpin() {
        tracker.unpin()
        publishCurrent()
    }

    fun selectCapture(captureId: Long) {
        tracker.selectCapture(captureId)
        publishCurrent()
    }

    fun clearSelected() {
        tracker.clearSelected()
        publishCurrent()
    }

    fun clearAll() {
        tracker.clearAll()
        publishCurrent()
    }

    fun setAutoHoldSeconds(seconds: Int) {
        tracker.settings = tracker.settings.copy(autoHoldMs = seconds * 1000L)
        viewModelScope.launch { container.settingsRepository.setRingAutoHoldSeconds(seconds) }
    }

    fun autoHoldSeconds(): Int = (tracker.settings.autoHoldMs / 1000L).toInt()

    private fun applyPersistedAutoHold() {
        val seconds = container.settingsRepository.settings.value.ringAutoHoldSeconds
        tracker.settings = tracker.settings.copy(autoHoldMs = seconds * 1000L)
    }

    override fun onCleared() {
        session.removeListener(listener)
        super.onCleared()
    }

    private fun handleStatus(status: CaptureStatus) {
        when (status) {
            is CaptureStatus.Running -> {
                val config = status.config
                if (currentConfig?.fingerprint() != config.fingerprint()) {
                    tracker.onConfigChanged(config.fingerprint())
                }
                currentConfig = config
                tracker.setAcquisitionActive(true)
                publishCurrent()
            }
            CaptureStatus.PermissionDenied -> _uiState.value = RingUiState.PermissionDenied
            is CaptureStatus.Unavailable -> _uiState.value = RingUiState.Unavailable(status.reason)
            is CaptureStatus.Error -> _uiState.value = RingUiState.Error(status.message)
            CaptureStatus.Stopped -> {
                tracker.setAcquisitionActive(false)
                publishCurrent()
            }
            CaptureStatus.Idle -> Unit
        }
    }

    private fun onBlock(block: FloatArray) {
        val config = currentConfig ?: return
        analyzer.pushSamples(block)
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        val frame = analyzer.computeFrame(config.sampleRate) ?: return
        lastPublishMillis = now
        val snap = tracker.update(frame)
        _uiState.value = RingUiState.Measuring(
            RingMeasurement(snap, frame, config.sourceLabel, config.isDemo)
        )
        maybePersistRingSummary(snap, config.isDemo)
    }

    /** Refreshes hero/history state (e.g. after Stop, pin/unpin/clear) without a new audio block. */
    private fun publishCurrent() {
        val snap = tracker.currentSnapshot()
        val config = currentConfig
        _uiState.value = RingUiState.Measuring(
            RingMeasurement(snap, null, config?.sourceLabel ?: "", config?.isDemo ?: false)
        )
        maybePersistRingSummary(snap, config?.isDemo ?: false)
    }

    /**
     * Writes the best candidate (pinned capture, else most recently seen) to
     * [com.peaceantz.stagescope.data.SurfaceSummaryRepository] for the Tile/complication -- only
     * when the chosen capture's identity actually changes (a new confirm, a pin/unpin, a clear),
     * not on every throttled block update, so a live stream of detector events never turns into
     * continuous provider updates. Demo-mode sessions never touch the persisted summary.
     */
    private fun maybePersistRingSummary(snap: RingSnapshot, isDemo: Boolean) {
        if (isDemo) return
        val best = snap.history.firstOrNull { it.pinned } ?: snap.history.firstOrNull()
        val key = best?.let { it.id to it.pinned }
        if (key == lastPersistedRingKey) return
        lastPersistedRingKey = key
        viewModelScope.launch {
            container.surfaceSummaryRepository.setRingSummary(best?.toRingSummaryState())
            notifyWatchSurfacesChanged(container.appContext)
        }
    }

    /** Converts the capture's monotonic (`nanoTime`-based) timestamp to wall-clock for persistence. */
    private fun RingCapture.toRingSummaryState(): RingSummaryState {
        val nanoNowMillis = SystemMonotonicClock.nowMillis()
        val wallNowMillis = System.currentTimeMillis()
        val wallConfirmedAt = wallNowMillis - (nanoNowMillis - confirmedAtMs)
        return RingSummaryState(
            captureId = id,
            frequencyHz = frequencyHz,
            timestampMillis = wallConfirmedAt,
            pinned = pinned,
        )
    }

    companion object {
        private const val PUBLISH_INTERVAL_MILLIS = 100L

        private fun emptySnapshot() = RingSnapshot(
            heroState = RingCaptureState.SEARCHING,
            heroCapture = null,
            detectingFrequencyHz = null,
            otherCandidates = emptyList(),
            history = emptyList(),
        )
    }
}
