package com.peaceantz.stagescope

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peaceantz.stagescope.ui.nav.ShortcutRequest
import com.peaceantz.stagescope.ui.nav.StageScopeNavHost
import com.peaceantz.stagescope.ui.theme.StageScopeTheme

class MainActivity : ComponentActivity() {

    private var pendingRequest by mutableStateOf<ShortcutRequest?>(null)

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
}
