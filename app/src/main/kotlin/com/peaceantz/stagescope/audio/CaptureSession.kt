package com.peaceantz.stagescope.audio

import com.peaceantz.stagescope.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/**
 * The single audio session shared by LEVEL, SPECTRUM, and RING. There is exactly one
 * [PcmSource] (real mic or demo) at a time; every registered listener receives every block, so
 * swiping between pages never creates a second [android.media.AudioRecord], restarts the
 * session, or resets any page's accumulated state. Owned above the pager (see StageScopeNavHost)
 * and shared by all three page ViewModels.
 *
 * It also lets the assistant borrow the microphone ([MeasurementControl]): [pauseForAssistant]
 * releases the real microphone but keeps the session -- its listeners, meters, Freeze and Ring bank --
 * alive, and [resumeAfterAssistant] re-opens it. The demo source never touches the microphone, so it is
 * never paused.
 */
class CaptureSession(private val container: AppContainer, private val scope: CoroutineScope) : MeasurementControl {

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var activeSource: PcmSource? = null
    private var activeIsDemo = false
    private var pausedSource: PcmSource? = null
    private var statusJob: Job? = null
    private val listeners = mutableListOf<(FloatArray) -> Unit>()

    /** True while a source is open (starting or measuring). A paused session is not "running" -- see [isPaused]. */
    val isRunning: Boolean get() = activeSource != null

    val isPaused: Boolean get() = _status.value is CaptureStatus.Paused

    init {
        container.audioCoordinator.attach(this)
    }

    fun addListener(listener: (FloatArray) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (FloatArray) -> Unit) {
        listeners.remove(listener)
    }

    fun start() {
        if (activeSource != null || isPaused) return
        val demoEnabled = container.settingsRepository.settings.value.demoModeEnabled
        val source = container.pcmSourceFor(demoEnabled)
        activeSource = source
        activeIsDemo = demoEnabled

        statusJob?.cancel()
        statusJob = scope.launch { source.status.collect { _status.value = it } }
        source.start(scope) { block -> listeners.forEach { it(block) } }
        // If the phone or watch is speaking right now, measuring would capture the assistant's own voice.
        container.audioCoordinator.onMeasurementStarted()
    }

    fun stop() {
        activeSource?.stop()
        activeSource = null
        pausedSource = null
        statusJob?.cancel()
        _status.value = CaptureStatus.Stopped
    }

    /** Called when the session's owner goes away, so the coordinator never resumes a dead session. */
    fun release() {
        stop()
        container.audioCoordinator.detach(this)
    }

    // ------------------------------------------------------------------------- MeasurementControl

    override val isPausedForAssistant: Boolean get() = isPaused

    override fun pauseForAssistant(reason: PauseReason): Boolean {
        val source = activeSource ?: return false
        // A demo session never opened the microphone, so there is nothing to hand over.
        if (activeIsDemo) return false
        val config = (_status.value as? CaptureStatus.Running)?.config
        statusJob?.cancel()
        statusJob = null
        source.stop()
        activeSource = null
        pausedSource = source
        _status.value = CaptureStatus.Paused(config, reason)
        return true
    }

    override suspend fun awaitMicReleased() {
        pausedSource?.awaitStopped()
    }

    override fun resumeAfterAssistant() {
        if (!isPaused) return
        pausedSource = null
        // The first value a new collector sees is the engine's stale "Stopped" from the pause; passing it
        // on would look like the person pressed Stop. Skip it until the engine reports something new.
        val source = container.pcmSourceFor(demoModeEnabled = false)
        activeSource = source
        activeIsDemo = false
        statusJob?.cancel()
        statusJob = scope.launch {
            source.status.dropWhile { it === CaptureStatus.Stopped || it === CaptureStatus.Idle }.collect { _status.value = it }
        }
        source.start(scope) { block -> listeners.forEach { it(block) } }
    }

    override fun endPausedSession() {
        if (isPaused) stop()
    }
}
