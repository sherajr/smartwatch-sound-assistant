package com.peaceantz.stagescope.audio

import com.peaceantz.stagescope.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single audio session shared by LEVEL, SPECTRUM, and RING. There is exactly one
 * [PcmSource] (real mic or demo) at a time; every registered listener receives every block, so
 * swiping between pages never creates a second [android.media.AudioRecord], restarts the
 * session, or resets any page's accumulated state. Owned above the pager (see StageScopeNavHost)
 * and shared by all three page ViewModels.
 */
class CaptureSession(private val container: AppContainer, private val scope: CoroutineScope) {

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var activeSource: PcmSource? = null
    private var statusJob: Job? = null
    private val listeners = mutableListOf<(FloatArray) -> Unit>()

    val isRunning: Boolean get() = activeSource != null

    fun addListener(listener: (FloatArray) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (FloatArray) -> Unit) {
        listeners.remove(listener)
    }

    fun start() {
        if (activeSource != null) return
        val demoEnabled = container.settingsRepository.settings.value.demoModeEnabled
        val source = container.pcmSourceFor(demoEnabled)
        activeSource = source

        statusJob?.cancel()
        statusJob = scope.launch { source.status.collect { _status.value = it } }
        source.start(scope) { block -> listeners.forEach { it(block) } }
    }

    fun stop() {
        activeSource?.stop()
        activeSource = null
        statusJob?.cancel()
        _status.value = CaptureStatus.Stopped
    }
}
