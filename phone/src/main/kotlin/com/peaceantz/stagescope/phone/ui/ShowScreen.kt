package com.peaceantz.stagescope.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.shared.show.CalendarDefaults
import com.peaceantz.stagescope.shared.show.Contact
import com.peaceantz.stagescope.shared.show.EmailValidator
import com.peaceantz.stagescope.shared.show.Performance
import com.peaceantz.stagescope.shared.show.PerformanceStatus
import com.peaceantz.stagescope.shared.show.Production
import com.peaceantz.stagescope.shared.show.RecipientGroup
import com.peaceantz.stagescope.shared.show.ShowLibrary
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private fun newId() = UUID.randomUUID().toString()

/** Applies a library edit and mirrors the (names-only) view to the watch. */
private fun PhoneContainer.editShows(transform: (ShowLibrary) -> ShowLibrary) {
    appScope.launch {
        data.shows.update(transform)
        publisher.publishShows()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowScreen(container: PhoneContainer) {
    val shows by container.data.shows.state.collectAsState()
    val lib = shows.library
    val production = lib.production(lib.selectedProductionId)
    val performances = lib.performances.filter { it.productionId == production?.id }
        .sortedWith(compareByDescending<Performance> { it.localDate }.thenByDescending { it.localTime ?: "" })

    var productionDialog by remember { mutableStateOf<Production?>(null) }
    var creatingProduction by remember { mutableStateOf(false) }
    var performanceDialog by remember { mutableStateOf<Performance?>(null) }
    var creatingPerformance by remember { mutableStateOf(false) }
    var contactDialog by remember { mutableStateOf<Contact?>(null) }
    var creatingContact by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf<RecipientGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var deletingProduction by remember { mutableStateOf(false) }

    ScreenColumn {
        Note("Your productions and performances keep issues and reports separate night by night. Only names (no email addresses) are mirrored to your watch.")

        Panel("Production") {
            if (lib.productions.isEmpty()) Text("No production yet. Add one to organize issues and reports by show.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lib.productions.forEach { p ->
                    FilterChip(selected = p.id == production?.id, onClick = {
                        container.editShows { l ->
                            val keep = l.performance(l.selectedPerformanceId)?.takeIf { it.productionId == p.id }
                            l.copy(selectedProductionId = p.id, selectedPerformanceId = keep?.id)
                        }
                    }, label = { Text(p.name) })
                }
            }
            production?.let {
                Labeled("Venue", it.venue ?: "—")
                Labeled("Time zone", it.timezoneId ?: "This phone's zone (${ZoneId.systemDefault().id})")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creatingProduction = true }) { Text("Add production") }
                if (production != null) {
                    OutlinedButton(onClick = { productionDialog = production }) { Text("Edit") }
                    TextButton(onClick = { deletingProduction = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (production != null) {
            Panel("Performances") {
                if (performances.isEmpty()) Text("No performances yet.")
                performances.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { performanceDialog = p }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName(), fontWeight = if (p.id == lib.selectedPerformanceId) FontWeight.Bold else FontWeight.Normal)
                            Note(p.status.name.lowercase().replace('_', ' '))
                        }
                        if (p.id == lib.selectedPerformanceId) Note("Selected", tone = Tone.OK)
                        else TextButton(onClick = { container.editShows { it.copy(selectedPerformanceId = p.id) } }) { Text("Select") }
                    }
                }
                Button(onClick = { creatingPerformance = true }) { Text("Add performance") }
                Note("Issues you log are tied to the selected performance, and reports use only that performance's facts.")
            }

            Panel("Report") {
                production.reportSections.forEach { s ->
                    SwitchRow(s.title, s.guidance.ifBlank { null }, s.enabled) { on ->
                        container.editShows { l -> l.copy(productions = l.productions.map { if (it.id == production.id) it.copy(reportSections = it.reportSections.map { r -> if (r.id == s.id) r.copy(enabled = on) else r }) else it }) }
                    }
                }
                WritingPrefs(production) { next -> container.editShows { l -> l.copy(productions = l.productions.map { if (it.id == next.id) next else it }) } }
                Note("Reports are drafted only from the selected performance's logged facts. The assistant doesn't invent incidents, fixes or recipients.")
            }
        }

        Panel("People you can email") {
            if (lib.contacts.isEmpty()) Text("No contacts yet. StageScope emails only addresses you add here, or ones you type or say yourself.")
            lib.contacts.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { contactDialog = c }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name + (c.role?.let { " · $it" } ?: ""), fontWeight = FontWeight.Medium)
                        Note(c.email + if (c.verified) "" else "  — not verified")
                    }
                }
            }
            Button(onClick = { creatingContact = true }) { Text("Add person") }
            Note("“Verified” means you checked the address. A spoken first name is never turned into an address by guesswork.")
        }

        Panel("Groups") {
            lib.groups.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { groupDialog = g }) {
                    Column {
                        Text(g.name, fontWeight = FontWeight.Medium)
                        Note(g.contactIds.mapNotNull { id -> lib.contacts.firstOrNull { it.id == id }?.name }.joinToString().ifEmpty { "No one yet" })
                    }
                }
            }
            Button(onClick = { creatingGroup = true }) { Text("Add group") }
            if (production != null && lib.groups.isNotEmpty()) {
                Text("Report recipients for ${production.name}", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = production.reportRecipientGroupId == null, onClick = {
                        container.editShows { l -> l.copy(productions = l.productions.map { if (it.id == production.id) it.copy(reportRecipientGroupId = null) else it }) }
                    }, label = { Text("None") })
                    lib.groups.forEach { g ->
                        FilterChip(selected = production.reportRecipientGroupId == g.id, onClick = {
                            container.editShows { l -> l.copy(productions = l.productions.map { if (it.id == production.id) it.copy(reportRecipientGroupId = g.id) else it }) }
                        }, label = { Text(g.name) })
                    }
                }
            }
        }

        Panel("Calendar defaults") {
            CalendarDefaultsEditor(lib.calendarDefaults) { next -> container.editShows { it.copy(calendarDefaults = next) } }
        }
    }

    if (creatingProduction || productionDialog != null) {
        ProductionDialog(productionDialog, onDismiss = { creatingProduction = false; productionDialog = null }) { p ->
            container.editShows { l ->
                val exists = l.productions.any { it.id == p.id }
                l.copy(
                    productions = if (exists) l.productions.map { if (it.id == p.id) p else it } else l.productions + p,
                    selectedProductionId = if (exists) l.selectedProductionId else p.id,
                    selectedPerformanceId = if (exists) l.selectedPerformanceId else null,
                )
            }
        }
    }
    if (creatingPerformance || performanceDialog != null) {
        PerformanceDialog(performanceDialog, production?.id ?: "", onDismiss = { creatingPerformance = false; performanceDialog = null }, onDelete = { id ->
            container.editShows { l -> l.copy(performances = l.performances.filterNot { it.id == id }, selectedPerformanceId = l.selectedPerformanceId?.takeIf { it != id }) }
        }) { p ->
            container.editShows { l ->
                val exists = l.performances.any { it.id == p.id }
                l.copy(
                    performances = if (exists) l.performances.map { if (it.id == p.id) p else it } else l.performances + p,
                    selectedPerformanceId = if (exists) l.selectedPerformanceId else p.id,
                )
            }
        }
    }
    if (creatingContact || contactDialog != null) {
        ContactDialog(contactDialog, onDismiss = { creatingContact = false; contactDialog = null }, onDelete = { id ->
            container.editShows { l ->
                l.copy(
                    contacts = l.contacts.filterNot { it.id == id },
                    groups = l.groups.map { g -> g.copy(contactIds = g.contactIds - id) },
                    productions = l.productions.map { it.copy(issueRecipientContactIds = it.issueRecipientContactIds - id) },
                )
            }
        }) { c ->
            container.editShows { l -> l.copy(contacts = if (l.contacts.any { it.id == c.id }) l.contacts.map { if (it.id == c.id) c else it } else l.contacts + c) }
        }
    }
    if (creatingGroup || groupDialog != null) {
        GroupDialog(groupDialog, lib.contacts, onDismiss = { creatingGroup = false; groupDialog = null }, onDelete = { id ->
            container.editShows { l -> l.copy(groups = l.groups.filterNot { it.id == id }, productions = l.productions.map { if (it.reportRecipientGroupId == id) it.copy(reportRecipientGroupId = null) else it }) }
        }) { g ->
            container.editShows { l -> l.copy(groups = if (l.groups.any { it.id == g.id }) l.groups.map { if (it.id == g.id) g else it } else l.groups + g) }
        }
    }
    if (deletingProduction && production != null) {
        ConfirmDialog(
            title = "Delete “${production.name}”?", text = "Its performances are removed too. Logged issues stay in the issue log.",
            confirmLabel = "Delete", destructive = true, onDismiss = { deletingProduction = false },
            onConfirm = {
                container.editShows { l ->
                    val remaining = l.productions.filterNot { it.id == production.id }
                    l.copy(
                        productions = remaining, performances = l.performances.filterNot { it.productionId == production.id },
                        selectedProductionId = remaining.firstOrNull()?.id, selectedPerformanceId = null,
                    )
                }
            },
        )
    }
}

