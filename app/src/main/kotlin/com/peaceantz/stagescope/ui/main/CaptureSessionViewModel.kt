package com.peaceantz.stagescope.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.audio.CaptureSession

/**
 * Created once per visit to the "main" (pager) destination and shared by Level/Spectrum/Ring via
 * `viewModel(viewModelStoreOwner = navController.getBackStackEntry("main"))`. Its lifetime -- and
 * therefore the [CaptureSession]'s -- spans page swipes and Details navigation, only ending when
 * "main" itself is popped off the back stack.
 */
class CaptureSessionViewModel(container: AppContainer) : ViewModel() {
    val session = CaptureSession(container, viewModelScope)

    override fun onCleared() {
        session.stop()
        super.onCleared()
    }
}
