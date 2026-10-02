package com.peaceantz.stagescope.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peaceantz.stagescope.phone.ui.DeepLink
import com.peaceantz.stagescope.phone.ui.PhoneRoot
import com.peaceantz.stagescope.phone.ui.StageScopePhoneTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as PhoneApp).container

    private var deepLink by mutableStateOf<DeepLink?>(null)
    private var consentCallback: ((Boolean) -> Unit)? = null

    /** Launches Google's consent screen from the authorization PendingIntent and reports ok/cancelled. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentCallback?.invoke(result.resultCode == RESULT_OK)
        consentCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only the launch intent of a *fresh* start is read; a rotation re-delivers the same Intent and
        // must not re-open (or re-"open on phone" acknowledge) the same item again.
        if (savedInstanceState == null) deepLink = DeepLink.parse(intent)
        setContent {
            StageScopePhoneTheme {
                PhoneRoot(container, deepLink) { deepLink = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = DeepLink.parse(intent)
    }

    fun launchConsent(request: IntentSenderRequest, onDone: (Boolean) -> Unit) {
        consentCallback = onDone
        consentLauncher.launch(request)
    }
}
