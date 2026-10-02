package com.peaceantz.stagescope.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.KeyPages
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.phone.ai.core.ModelCatalog
import com.peaceantz.stagescope.phone.ai.core.ModelInfo
import com.peaceantz.stagescope.phone.data.PhoneSettings
import com.peaceantz.stagescope.shared.assistant.ProviderId
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ProvidersScreen(container: PhoneContainer) {
    val settings by container.data.settings.state.collectAsState()
    ScreenColumn {
        Panel("Bring your own key") {
            Text("StageScope is a personal assistant that uses your own account with each AI service. Your key is encrypted with the Android Keystore on this phone. It is never built into the app, sent to your watch, written to logs or backups, or shown to an AI model.")
            Note("Usage is billed by the provider to your account. StageScope can't see or cap your provider bill; its own limits (Settings → Usage) only control what this app sends.")
        }
        ProviderId.entries.forEach { ProviderCard(container, it, settings) }
        Note("Prices are StageScope's estimates from each provider's public pricing page, last checked ${ModelCatalog.VERIFIED_ON}. Your provider's invoice is the authority. A model that is listed here but unavailable to your key shows an error — StageScope never silently switches to another model or provider.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(container: PhoneContainer, provider: ProviderId, settings: PhoneSettings) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The key lives only in this in-memory field -- deliberately NOT rememberSaveable, so it never lands in saved instance state.
    var keyInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val hasKey = container.providers.hasKey(provider)
    val unreadable = container.credentials.isUnreadable(provider)
    val status = settings.keyStatus[provider]
    val selected = settings.selectedProvider == provider
    val modelId = settings.modelFor(provider)
    val model = ModelCatalog.find(provider, modelId)

    Panel(title = provider.label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (text, tone) = when {
                unreadable -> "Saved key can't be read on this phone any more. Enter it again." to Tone.BAD
                !hasKey -> "No key yet" to Tone.NEUTRAL
                status?.validated == true -> "Key works · checked ${status.checkedAtEpochMs?.let(::formatWhen) ?: ""}" to Tone.OK
                status?.lastErrorMessage != null -> status.lastErrorMessage to Tone.BAD
                else -> "Key saved ${container.providers.maskedKey(provider)?.let { "($it)" } ?: ""} — not checked yet" to Tone.WARN
            }
            StatusRow("Status", text, tone, Modifier.weight(1f))
        }

        if (selected) Note("Active provider for the watch and phone.", tone = Tone.OK)
        else OutlinedButton(onClick = { scope.launch { container.providers.select(provider) } }, enabled = hasKey || settings.devMode) { Text("Use ${provider.shortLabel}") }

        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (hasKey) "Replace API key" else "Paste API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyInput.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = container.providers.saveKey(provider, keyInput)
                    message = r.fold(onSuccess = { keyInput = ""; "Saved. Tap “Check key” to test it." }, onFailure = { it.message ?: "Couldn't save that key." })
                    busy = false
                }
            }) { Text("Save key") }
            OutlinedButton(enabled = hasKey && !unreadable && !busy, onClick = {
                busy = true; message = "Checking…"
                scope.launch {
                    val r = container.providers.validate(provider)
                    message = if (r.ok) "Key works." + (if (container.providers.modelLooksUnavailable(provider)) " But the selected model isn't in this key's model list — pick another below." else "") else (r.message ?: "That key didn't work.")
                    busy = false
                }
            }) { Text("Check key") }
            OutlinedButton(onClick = { openUrl(context, KeyPages.url(provider)) }) { Text("Get a key") }
            if (hasKey) TextButton(onClick = { removing = true }) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
        }
        message?.let { Note(it, tone = if (it.startsWith("Key works") || it.startsWith("Saved")) Tone.OK else if (it == "Checking…") Tone.NEUTRAL else Tone.BAD) }
        Note(KeyPages.billingNote(provider))

        Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ModelCatalog.forProvider(provider).forEach { m ->
            ModelRow(m, selected = m.id == modelId) { scope.launch { container.providers.setModel(provider, m.id) } }
        }
        if (container.providers.modelLooksUnavailable(provider)) {
            Note("This key's model list doesn't include “$modelId”. Requests will fail until you pick a model it can use.", tone = Tone.BAD)
        }

        SwitchRow(
            "Thorough mode", "More reasoning: slower and usually costs more. Off = balanced.",
            settings.thorough[provider] == true,
        ) { scope.launch { container.providers.setThorough(provider, it) } }
        SwitchRow(
            "Allow web search", model?.webSearchNote ?: "Lets the model look things up. Off by default; extra cost.",
            settings.webSearchFor(provider),
        ) { scope.launch { container.providers.setWebSearch(provider, it) } }
    }

    if (removing) {
        ConfirmDialog(
            title = "Remove the ${provider.shortLabel} key?", text = "The encrypted key is deleted from this phone. You can paste it again later.",
            confirmLabel = "Remove", destructive = true, onDismiss = { removing = false },
            onConfirm = { scope.launch { container.providers.removeKey(provider); message = "Key removed." } },
        )
    }
}

@Composable
private fun ModelRow(m: ModelInfo, selected: Boolean, onSelect: () -> Unit) {
    val today = LocalDate.now()
    val p = m.priceOn(today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 12.dp)) {
            Text(m.displayName + if (m.isDefault) "  · default" else "", fontWeight = FontWeight.Medium)
            Note(m.blurb)
            Note("\$${trim(p.inputPerMTok)} in / \$${trim(p.outputPerMTok)} out per 1M tokens · ${m.id}")
            val after = m.priceAfter
            if (m.changesOn != null && after != null) {
                Note("Price changes ${m.changesOn} to \$${trim(after.inputPerMTok)} in / \$${trim(after.outputPerMTok)} out.")
            }
        }
    }
}

private fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')
