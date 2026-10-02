package com.peaceantz.stagescope.phone.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.phone.data.PhoneSettings
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionPresentation
import com.peaceantz.stagescope.shared.actions.ActionRecord
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.IssueLogDraft
import com.peaceantz.stagescope.shared.actions.KeepDraft
import com.peaceantz.stagescope.shared.show.EmailAddress
import com.peaceantz.stagescope.shared.show.EmailValidator
import kotlinx.coroutines.launch

private fun toneOf(state: ActionState): Tone = when (state) {
    ActionState.COMPLETED -> Tone.OK
    ActionState.FAILED, ActionState.OUTCOME_UNCERTAIN -> Tone.BAD
    ActionState.AWAITING_INFORMATION, ActionState.AWAITING_REVIEW, ActionState.AWAITING_PHONE, ActionState.EXECUTING -> Tone.WARN
    else -> Tone.NEUTRAL
}

/**
 * One saved action (draft, handoff, issue log) with every control it can legitimately have in its
 * current state. Confirm is the only way an email or event ever runs, and it is bound to the exact
 * revision and content hash shown here -- if the draft changes underneath, the ledger refuses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionCard(container: PhoneContainer, record: ActionRecord, settings: PhoneSettings, openGoogleSetup: () -> Unit) {
    val context = LocalContext.current
    var editing by remember(record.actionId, record.revision) { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) { container.appScope.launch { block() } }

    Panel(title = ActionPresentation.title(record.draft)) {
        StatusRow("Status", ActionPresentation.statusLine(record), toneOf(record.state))

        when (val d = record.draft) {
            is EmailDraft -> EmailBody(d, settings)
            is CalendarDraft -> CalendarBody(d)
            is KeepDraft -> KeepBody(d)
            is IssueLogDraft -> Text(d.summary)
        }
        record.missingInformation.forEach { Note("Needs: $it  — answer in the conversation below.", tone = Tone.WARN) }
        record.warnings.forEach { Note("Check: $it", tone = Tone.WARN) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (record.state) {
                ActionState.AWAITING_REVIEW -> {
                    val connected = if (record.kind == ActionKind.EMAIL) settings.gmailGranted else settings.calendarGranted
                    if (record.kind.executesOnPhoneApi && connected && !settings.devMode) {
                        Button(onClick = { container.confirmFromPhone(record) }) {
                            Text(if (record.kind == ActionKind.EMAIL) "Confirm & send" else "Confirm & add")
                        }
                    } else if (settings.devMode) {
                        Note("Development mode: this can't be sent or added.", tone = Tone.WARN)
                    } else {
                        Button(onClick = openGoogleSetup) { Text(if (record.kind == ActionKind.EMAIL) "Connect Gmail to send" else "Connect Calendar to add") }
                    }
                    OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                    OutlinedButton(onClick = {
                        val intent = when (val d = record.draft) {
                            is EmailDraft -> Handoffs.emailIntent(d)
                            is CalendarDraft -> Handoffs.calendarIntent(d)
                            else -> null
                        }
                        if (intent != null && startSafely(context, intent)) act {
                            container.executor.handoffPrepared(record.actionId)
                            container.executor.handoffOpened(record.actionId)
                        }
                    }) { Text(if (record.kind == ActionKind.EMAIL) "Open in email app" else "Open in Calendar app") }
                    TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
                }
                ActionState.AWAITING_INFORMATION -> {
                    OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                    TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
                }
                ActionState.AWAITING_PHONE -> {
                    val d = record.draft
                    if (d is KeepDraft) {
                        Button(onClick = {
                            if (startSafely(context, Handoffs.keepShareIntent(d))) act { container.executor.handoffOpened(record.actionId) }
                        }) { Text("Share to Keep") }
                        OutlinedButton(onClick = { copyToClipboard(context, "Keep item", d.itemText) }) { Text("Copy text") }
                        OutlinedButton(onClick = { copyToClipboard(context, "Gemini command", d.geminiCommand()) }) { Text("Copy Gemini command") }
                    } else if (d is EmailDraft) {
                        OutlinedButton(onClick = { startSafely(context, Handoffs.emailIntent(d)); act { container.executor.handoffOpened(record.actionId) } }) { Text("Open email again") }
                    } else if (d is CalendarDraft) {
                        OutlinedButton(onClick = { Handoffs.calendarIntent(d)?.let { startSafely(context, it) }; act { container.executor.handoffOpened(record.actionId) } }) { Text("Open Calendar again") }
                    }
                    Button(onClick = { act { container.executor.markDone(record.actionId) } }) { Text("I did it — mark done") }
                    if (record.kind.needsConfirmation) OutlinedButton(onClick = { act { container.executor.reopen(record.actionId) } }) { Text("Review again") }
                    TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
                }
                ActionState.EXECUTING -> {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Note("It can't be cancelled once it has started. The final result will be shown here.")
                }
                ActionState.FAILED -> {
                    if (record.kind.needsConfirmation) OutlinedButton(onClick = { act { container.executor.reopen(record.actionId) } }) { Text("Review again") }
                    TextButton(onClick = { act { container.executor.cancel(record.actionId) } }) { Text("Dismiss") }
                }
                ActionState.OUTCOME_UNCERTAIN -> {
                    Button(onClick = { act { container.executor.resolveUncertain(record.actionId, true) } }) {
                        Text(if (record.kind == ActionKind.EMAIL) "I checked — it was sent" else "I checked — it was added")
                    }
                    OutlinedButton(onClick = { act { container.executor.resolveUncertain(record.actionId, false) } }) { Text("It wasn't — review again") }
                }
                ActionState.COMPLETED -> {
                    val link = record.receipt?.link
                    if (link != null) OutlinedButton(onClick = { openUrl(context, link) }) { Text("Open") }
                }
                else -> Unit
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel this ${record.kind.label.lowercase()}?", text = "Nothing has been sent or created. You can ask the assistant to draft it again.",
            confirmLabel = "Cancel it", onConfirm = { act { container.executor.cancel(record.actionId) } }, onDismiss = { confirmCancel = false }, destructive = true,
        )
    }
    if (editing) {
        when (val d = record.draft) {
            is EmailDraft -> EmailEditDialog(d, onDismiss = { editing = false }) { next ->
                act {
                    val missing = if (next.to.isEmpty()) listOf("Who should this go to?") else emptyList()
                    container.executor.replaceDraft(record.actionId, next, missing, record.warnings)
                }
            }
            is CalendarDraft -> CalendarEditDialog(d, onDismiss = { editing = false }) { next ->
                act { container.executor.replaceDraft(record.actionId, next, record.missingInformation, record.warnings) }
            }
            else -> editing = false
        }
    }
}

/** Starts an activity, reporting false (rather than crashing) when no app can handle it. */
private fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: ActivityNotFoundException) {
    false
}

