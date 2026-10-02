package com.peaceantz.stagescope.ui.assistant

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.assistant.AssistantFormatting
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.ui.components.RotatedContent

/** Setup status, how replies are delivered, and the few preferences the watch owns. */
@Composable
fun AssistantSettingsScreen(vm: AssistantViewModel, angleDegrees: Float, nav: AssistantNavigator) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val reachable by vm.reachable.collectAsStateWithLifecycle()
    val phoneName by vm.phoneName.collectAsStateWithLifecycle()
    val notice = vm.notice.collectAsStateWithLifecycle().value?.takeIf { it.scope == ScreenNotice.SETUP }?.message
    val listState = rememberScalingLazyListState()
    val providers = cache.providers
    val (readiness, ready) = AssistantFormatting.providerReadiness(providers)

    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("Assistant setup", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }

            item { SectionLabel("Status") }
            item { Hint(if (reachable) "Phone: connected${phoneName?.let { " ($it)" } ?: ""}" else "Phone: not reachable", if (reachable) Tone.GOOD else Tone.WARN) }
            item { Hint(readiness, if (ready) Tone.GOOD else Tone.WARN) }
            if (providers != null) {
                item { Hint(if (providers.gmailReady) "Gmail: connected" else "Gmail: not connected (drafts open on the phone instead)") }
                item { Hint(if (providers.calendarReady) "Calendar: connected" else "Calendar: not connected (drafts open on the phone instead)") }
                if (!providers.notificationsEnabled) item { Hint("Phone notifications are off, so “Continue on phone” can't alert you.", Tone.WARN) }
                if (providers.phoneAppVersion.isNotBlank()) item { Hint("Phone app ${providers.phoneAppVersion}") }
            } else {
                item { Hint("Nothing received from the phone yet. Open StageScope on your phone once.", Tone.WARN) }
            }
            item { ChipButton("Open setup on phone", secondary = "Asks Wear OS to open StageScope there", onClick = vm::openPhoneApp) }
            notice?.let { item { Hint(it, Tone.WARN) } }
            item { ChipButton("Refresh from phone", onClick = vm::refresh) }
            item { ChipButton(AssistantFormatting.providerChip(providers), secondary = "Change AI provider", onClick = nav.providers) }

            item { SectionLabel("Replies") }
            item {
                ChipButton(
                    "Reply style: ${when (prefs.outputMode) { ReplyMode.TEXT -> "Text"; ReplyMode.VOICE -> "Voice"; ReplyMode.BOTH -> "Text + voice" }}",
                    secondary = "Tap to change",
                    onClick = { vm.setOutputMode(when (prefs.outputMode) { ReplyMode.TEXT -> ReplyMode.VOICE; ReplyMode.VOICE -> ReplyMode.BOTH; ReplyMode.BOTH -> ReplyMode.TEXT }) },
                )
            }
            item {
                ChipButton(
                    if (prefs.theatreMode) "Theatre mode: ON" else "Theatre mode: OFF",
                    secondary = if (prefs.theatreMode) "Silent. Replies are only spoken when you tap Speak." else "Replies may be spoken aloud if you chose Voice.",
                    onClick = { vm.setTheatreMode(!prefs.theatreMode) },
                )
            }
            item {
                ChipButton(
                    if (prefs.hapticsEnabled) "Vibrate on answers: ON" else "Vibrate on answers: OFF",
                    secondary = "Off by default",
                    onClick = { vm.setHaptics(!prefs.hapticsEnabled) },
                )
            }
            item {
                ChipButton(
                    "Listen up to ${prefs.listenSeconds} s", secondary = "Tap to change (10 / 20 / 30)",
                    onClick = { vm.setListenSeconds(when { prefs.listenSeconds < 20 -> 20; prefs.listenSeconds < 30 -> 30; else -> 10 }) },
                )
            }
            item { Hint("The microphone is used only while you're asking, and measurement is paused for that moment and resumed after.") }
        }
    }
}

@Composable
fun ProviderPickerScreen(vm: AssistantViewModel, angleDegrees: Float) {
    val cache by vm.cache.collectAsStateWithLifecycle()
    val notice = vm.notice.collectAsStateWithLifecycle().value?.takeIf { it.scope == ScreenNotice.PROVIDERS }?.message
    val listState = rememberScalingLazyListState()
    val view = cache.providers
    // The choices are tapped lower down the list than this message sits, so scroll the message into view when it appears.
    LaunchedEffect(notice) { if (notice != null) listState.animateScrollToItem(1) }
    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("AI provider", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }
            notice?.let { item { Hint(it, Tone.WARN) } }
            if (view == null) item { Hint("Nothing received from the phone yet.", Tone.WARN) }
            view?.providers?.forEach { p ->
                item(key = p.providerId.name) {
                    val state = when {
                        view.devMode -> "test mode"
                        !p.hasKey -> "no key — add one on your phone"
                        !p.keyValidated -> "key not checked yet"
                        else -> "ready"
                    }
                    ChipButton(
                        label = (if (p.providerId == view.selected) "● " else "") + p.providerId.label,
                        secondary = "${p.modelId} · $state",
                        onClick = { vm.selectProvider(p.providerId) },
                        enabled = p.hasKey || view.devMode,
                    )
                }
            }
            view?.providers?.firstOrNull { it.providerId == view.selected }?.let { sel ->
                item { SectionLabel(sel.providerId.shortLabel) }
                item {
                    ChipButton(
                        if (sel.thorough) "Thorough: ON" else "Thorough: OFF", secondary = "Slower, usually costs more",
                        onClick = { vm.selectProvider(sel.providerId, thorough = !sel.thorough) },
                    )
                }
                if (sel.webSearchAvailable) item {
                    ChipButton(
                        if (sel.webSearchEnabled) "Web search: ON" else "Web search: OFF", secondary = "Extra cost; used only when it helps",
                        onClick = { vm.selectProvider(sel.providerId, webSearch = !sel.webSearchEnabled) },
                    )
                }
            }
            item { Hint("Keys are entered and stored on your phone only. Models and prices are on the phone's provider screen.") }
        }
    }
}

@Composable
fun ShowPickerScreen(vm: AssistantViewModel, angleDegrees: Float, onClose: () -> Unit) {
    val cache by vm.cache.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val shows = cache.shows
    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("Performance", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }
            item { Hint("Questions and issues you log here are tied to the performance you pick. Productions are managed on your phone.") }
            if (shows == null || shows.performances.isEmpty()) {
                item { Hint("No performances yet. Add a production and a performance on your phone.", Tone.WARN) }
            }
            item {
                val phoneChoice = shows?.performances?.firstOrNull { it.id == shows.selectedPerformanceId }?.label
                ChipButton(
                    (if (prefs.performanceOverrideId == null) "● " else "") + "Follow my phone",
                    secondary = phoneChoice ?: "No performance selected there",
                    onClick = { vm.setPerformance(null); onClose() },
                )
            }
            shows?.performances?.forEach { perf ->
                item(key = perf.id) {
                    val production = shows.productions.firstOrNull { it.id == perf.productionId }?.name
                    ChipButton(
                        (if (prefs.performanceOverrideId == perf.id) "● " else "") + perf.label,
                        secondary = listOfNotNull(production, perf.status.name.lowercase().replace('_', ' ')).joinToString(" · "),
                        onClick = { vm.setPerformance(perf.id); onClose() },
                    )
                }
            }
        }
    }
}