@Composable
private fun WritingPrefs(p: Production, onChange: (Production) -> Unit) {
    var tone by remember(p.id) { mutableStateOf(p.writing.tone) }
    var greeting by remember(p.id) { mutableStateOf(p.writing.greeting) }
    var signature by remember(p.id) { mutableStateOf(p.writing.signature.orEmpty()) }
    Text("How emails should read", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(tone, { tone = it }, label = { Text("Tone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(greeting, { greeting = it }, label = { Text("Greeting") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(signature, { signature = it }, label = { Text("Signature") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    OutlinedButton(onClick = {
        onChange(p.copy(writing = p.writing.copy(tone = tone.trim().ifEmpty { p.writing.tone }, greeting = greeting.trim(), signature = signature.trim().ifEmpty { null })))
    }) { Text("Save writing preferences") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarDefaultsEditor(d: CalendarDefaults, onChange: (CalendarDefaults) -> Unit) {
    var minutes by remember(d) { mutableStateOf(d.defaultDurationMinutes.toString()) }
    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Default event length (minutes)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Text("When you say an hour with no AM/PM", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ASK" to "Ask me", "PM" to "Assume PM", "AM" to "Assume AM").forEach { (value, label) ->
            FilterChip(selected = d.ambiguousHourBias == value, onClick = { onChange(d.copy(ambiguousHourBias = value)) }, label = { Text(label) })
        }
    }
    OutlinedButton(enabled = (minutes.toIntOrNull() ?: 0) in 5..1440 && minutes.toInt() != d.defaultDurationMinutes, onClick = {
        onChange(d.copy(defaultDurationMinutes = minutes.toInt()))
    }) { Text("Save length") }
    Note("Any default StageScope applies — a length, AM/PM, or a daylight-saving shift — is always shown on the review card before you confirm.")
}

// ------------------------------------------------------------------------------------------ dialogs

/**
 * A form in a dialog. [onSave] returns null when it saved, or the message to show. The message is drawn *outside* the scrolling
 * fields: Save doesn't scroll, so an error placed inside them could sit below the visible part and Save would look like it did
 * nothing (it did, on a phone: an invalid time zone just seemed to ignore the tap). Deleting asks first.
 */
@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, onSave: () -> String?, onDelete: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                error?.let { Note(it, tone = Tone.BAD) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(); if (error == null) onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete this?", text = "This can't be undone.", confirmLabel = "Delete", destructive = true,
            onDismiss = { confirmDelete = false }, onConfirm = { onDelete(); onDismiss() },
        )
    }
}

@Composable
private fun ProductionDialog(initial: Production?, onDismiss: () -> Unit, onSave: (Production) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var venue by remember { mutableStateOf(initial?.venue.orEmpty()) }
    var zone by remember { mutableStateOf(initial?.timezoneId.orEmpty()) }
    var equipment by remember { mutableStateOf(initial?.equipmentNotes.orEmpty()) }
    var calendarId by remember { mutableStateOf(initial?.defaultCalendarId.orEmpty()) }
    FormDialog(if (initial == null) "Add production" else "Edit production", onDismiss, onSave = {
        val zoneOk = zone.isBlank() || runCatching { ZoneId.of(zone.trim()) }.isSuccess
        when {
            name.isBlank() -> "Give the production a name."
            !zoneOk -> "“${zone.trim()}” isn't a time zone ID (try America/New_York)."
            else -> {
                onSave(
                    (initial ?: Production(id = newId(), name = name.trim())).copy(
                        name = name.trim(), venue = venue.trim().ifEmpty { null }, timezoneId = zone.trim().ifEmpty { null },
                        equipmentNotes = equipment.trim().ifEmpty { null }, defaultCalendarId = calendarId.trim().ifEmpty { null },
                    ),
                )
                null
            }
        }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(venue, { venue = it }, label = { Text("Venue") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(zone, { zone = it }, label = { Text("Time zone ID (blank = this phone's)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(onClick = { zone = ZoneId.systemDefault().id }) { Text("Use this phone's zone: ${ZoneId.systemDefault().id}") }
        OutlinedTextField(equipment, { equipment = it }, label = { Text("Equipment notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(calendarId, { calendarId = it }, label = { Text("Calendar ID override (advanced)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerformanceDialog(initial: Performance?, productionId: String, onDismiss: () -> Unit, onDelete: (String) -> Unit, onSave: (Performance) -> Unit) {
    var date by remember { mutableStateOf(initial?.localDate ?: LocalDate.now().toString()) }
    var time by remember { mutableStateOf(initial?.localTime.orEmpty()) }
    var number by remember { mutableStateOf(initial?.number?.toString().orEmpty()) }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var status by remember { mutableStateOf(initial?.status ?: PerformanceStatus.UPCOMING) }
    FormDialog(if (initial == null) "Add performance" else "Edit performance", onDismiss, onDelete = initial?.let { { onDelete(it.id) } }, onSave = {
        val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        val timeText = time.trim()
        val t = if (timeText.isEmpty()) null else runCatching { LocalTime.parse(timeText) }.getOrNull()
        when {
            d == null -> "Date should look like 2026-10-31."
            timeText.isNotEmpty() && t == null -> "Time should look like 19:30."
            else -> {
                onSave(
                    (initial ?: Performance(id = newId(), productionId = productionId, localDate = d.toString())).copy(
                        localDate = d.toString(), localTime = t?.toString()?.take(5), number = number.toIntOrNull(),
                        label = label.trim().ifEmpty { null }, status = status, notes = notes.trim().ifEmpty { null },
                    ),
                )
                null
            }
        }
    }) {
        OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(onClick = { date = LocalDate.now().toString() }) { Text("Today") }
        OutlinedTextField(time, { time = it }, label = { Text("Time, 24-hour (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(4) }, label = { Text("Performance number (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(label, { label = it }, label = { Text("Label (e.g. Opening night)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerformanceStatus.entries.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s.name.lowercase().replace('_', ' ')) }) }
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    }
}

@Composable
private fun ContactDialog(initial: Contact?, onDismiss: () -> Unit, onDelete: (String) -> Unit, onSave: (Contact) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var role by remember { mutableStateOf(initial?.role.orEmpty()) }
    var verified by remember { mutableStateOf(initial?.verified ?: false) }
    FormDialog(if (initial == null) "Add person" else "Edit person", onDismiss, onDelete = initial?.let { { onDelete(it.id) } }, onSave = {
        when {
            name.isBlank() -> "Add a name."
            !EmailValidator.isValid(email) -> "That doesn't look like a valid email address."
            else -> {
                onSave((initial ?: Contact(newId(), name.trim(), email.trim())).copy(name = name.trim(), email = email.trim(), role = role.trim().ifEmpty { null }, verified = verified))
                null
            }
        }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(role, { role = it }, label = { Text("Role (e.g. Stage manager)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        SwitchRow("I've checked this address", "Only verified people are used when you say a name.", verified) { verified = it }
    }
}

@Composable
private fun GroupDialog(initial: RecipientGroup?, contacts: List<Contact>, onDismiss: () -> Unit, onDelete: (String) -> Unit, onSave: (RecipientGroup) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var members by remember { mutableStateOf(initial?.contactIds?.toSet() ?: emptySet()) }
    FormDialog(if (initial == null) "Add group" else "Edit group", onDismiss, onDelete = initial?.let { { onDelete(it.id) } }, onSave = {
        if (name.isBlank()) "Give the group a name."
        else { onSave((initial ?: RecipientGroup(newId(), name.trim())).copy(name = name.trim(), contactIds = contacts.map { it.id }.filter { it in members })); null }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Group name (e.g. Production team)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (contacts.isEmpty()) Note("Add people first, then put them in a group.")
        contacts.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = c.id in members, onCheckedChange = { on -> members = if (on) members + c.id else members - c.id })
                Column { Text(c.name); Note(c.email) }
            }
        }
    }
}
