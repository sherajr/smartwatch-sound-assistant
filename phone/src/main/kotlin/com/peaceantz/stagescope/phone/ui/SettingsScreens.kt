package com.peaceantz.stagescope.phone.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.BuildConfig
import com.peaceantz.stagescope.phone.MainActivity
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.phone.data.UsageGuard
import com.peaceantz.stagescope.phone.data.UsageLimits
import com.peaceantz.stagescope.phone.google.CalendarInfo
import com.peaceantz.stagescope.phone.google.ConnectResult
import com.peaceantz.stagescope.phone.google.GoogleFeature
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.protocol.Wire
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

fun Context.findMainActivity(): MainActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is MainActivity) return c; c = c.baseContext }
    return null
}

@Composable
fun SettingsHomeScreen(nav: (String) -> Unit) {
    ScreenColumn {
        listOf(
            Triple("settings/providers", "AI providers", "Keys, models, thorough mode, web search"),
            Triple("settings/google", "Gmail & Calendar", "Connect so confirmed emails and events can be sent from here"),
            Triple("settings/speech", "Voice & speech", "How watch dictation works, speaking replies"),
            Triple("settings/usage", "Usage & limits", "What this app has sent, estimated cost, your own limits"),
            Triple("settings/dev", "Developer", "Test mode, versions, resend to watch"),
        ).forEach { (route, title, subtitle) ->
            Panel(modifier = Modifier.clickable { nav(route) }) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Note(subtitle)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Google

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoogleScreen(container: PhoneContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.data.settings.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }

    fun connect(feature: GoogleFeature) {
        scope.launch {
            when (val r = container.google.connect(feature)) {
                is ConnectResult.Connected -> message = "${feature.label} connected as ${r.email}."
                is ConnectResult.Failed -> message = r.message
                is ConnectResult.NeedsConsent -> {
                    val activity = context.findMainActivity()
                    if (activity == null) message = "Open StageScope on the phone to continue."
                    else activity.launchConsent(IntentSenderRequest.Builder(r.pendingIntent.intentSender).build()) { ok ->
                        if (ok) connect(feature) else message = "Google permission wasn't granted."
                    }
                }
            }
        }
    }

    ScreenColumn {
        Panel("What StageScope asks for") {
            Text("Only two narrow permissions, each optional: send email as you (it can't read your mail) and create events on calendars you own. A message is only sent, and an event only created, after you tap Confirm on a draft you've reviewed.")
            Note("StageScope doesn't store Google access tokens; Google's own sign-in keeps and refreshes them. You can revoke access here or at myaccount.google.com/permissions.")
            settings.googleAccountEmail?.let { Labeled("Connected account", it) }
        }

        listOf(
            GoogleFeature.GMAIL to settings.gmailGranted,
            GoogleFeature.CALENDAR to settings.calendarGranted,
        ).forEach { (feature, granted) ->
            Panel(feature.label) {
                StatusRow("Status", if (granted) "Connected" else "Not connected", if (granted) Tone.OK else Tone.NEUTRAL)
                if (granted) OutlinedButton(onClick = { scope.launch { container.google.disconnect(feature); message = "${feature.label} disconnected." } }) { Text("Disconnect") }
                else Button(onClick = { connect(feature) }) { Text("Connect") }
            }
        }

        if (settings.calendarGranted) {
            Panel("Which calendar") {
                Labeled("Events go to", settings.defaultCalendarLabel)
                if (!settings.calendarListGranted) {
                    OutlinedButton(onClick = { connect(GoogleFeature.CALENDAR_LIST) }) { Text("Let me choose a different calendar") }
                    Note("This asks for one extra read-only permission (your list of calendars).")
                } else {
                    OutlinedButton(onClick = { scope.launch { calendars = container.google.calendars(); if (calendars == null) message = "Couldn't load your calendars." } }) { Text("Load my calendars") }
                    calendars?.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { container.google.chooseCalendar(c) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.defaultCalendarId == c.id, onClick = { scope.launch { container.google.chooseCalendar(c) } })
                            Text(c.summary + if (c.primary) " (primary)" else "")
                        }
                    }
                }
            }
        }

        message?.let { Note(it, tone = if (it.contains("connected", true) && !it.contains("not", true)) Tone.OK else Tone.WARN) }

        Panel("If Connect fails") {
            Text("Google only allows sign-in for an app whose package name and signing certificate are registered in a Google Cloud project that you own. This is one-time setup — the steps, including the exact package name and your debug SHA-1, are in docs/AI_SETUP.md.")
            Note("Until then, every email or event draft can still be opened in your own email or Calendar app with the text filled in (the “Open in…” button on the draft).")
        }
    }
}

