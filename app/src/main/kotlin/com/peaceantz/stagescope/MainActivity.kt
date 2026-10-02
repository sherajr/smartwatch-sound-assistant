package com.peaceantz.stagescope

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peaceantz.stagescope.assistant.speech.DictationLauncherBinding
import com.peaceantz.stagescope.ui.nav.ShortcutRequest
import com.peaceantz.stagescope.ui.nav.StageScopeNavHost
import com.peaceantz.stagescope.ui.theme.StageScopeTheme

class MainActivity : ComponentActivity() {

    private var pendingRequest by mutableStateOf<ShortcutRequest?>(null)
    private lateinit var dictationBinding: DictationLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only honor the launch intent on a genuinely fresh creation. `savedInstanceState == null`
        // is Android's own signal for that -- a recreation (rotation, or a process-death respawn
        // that restores this same task from Recents) redelivers the same Intent without a new
        // onNewIntent call, and must not replay a stale Measure/navigate request. A real re-tap of
        // a Tile/complication always arrives through onNewIntent instead, which is unconditionally
        // honored below since it can only mean a fresh, deliberate tap.
        if (savedInstanceState == null) {
            pendingRequest = ShortcutRequest.fromIntent(intent)
        }
        val container = (application as StageScopeApp).container
        // The watch's dictation screen is another app's Activity that covers this one, so its result callback must be registered
        // here, in the Activity itself, before it is STARTED -- not in a composable, which is gone while that screen is open. Doing it
        // on every creation (including after a rotation or a process death) is also what lets a result find its way back.
        // Nothing here opens the dictation screen: that only ever happens for a session the person started with a tap.
        dictationBinding = DictationLauncherBinding(this, container.dictation)
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            StageScopeTheme(theme = settings.theme, dimAppearance = settings.dimAppearanceEnabled) {
                StageScopeNavHost(
                    container = container,
                    pendingAction = pendingRequest,
                    onPendingActionConsumed = { pendingRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRequest = ShortcutRequest.fromIntent(intent)
    }

    override fun onDestroy() {
        // If this Activity is finishing for good, a dictation screen can no longer report to it; end that session safely.
        dictationBinding.onActivityFinishing()
        super.onDestroy()
    }
}
