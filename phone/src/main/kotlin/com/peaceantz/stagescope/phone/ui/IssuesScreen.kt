package com.peaceantz.stagescope.phone.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.shared.issues.Change
import com.peaceantz.stagescope.shared.issues.IssueExport
import com.peaceantz.stagescope.shared.issues.IssueField
import com.peaceantz.stagescope.shared.issues.IssueFilter
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.issues.IssuePatch
import com.peaceantz.stagescope.shared.issues.IssueSeverity
import com.peaceantz.stagescope.shared.issues.IssueStatus
import com.peaceantz.stagescope.shared.issues.IssueView
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import kotlinx.coroutines.launch
import java.util.UUID

private enum class IssueTab(val label: String) { OPEN("Open"), RESOLVED("Resolved"), ALL("All"), CONFLICTS("Conflicts") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssuesScreen(container: PhoneContainer, openIssue: (String) -> Unit) {
    val context = LocalContext.current
    val ledger by container.data.issues.state.collectAsState()
    val shows by container.data.shows.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(IssueTab.OPEN.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val current = IssueTab.valueOf(tab)
    val library = shows.library
    val selectedPerformance = library.selectedPerformance()

    val filter = IssueFilter(
        performanceId = if (onlySelected) selectedPerformance?.id else null,
        statuses = when (current) {
            IssueTab.OPEN -> setOf(IssueStatus.OPEN, IssueStatus.REOPENED)
            IssueTab.RESOLVED -> setOf(IssueStatus.RESOLVED)
            else -> null
        },
        onlyWithConflicts = current == IssueTab.CONFLICTS,
        query = query,
    )
    val views = IssueLedger.views(ledger, filter)

    ScreenColumn {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IssueTab.entries.forEach { t -> FilterChip(selected = t == current, onClick = { tab = t.name }, label = { Text(t.label) }) }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search issues") })
        if (selectedPerformance != null) {
            SwitchRow("Only the selected performance", selectedPerformance.displayName(), onlySelected) { onlySelected = it }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text("Add an issue") }
            OutlinedButton(enabled = views.isNotEmpty(), onClick = {
                val title = "Issue log" + (selectedPerformance?.let { " — ${it.displayName()}" } ?: "")
                context.startActivity(Handoffs.genericShare(IssueExport.toMarkdown(views, title, { library.production(it)?.name }, { library.performance(it)?.displayName() }), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Share (text)") }
            OutlinedButton(enabled = views.isNotEmpty(), onClick = {
                context.startActivity(Handoffs.genericShare(IssueExport.toCsv(views, { library.production(it)?.name }, { library.performance(it)?.displayName() }), "Issue log (CSV)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Share (CSV)") }
        }
        if (views.isEmpty()) {
            Panel { Text(if (ledger.issues.isEmpty()) "No issues logged yet. Say \"log an issue\" on your watch, or add one here." else "No issues match this filter.") }
        }
        views.forEach { v -> IssueRow(v, library.performance(v.performanceId)?.displayName()) { openIssue(v.id) } }
    }

    if (adding) AddIssueDialog(container, onDismiss = { adding = false })
}

@Composable
private fun IssueRow(v: IssueView, performanceName: String?, onClick: () -> Unit) {
    Panel(modifier = Modifier.clickable(onClick = onClick)) {
        Text(v.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3)
        val meta = listOfNotNull(
            v.statusLabel(), v.severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() }, v.equipment, v.channel?.let { "ch $it" },
            performanceName, formatWhen(v.occurredAtEpochMs ?: v.createdAtEpochMs),
        ).joinToString(" · ")
        Note(meta)
        if (v.conflicts.isNotEmpty()) Note("Edited on two devices — needs your review", tone = Tone.WARN)
    }
}

@Composable
private fun AddIssueDialog(container: PhoneContainer, onDismiss: () -> Unit) {
    var description by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add an issue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("What happened") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(equipment, { equipment = it }, label = { Text("Equipment (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(channel, { channel = it }, label = { Text("Channel (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = description.isNotBlank(), onClick = {
                container.appScope.launch {
                    val lib = container.data.shows.library
                    val ledger = container.data.issues
                    val now = System.currentTimeMillis()
                    val opId = UUID.randomUUID().toString()
                    ledger.update { s ->
                        val issue = IssueOps.create(
                            id = UUID.randomUUID().toString(), replica = s.replicaId, nowEpochMs = now,
                            originalObservation = description.trim(),
                            productionId = lib.selectedProductionId, performanceId = lib.selectedPerformanceId,
                            equipment = equipment.trim().ifEmpty { null }, channel = channel.trim().ifEmpty { null },
                        )
                        IssueLedger.create(s, opId, issue)
                    }
                    container.publisher.publishIssues()
                }
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueDetailScreen(container: PhoneContainer, issueId: String, onClose: () -> Unit) {
    val ledger by container.data.issues.state.collectAsState()
    val shows by container.data.shows.state.collectAsState()
    val state = ledger.issues[issueId]
    if (state == null) {
        ScreenColumn { Panel { Text("That issue isn't on this phone (yet).") } }
        return
    }
    val v = state.view()
    var description by remember(v.revision) { mutableStateOf(v.description) }
    var equipment by remember(v.revision) { mutableStateOf(v.equipment.orEmpty()) }
    var channel by remember(v.revision) { mutableStateOf(v.channel.orEmpty()) }
    var fix by remember(v.revision) { mutableStateOf(v.attemptedFix.orEmpty()) }
    var note by remember(v.revision) { mutableStateOf(v.resolutionNote.orEmpty()) }
    var severity by remember(v.revision) { mutableStateOf(v.severity) }
    var deleting by remember { mutableStateOf(false) }
    val evidence = v.evidenceSnapshotId?.let { container.data.measurements.get(it) }

    fun mutate(block: (com.peaceantz.stagescope.shared.issues.IssueLedgerState, String) -> com.peaceantz.stagescope.shared.issues.IssueLedgerState) {
        container.appScope.launch {
            val opId = UUID.randomUUID().toString()
            container.data.issues.update { block(it, opId) }
            container.publisher.publishIssues()
        }
    }

    ScreenColumn {
        if (v.deleted) Panel("Deleted") { Text("This issue was deleted. It is kept as a marker so the deletion syncs to your watch."); Button(onClick = { mutate { s, op -> IssueLedger.restore(s, op, issueId) } }) { Text("Restore") } }

        if (v.conflicts.isNotEmpty()) {
            Panel("Edited on two devices") {
                Note("You and another device changed the same thing. Nothing was lost — pick the version to keep.", tone = Tone.WARN)
                v.conflicts.forEach { c ->
                    Text(c.field.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                    if (c.field == IssueField.DELETED) {
                        Note("Deleted on one device, edited or kept on another.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { mutate { s, op -> IssueLedger.chooseSibling(s, op, issueId, c.field, 0) } }) { Text("Keep the issue") }
                            OutlinedButton(onClick = { mutate { s, op -> IssueLedger.chooseSibling(s, op, issueId, c.field, 1) } }) { Text("Delete it") }
                        }
                    } else {
                        (listOf(c.shownValue) + c.otherValues).forEachIndexed { i, value ->
                            OutlinedButton(onClick = { mutate { s, op -> IssueLedger.chooseSibling(s, op, issueId, c.field, i) } }, modifier = Modifier.fillMaxWidth()) {
                                Text("Keep: ${value.ifBlank { "(empty)" }}", maxLines = 3)
                            }
                        }
                    }
                }
            }
        }

        Panel("What you reported") {
            Text("“${v.originalObservation}”")
            Note("Your original words are never changed by edits or by AI wording.")
            v.aiWording?.let { Labeled("AI wording (not your words)", it) }
        }

        Panel("Details") {
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(equipment, { equipment = it }, label = { Text("Equipment") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(channel, { channel = it }, label = { Text("Channel") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(fix, { fix = it }, label = { Text("Attempted fix") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("Severity", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = severity == null, onClick = { severity = null }, label = { Text("Not set") })
                IssueSeverity.entries.forEach { s -> FilterChip(selected = severity == s, onClick = { severity = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
            Button(onClick = {
                val patch = IssuePatch(
                    description = description.trim().takeIf { it.isNotEmpty() && it != v.description }?.let { Change(it) },
                    equipment = nullableChange(equipment, v.equipment),
                    channel = nullableChange(channel, v.channel),
                    attemptedFix = nullableChange(fix, v.attemptedFix),
                    severity = if (severity != v.severity) Change(severity) else null,
                )
                mutate { s, op -> IssueLedger.edit(s, op, issueId, patch) }
            }) { Text("Save changes") }
        }

        Panel("Status: ${v.statusLabel()}") {
            if (v.certaintyNote() != null) Note(v.certaintyNote()!!)
            OutlinedTextField(note, { note = it }, label = { Text("Resolution note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.status != IssueStatus.RESOLVED || v.certainty == ResolutionCertainty.TENTATIVE) {
                    Button(onClick = { mutate { s, op -> IssueLedger.resolve(s, op, issueId, ResolutionCertainty.CONFIRMED, note.trim().ifEmpty { null }) } }) { Text("Resolved") }
                }
                if (v.status != IssueStatus.RESOLVED) {
                    OutlinedButton(onClick = { mutate { s, op -> IssueLedger.resolve(s, op, issueId, ResolutionCertainty.TENTATIVE, note.trim().ifEmpty { null }) } }) { Text("Seems resolved") }
                } else {
                    OutlinedButton(onClick = { mutate { s, op -> IssueLedger.reopen(s, op, issueId, note.trim().ifEmpty { null }) } }) { Text("Reopen") }
                }
            }
        }

        Panel("Where and when") {
            Labeled("Production", shows.library.production(v.productionId)?.name ?: "—")
            Labeled("Performance", shows.library.performance(v.performanceId)?.displayName() ?: "—")
            Labeled("Occurred", formatWhen(v.occurredAtEpochMs ?: v.createdAtEpochMs))
            Note("Revision ${v.revision} (counts edits across your phone and watch — not a clock).")
        }

        if (v.evidenceSnapshotId != null) {
            Panel("Attached measurement") {
                Text(evidence?.let { "Snapshot from ${formatWhen(it.capturedAtEpochMs)}${if (it.device.isDemo) " (DEMO data)" else ""}" } ?: "A measurement snapshot was attached (it is no longer stored on this phone).")
                Note("Attached on purpose by the person who logged the issue; StageScope never attaches a measurement on its own.")
            }
        }

        if (!v.deleted) {
            TextButton(onClick = { deleting = true }) { Text("Delete this issue", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete this issue?", text = "It disappears from both devices. A marker is kept so the deletion syncs; you can restore it from here until then.",
            confirmLabel = "Delete", destructive = true, onDismiss = { deleting = false },
            onConfirm = { mutate { s, op -> IssueLedger.delete(s, op, issueId) }; onClose() },
        )
    }
}

/** A cleared text box means "clear the field"; an unchanged one means "don't touch it". */
private fun nullableChange(typed: String, current: String?): Change<String?>? {
    val next = typed.trim().ifEmpty { null }
    return if (next == current) null else Change(next)
}

private fun IssueView.certaintyNote(): String? = when {
    status == IssueStatus.RESOLVED && certainty == ResolutionCertainty.TENTATIVE -> "Marked “seems resolved” — not confirmed. It stays tentative in reports until you confirm."
    else -> null
}