@Composable
private fun EmailBody(d: EmailDraft, settings: PhoneSettings) {
    val from = d.senderAccount ?: settings.googleAccountEmail
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Labeled("From", from ?: "— connect Gmail to choose the sending account")
        Labeled("To", d.to.joinToString { it.display() }.ifEmpty { "— none yet" })
        if (d.cc.isNotEmpty()) Labeled("Cc", d.cc.joinToString { it.display() })
        if (d.bcc.isNotEmpty()) Labeled("Bcc", d.bcc.joinToString { it.display() })
        Labeled("Subject", d.subject.ifBlank { "(no subject)" })
        SelectionContainer {
            Text(d.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CalendarBody(d: CalendarDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Labeled("Title", d.title)
        if (d.startLocal.isBlank()) {
            Labeled("When", "— still needed")
        } else if (d.allDay) {
            Labeled("When", "${d.startLocal.take(10)} (all day) · ${d.timezoneId}")
        } else {
            Labeled("Starts", "${d.startLocal.replace('T', ' ')} (UTC${d.startOffset}) · ${d.timezoneId}")
            Labeled("Ends", "${d.endLocal.replace('T', ' ')} (UTC${d.endOffset})")
        }
        d.location?.let { Labeled("Where", it) }
        Labeled("Calendar", d.calendarLabel + (d.accountEmail?.let { " · $it" } ?: ""))
        if (d.invitees.isNotEmpty()) {
            Labeled("Invitees", d.invitees.joinToString { it.display() })
            Note("Google will email an invitation to these people when you confirm.", tone = Tone.WARN)
        }
        d.description?.let { Labeled("Notes", it) }
        d.assumptions.forEach { Note("Assumed: $it", tone = Tone.WARN) }
    }
}

@Composable
private fun KeepBody(d: KeepDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer { Text("“${d.itemText}”", style = MaterialTheme.typography.bodyLarge) }
        d.listName?.let { Labeled("List", it) }
        Note("Not in Keep yet. Google offers no supported way for an app to add to an existing Keep list, so finish it on this phone: share it to Keep as a new note, or ask Gemini, then mark it done.")
    }
}

@Composable
fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------------------------------ editors

/**
 * Splits an address list on commas/semicolons that are not inside a quoted display name or `<...>`.
 * A line break always splits (and is then rejected as an address), so a pasted newline can never
 * smuggle a second header into a message.
 */
internal fun splitAddressList(text: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var inQuotes = false
    var inAngle = false
    for (c in text) {
        when {
            c == '\n' || c == '\r' -> { parts += current.toString(); current.clear() }
            c == '"' -> { inQuotes = !inQuotes; current.append(c) }
            c == '<' && !inQuotes -> { inAngle = true; current.append(c) }
            c == '>' && !inQuotes -> { inAngle = false; current.append(c) }
            (c == ',' || c == ';') && !inQuotes && !inAngle -> { parts += current.toString(); current.clear() }
            else -> current.append(c)
        }
    }
    parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** Parses "a@b.c, Jane <j@x.y>" into addresses; returns the first problem otherwise. */
fun parseAddresses(text: String): Pair<List<EmailAddress>, String?> {
    val out = ArrayList<EmailAddress>()
    for (raw in splitAddressList(text)) {
        val m = Regex("^(.*)<([^<>]+)>$").find(raw)
        val addr = (m?.groupValues?.get(2) ?: raw).trim()
        val name = m?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        if (!EmailValidator.isValid(addr)) return emptyList<EmailAddress>() to "“$raw” isn't a valid email address."
        out += EmailAddress(addr, name)
    }
    return out to null
}

@Composable
private fun EmailEditDialog(d: EmailDraft, onDismiss: () -> Unit, onSave: (EmailDraft) -> Unit) {
    var to by remember { mutableStateOf(d.to.joinToString(", ") { it.display() }) }
    var cc by remember { mutableStateOf(d.cc.joinToString(", ") { it.display() }) }
    var bcc by remember { mutableStateOf(d.bcc.joinToString(", ") { it.display() }) }
    var subject by remember { mutableStateOf(d.subject) }
    var body by remember { mutableStateOf(d.body) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit email") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The fields scroll but Save doesn't, so the message sits outside them: inside, it could be below what's on screen.
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bcc, { bcc = it }, label = { Text("Bcc") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
                }
                error?.let { Note(it, tone = Tone.BAD) }
                Note("Editing changes the draft, so you will need to confirm it again.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (toList, e1) = parseAddresses(to)
                val (ccList, e2) = parseAddresses(cc)
                val (bccList, e3) = parseAddresses(bcc)
                val problem = e1 ?: e2 ?: e3
                if (problem != null) { error = problem; return@TextButton }
                onSave(d.copy(to = toList, cc = ccList, bcc = bccList, subject = subject.trim().take(300), body = body))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CalendarEditDialog(d: CalendarDraft, onDismiss: () -> Unit, onSave: (CalendarDraft) -> Unit) {
    var title by remember { mutableStateOf(d.title) }
    var location by remember { mutableStateOf(d.location.orEmpty()) }
    var notes by remember { mutableStateOf(d.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit event") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(location, { location = it }, label = { Text("Where") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                }
                error?.let { Note(it, tone = Tone.BAD) }
                Note("To change the date or time, tell the assistant in the conversation: it re-resolves the date, time zone and daylight-saving offset for you.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A Save that does nothing looks broken -- say why.
                if (title.isBlank()) { error = "Give the event a title."; return@TextButton }
                onSave(d.copy(title = title.trim().take(200), location = location.trim().ifEmpty { null }, description = notes.trim().ifEmpty { null }))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
