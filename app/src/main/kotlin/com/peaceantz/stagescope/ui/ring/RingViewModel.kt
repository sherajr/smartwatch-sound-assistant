package com.peaceantz.stagescope.ui.ring

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureConfig
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import com.peaceantz.stagescope.data.PersistedRingCapture
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
 * without restarting capture or losing captures. Pinned captures are additionally restored from
 * [com.peaceantz.stagescope.data.RingBankRepository] at startup (metadata only, no audio) and
 * re-saved on every pin-affecting change.
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
    private var lastPersistedRingKey: Triple<Long, Boolean, Double>? = null

    private val listener: (FloatArray) -> Unit = { block -> onBlock(block) }

    init {
        applyPersistedAutoHold()
        restorePinnedBank()
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
        persistPinnedBank()
    }

    fun unpin(captureId: Long) {
        tracker.unpin(captureId)
        publishCurrent()
        persistPinnedBank()
    }

    fun selectCapture(captureId: Long) {
        tracker.selectCapture(captureId)
        publishCurrent()
    }

    fun clearSelected() {
        tracker.clearSelected()
        publishCurrent()
        persistPinnedBank()
    }

    fun clearUnpinned() {
        tracker.clearUnpinned()
        publishCurrent()
    }

    fun clearAll() {
        tracker.clearAll()
        publishCurrent()
        persistPinnedBank()
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

    /** Replays pinned captures saved from a previous run -- frequency/id only, never audio. They
     *  read as PINNED (and [RingCapture.restoredFromDisk]) until a live detection updates them. */
    private fun restorePinnedBank() {
        for (persisted in container.ringBankRepository.bank.value.pinned) {
            tracker.restoreCapture(
                id = persisted.id,
                frequencyHz = persisted.frequencyHz,
                savedAtWallClockMillis = persisted.savedAtMillis,
            )
        }
    }

    private fun persistPinnedBank() {
        val pinned = tracker.currentSnapshot().history.filter { it.pinned }.map {
            PersistedRingCapture(id = it.id, frequencyHz = it.frequencyHz, savedAtMillis = System.currentTimeMillis())
        }
        viewModelScope.launch { container.ringBankRepository.savePinned(pinned) }
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
     * Writes the most prominent currently-confirmed ring (per [RingSnapshot.mostProminentCaptureId],
     * falling back to the strongest remaining historical capture if that id was cleared) to
     * [com.peaceantz.stagescope.data.SurfaceSummaryRepository] for the Tile/complication -- NOT
     * "pinned, else most recent": pin state and manual selection never enter this decision, so a
     * switch driven purely by the audio stream (no Pin tap at all) still reaches the complication.
     * Only writes when the chosen capture's identity/pin/frequency actually changed, so a live
     * stream of detector updates at ~10 Hz never turns into continuous provider writes.
     */
    private fun maybePersistRingSummary(snap: RingSnapshot, isDemo: Boolean) {
        if (isDemo) return
        val chosen = resolveComplicationCapture(snap)
        val key = chosen?.let { Triple(it.id, it.pinned, it.frequencyHz) }
        if (key == lastPersistedRingKey) return
        lastPersistedRingKey = key
        viewModelScope.launch {
            container.surfaceSummaryRepository.setRingSummary(chosen?.toRingSummaryState())
            notifyWatchSurfacesChanged(container.appContext)
        }
    }

    private fun resolveComplicationCapture(snap: RingSnapshot): RingCapture? {
        val byMostProminentId = snap.mostProminentCaptureId?.let { id -> snap.history.find { it.id == id } }
        if (byMostProminentId != null) return byMostProminentId
        return snap.history.maxByOrNull { it.prominenceDb }
    }

    /** Converts the capture's monotonic timestamp to wall-clock; a disk-restored capture instead
     *  uses its saved wall-clock time directly (this run's monotonic clock has no relation to it). */
    private fun RingCapture.toRingSummaryState(): RingSummaryState {
        val timestamp = if (restoredFromDisk && restoredWallClockMillis != null) {
            restoredWallClockMillis
        } else {
            val nanoNowMillis = SystemMonotonicClock.nowMillis()
            val wallNowMillis = System.currentTimeMillis()
            wallNowMillis - (nanoNowMillis - lastSeenAtMs)
        }
        return RingSummaryState(
            captureId = id,
            frequencyHz = frequencyHz,
            timestampMillis = timestamp,
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
            mostProminentCaptureId = null,
            slotsUsed = 0,
            slotsTotal = 5,
            allSlotsPinned = false,
        )
    }
}
