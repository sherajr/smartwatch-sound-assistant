package com.peaceantz.stagescope.phone.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.link.WatchNode
import kotlinx.coroutines.delay

/**
 * "Is everything wired up?" at a glance: each row says what is true and, when it isn't, what to do.
 * Every status here is read from real state (the Data Layer, the key store, the permission system) --
 * nothing is assumed to work.
 */
@Composable
fun HomeScreen(container: PhoneContainer, nav: (String) -> Unit, switchTab: (String) -> Unit) {
    val context = LocalContext.current
    val settings by container.data.settings.state.collectAsState()
    val actions by container.data.actions.state.collectAsState()
    var nodes by remember { mutableStateOf<List<WatchNode>?>(null) }
    var notificationsOk by remember { mutableStateOf(container.continuations.notificationsEnabled()) }

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nodes = container.watchLink.reachableWatches()
                notificationsOk = container.continuations.notificationsEnabled()
                delay(8_000)
            }
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOk = container.continuations.notificationsEnabled() }

    val provider = settings.selectedProvider
    val hasKey = container.providers.hasKey(provider)
    val providerState = settings.keyStatus[provider]
    val needAttention = actions.actions.values.count { it.state in NEEDS_ATTENTION }

    ScreenColumn {
        if (settings.devMode) Panel { Note("Test mode is ON: answers come from a built-in fake, not a real AI, and nothing can be sent or created.", tone = Tone.WARN) }

        if (needAttention > 0) {
            Panel("Waiting for you") {
                Text("$needAttention item${if (needAttention == 1) "" else "s"} to review, answer or finish.")
                Button(onClick = { switchTab("chats") }) { Text("Open conversations") }
            }
        }

        Panel("Watch") {
            val list = nodes
            when {
                list == null -> StatusRow("Connection", "Checking…", Tone.NEUTRAL)
                list.isEmpty() -> {
                    StatusRow("Connection", "No watch running StageScope is reachable right now", Tone.WARN)
                    Note("Check that the watch is connected in the Wear OS app and that StageScope is installed on it. The watch app must be signed with the same key as this phone app, or Wear won't connect them (docs/AI_SETUP.md). The watch's measurement tools work without the phone.")
                }
                else -> StatusRow("Connection", "Connected: ${list.joinToString { it.displayName }}", Tone.OK)
            }
            settings.watchAppVersion?.let { Labeled("Watch app version", it) }
            settings.lastWatchContactEpochMs?.let { Labeled("Last heard from the watch", formatWhen(it)) }
        }

        Panel("AI provider") {
            val model = ModelCatalog.find(provider, settings.modelFor(provider))
            Labeled("Active", "${provider.label} · ${model?.displayName ?: settings.modelFor(provider)}")
            when {
                settings.devMode -> StatusRow("Key", "Not needed in test mode", Tone.NEUTRAL)
                !hasKey -> StatusRow("Key", "Not set up — the assistant can't answer yet", Tone.WARN)
                providerState?.validated == true -> StatusRow("Key", "Works", Tone.OK)
                else -> StatusRow("Key", "Saved, not checked yet", Tone.WARN)
            }
            OutlinedButton(onClick = { nav("settings/providers") }, modifier = Modifier.fillMaxWidth()) { Text("Manage providers") }
        }

        Panel("Gmail & Calendar") {
            StatusRow("Gmail", if (settings.gmailGranted) "Connected as ${settings.googleAccountEmail}" else "Not connected — drafts can be opened in your email app instead", if (settings.gmailGranted) Tone.OK else Tone.NEUTRAL)
            StatusRow("Calendar", if (settings.calendarGranted) "Connected — events go to ${settings.defaultCalendarLabel}" else "Not connected — drafts can be opened in your Calendar app instead", if (settings.calendarGranted) Tone.OK else Tone.NEUTRAL)
            OutlinedButton(onClick = { nav("settings/google") }, modifier = Modifier.fillMaxWidth()) { Text("Connect or manage") }
        }

        Panel("Voice & notifications") {
            StatusRow("Voice input", "Done on your watch with its own dictation screen — the phone doesn't record or transcribe", Tone.NEUTRAL)
            StatusRow("Notifications", if (notificationsOk) "Allowed — “Continue on phone” can alert you" else "Off — “Continue on phone” will save the item but can't alert you", if (notificationsOk) Tone.OK else Tone.WARN)
            if (!notificationsOk) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow notifications") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open notification settings") }
            }
        }

        Panel("How StageScope keeps you in control") {
            Note("Nothing is emailed or added to a calendar until you review the full draft and tap Confirm. Keep items are prepared on your phone for you to finish — StageScope never claims they were added. Measurements are only sent to an AI when you ask a question, and never as a continuous stream.")
        }
    }
}