// ------------------------------------------------------------------------------------------ Speech

@Composable
fun SpeechScreen(container: PhoneContainer) {
    var spoke by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        Panel("How voice input works") {
            Text("You dictate on your watch, using the watch's own dictation screen. It hands the words back to the watch app, you check them, and only then does the watch send the text — together with any attached measurement — to this phone. This phone never records you, never receives audio, and never transcribes anything.")
            Note("Watch dictation may need an internet connection. StageScope sends the transcript to your phone for the AI response.")
            Note("StageScope can't see or control how the watch's dictation service reaches its recognizer; that is the watch's own setting and service.")
        }
        Panel("Older recordings") {
            Note("Earlier versions of the watch app could record a short clip and ask this phone to turn it into text. That is turned off: a recording an older watch app offers is refused, nothing is uploaded to any cloud service, and the watch keeps the recording until you delete it there.")
        }
        Panel("Speaking replies") {
            Text("Replies are silent by default (theatre-safe). Tap Speak under any reply to hear it on this phone.")
            OutlinedButton(onClick = { spoke = when (val r = container.speech.speak("StageScope speech test.")) { is com.peaceantz.stagescope.phone.voice.SpeakResult.Started -> "Speaking…"; is com.peaceantz.stagescope.phone.voice.SpeakResult.Unavailable -> r.reason } }) { Text("Test phone speech") }
            spoke?.let { Note(it) }
            Note("While the phone speaks, your watch pauses measurement so the speaker isn't mistaken for a ring or a level.")
        }
    }
}

