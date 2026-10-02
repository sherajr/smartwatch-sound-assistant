package com.peaceantz.stagescope.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureSession
import com.peaceantz.stagescope.audio.CaptureStatus
import kotlinx.coroutines.launch

/**
 * Created once per visit to the "main" (pager) destination and shared by Level/Spectrum/Ring via
 * `viewModel(viewModelStoreOwner = navController.getBackStackEntry("main"))`. Its lifetime -- and
 * therefore the [CaptureSession]'s -- spans page swipes and Details navigation, only ending when
 * "main" itself is popped off the back stack.
 */
class CaptureSessionViewModel(private val container: AppContainer) : ViewModel() {
    val session = CaptureSession(container, viewModelScope)

    init {
        // Keeps the assistant's view of "is measurement running, paused, stopped?" honest.
        viewModelScope.launch { session.status.collect { container.measurementHub.onStatus(it) } }
    }

    override fun onCleared() {
        session.release()
        container.measurementHub.onStatus(CaptureStatus.Stopped)
        super.onCleared()
    }
}