// ---------------------------------------------------------------------------------------------------- Usage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageScreen(container: PhoneContainer) {
    val settings by container.data.settings.state.collectAsState()
    val usage by container.data.usage.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val todayCount = usage.entries.count { it.kind == "chat" && Instant.ofEpochMilli(it.atEpochMs).atZone(zone).toLocalDate() == today }
    val month = UsageGuard.monthSpendUsd(usage.entries, now, zone)
    val limits = settings.limits

    var perDay by remember(limits) { mutableStateOf(limits.maxRequestsPerDay.toString()) }
    var budget by remember(limits) { mutableStateOf(limits.monthlyBudgetUsd?.let { "%.2f".format(it) }.orEmpty()) }
    var warn by remember(limits) { mutableStateOf(limits.warnAtPercent.toString()) }
    var maxOut by remember(limits) { mutableStateOf(limits.maxOutputTokens.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(3_000); saved = false } }

    ScreenColumn {
        Panel("These are limits StageScope applies itself") {
            Text("They control what this app sends from this phone. They are not a spending cap at your AI provider: a request already in flight, or other apps using the same key, can go past them. Costs are estimates from a dated price table (or the provider's own figure when it reports one) — your invoice is the authority.")
        }
        Panel("So far") {
            Labeled("AI requests today", "$todayCount of ${limits.maxRequestsPerDay}")
            Labeled("Estimated spend this month", "\$" + "%.2f".format(month) + (limits.monthlyBudgetUsd?.let { " of \$" + "%.2f".format(it) } ?: ""))
            ProviderId.entries.forEach { p ->
                val micros = usage.entries.filter { it.providerId == p && Instant.ofEpochMilli(it.atEpochMs).atZone(zone).let { d -> d.month == today.month && d.year == today.year } }
                    .sumOf { it.usage.reportedCostMicros ?: it.usage.estimatedCostMicros ?: 0L }
                if (micros > 0) Note("${p.shortLabel}: ${formatUsd(micros)} this month")
            }
        }
        Panel("Your limits") {
            OutlinedTextField(perDay, { perDay = it.filter(Char::isDigit).take(5) }, label = { Text("Max AI requests per day") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(budget, { budget = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Monthly budget in USD (blank = none)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(warn, { warn = it.filter(Char::isDigit).take(3) }, label = { Text("Warn at % of budget") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(maxOut, { maxOut = it.filter(Char::isDigit).take(6) }, label = { Text("Max reply length (tokens)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            error?.let { Note(it, tone = Tone.BAD) }
            if (saved) Note("Saved.", tone = Tone.OK)
            Button(onClick = {
                val d = perDay.toIntOrNull(); val w = warn.toIntOrNull(); val o = maxOut.toIntOrNull()
                val b = if (budget.isBlank()) null else budget.toDoubleOrNull()
                saved = false
                when {
                    d == null || d !in 1..5000 -> error = "Requests per day should be between 1 and 5000."
                    budget.isNotBlank() && (b == null || b <= 0.0) -> error = "Budget should be a positive amount, or blank."
                    w == null || w !in 1..100 -> error = "Warn level should be between 1 and 100."
                    o == null || o !in 256..64_000 -> error = "Reply length should be between 256 and 64000 tokens."
                    else -> { error = null; saved = true; scope.launch { container.data.settings.update { it.copy(limits = it.limits.copy(maxRequestsPerDay = d, monthlyBudgetUsd = b, warnAtPercent = w, maxOutputTokens = o)) } } }
                }
            }) { Text("Save limits") }
        }
        Panel("Recent requests") {
            if (usage.entries.isEmpty()) Text("Nothing yet.")
            usage.entries.takeLast(25).reversed().forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                val cost = e.usage.reportedCostMicros?.let { formatUsd(it) + " reported" } ?: e.usage.estimatedCostMicros?.let { formatUsd(it) + " est." } ?: "cost unknown"
                Text("${e.providerId.shortLabel} · ${e.modelId}", fontWeight = FontWeight.Medium)
                Note("${formatWhen(e.atEpochMs)} · ${e.kind}${if (!e.succeeded) " · failed" else ""} · ${e.usage.inputTokens} in / ${e.usage.outputTokens} out · $cost")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Developer

@Composable
fun DevScreen(container: PhoneContainer) {
    val settings by container.data.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf<String?>(null) }
    ScreenColumn {
        Panel("Test mode") {
            SwitchRow(
                "Use the built-in test assistant",
                "Replaces every AI provider with a local fake that never contacts any service and costs nothing. It can NEVER send email or create events. Use it to check the watch↔phone link.",
                settings.devMode,
            ) { on -> scope.launch { container.data.settings.update { it.copy(devMode = on) }; container.refreshWatchViews() } }
            if (settings.devMode) Note("Test mode is ON — answers are not from a real AI.", tone = Tone.WARN)
        }
        Panel("Versions") {
            Labeled("Phone app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Labeled("Protocol", "${Wire.MIN_SUPPORTED_VERSION}–${Wire.PROTOCOL_VERSION}, negotiated ${settings.negotiatedProtocolVersion ?: "—"}")
            Labeled("Watch app", settings.watchAppVersion ?: "not seen yet")
            Labeled("Last watch contact", settings.lastWatchContactEpochMs?.let(::formatWhen) ?: "never")
            Labeled("This phone's link id", settings.phoneInstallId.take(8))
        }
        Panel("Watch link") {
            Button(onClick = { scope.launch { container.refreshWatchViews(); sent = "Sent the latest providers, shows, issues and conversation to the watch." } }) { Text("Resend everything to the watch") }
            sent?.let { Note(it) }
            Note("The watch also refreshes these whenever it connects. Nothing here contains your API keys.")
        }
    }
}
