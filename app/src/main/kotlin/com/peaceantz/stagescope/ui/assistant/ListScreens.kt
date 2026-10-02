package com.peaceantz.stagescope.ui.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.assistant.AssistantFormatting
import com.peaceantz.stagescope.assistant.AttentionItem
import com.peaceantz.stagescope.assistant.MemoState
import com.peaceantz.stagescope.assistant.OutboxState
import com.peaceantz.stagescope.shared.issues.IssueFilter
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueSeverity
import com.peaceantz.stagescope.shared.issues.IssueStatus
import com.peaceantz.stagescope.shared.issues.IssueView
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.PrimaryActionRow
import com.peaceantz.stagescope.ui.components.RotatedContent
import kotlinx.coroutines.delay

/** Everything that needs a decision: drafts to review, questions that went stale or failed, transcripts to check. */
@Composable
fun TasksScreen(vm: AssistantViewModel, angleDegrees: Float, nav: AssistantNavigator) {
    val attention by vm.attention.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("Needs you", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }
            if (attention.isEmpty()) item { Hint("Nothing is waiting for you.") }
            for (a in attention) {
                when (a) {
                    is AttentionItem.Action -> item(key = a.key) {
                        ChipButton(a.card.title, secondary = "${a.card.statusLine} · ${a.conversationTitle}", onClick = { nav.action(a.conversationId, a.card.actionId) })
                    }
                    is AttentionItem.Question -> {
                        val e = a.entry
                        item(key = a.key) { Hint("${e.request.userText.take(60)} — ${AssistantFormatting.outboxLabel(e)}", Tone.WARN) }
                        if (e.state == OutboxState.STALE) {
                            item(key = a.key + ":send") { ChipButton("Send it anyway", onClick = { vm.sendStale(e.requestId) }) }
                        } else {
                            item(key = a.key + ":retry") { ChipButton("Retry", onClick = { vm.retryQuestion(e.requestId) }) }
                        }
                        item(key = a.key + ":discard") { ChipButton("Discard", onClick = { vm.discardQuestion(e.requestId) }) }
                    }
                    is AttentionItem.Memo -> item(key = a.key) {
                        val m = a.memo
                        if (m.state == MemoState.TRANSCRIPT_READY) {
                            ChipButton("Check transcript", secondary = m.transcript?.take(80), onClick = { nav.reviewMemo(m.memoId) })
                        } else {
                            // Opens the list, where a failed recording can be deleted deliberately -- a tap here never discards anything.
                            ChipButton("Voice memo failed", secondary = m.error, onClick = nav.memos)
                        }
                    }
                }
            }
        }
    }
}

/** The watch's own issue log: works with no phone, no network, no AI. */
@Composable
fun IssuesScreen(vm: AssistantViewModel, angleDegrees: Float, nav: AssistantNavigator) {
    val ledger by vm.issueLedger.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val views = IssueLedger.views(ledger, IssueFilter())
    val open = views.filter { it.isOpen }
    val resolved = views.filter { !it.isOpen }
    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("Issue log", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }
            item { ChipButton("Log an issue", secondary = "Say what happened", onClick = { nav.ask(TaskKind.LOG_ISSUE, SnapshotOrigin.ASSISTANT, null, null) }) }
            if (views.isEmpty()) item { Hint("No issues logged yet.") }
            if (open.isNotEmpty()) item { SectionLabel("Open (${open.size})") }
            for (v in open) item(key = v.id) { IssueRow(v, cache.shows?.performances?.firstOrNull { it.id == v.performanceId }?.label) { nav.issue(v.id) } }
            if (resolved.isNotEmpty()) item { SectionLabel("Resolved (${resolved.size})") }
            for (v in resolved) item(key = v.id) { IssueRow(v, null) { nav.issue(v.id) } }
        }
    }
}

@Composable
private fun IssueRow(v: IssueView, performance: String?, onClick: () -> Unit) {
    val meta = listOfNotNull(
        v.statusLabel(), v.severity?.name?.lowercase()?.replaceFirstChar { it.uppercase() }, v.equipment, v.channel?.let { "ch $it" }, performance,
    ).joinToString(" · ")
    ChipButton(v.description, secondary = if (v.conflicts.isEmpty()) meta else "$meta · edited on two devices", onClick = onClick)
}

@Composable
fun IssueDetailScreen(vm: AssistantViewModel, issueId: String, angleDegrees: Float, onClose: () -> Unit) {
    val ledger by vm.issueLedger.collectAsStateWithLifecycle()
    val issue = ledger.issues[issueId]?.view()?.takeIf { !it.deleted }
    val listState = rememberScalingLazyListState()
    var confirmDelete by remember { mutableStateOf(false) }
    // The armed state lapses by itself, so a stray earlier tap can't turn a later one into a delete.
    LaunchedEffect(confirmDelete) { if (confirmDelete) { delay(CONFIRM_WINDOW_MS); confirmDelete = false } }
    RotatedContent(angleDegrees) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScalingLazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 28.dp, bottom = 6.dp, start = 16.dp, end = 16.dp),
            ) {
                if (issue == null) {
                    item { Text("That issue isn't on this watch.", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center) }
                    return@ScalingLazyColumn
                }
                item { Text(issue.description, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                item { Hint(issue.statusLabel(), if (issue.isOpen) Tone.WARN else Tone.GOOD) }
                issue.equipment?.let { item { Hint("Equipment: $it") } }
                issue.channel?.let { item { Hint("Channel: $it") } }
                issue.severity?.let { s: IssueSeverity -> item { Hint("Severity: ${s.name.lowercase()}") } }
                issue.attemptedFix?.let { item { Hint("Attempted fix: $it") } }
                issue.resolutionNote?.let { item { Hint("Note: $it") } }
                item { Hint("You said: “${issue.originalObservation}”") }
                if (issue.certainty == ResolutionCertainty.TENTATIVE && issue.status == IssueStatus.RESOLVED) {
                    item { Hint("Marked “seems resolved” — not confirmed.", Tone.WARN) }
                }
                if (issue.conflicts.isNotEmpty()) item { Hint("Edited on two devices. Review it on your phone to choose.", Tone.WARN) }
                if (issue.isOpen) {
                    item { ChipButton("Seems resolved", onClick = { vm.resolveIssue(issue.id, ResolutionCertainty.TENTATIVE) }) }
                    item { ChipButton("Resolved", onClick = { vm.resolveIssue(issue.id, ResolutionCertainty.CONFIRMED) }) }
                } else {
                    if (issue.certainty == ResolutionCertainty.TENTATIVE) {
                        item { ChipButton("Confirm resolved", onClick = { vm.resolveIssue(issue.id, ResolutionCertainty.CONFIRMED) }) }
                    }
                    item { ChipButton("Reopen", onClick = { vm.reopenIssue(issue.id) }) }
                }
                // Deleting is the one thing here that can't be undone from the watch, so it is a labelled chip that needs a second tap --
                // never a round ✕, which means "close" everywhere else in the app.
                item {
                    ChipButton(
                        if (confirmDelete) "Tap again to delete" else "Delete this issue",
                        secondary = if (confirmDelete) "Removes it from the log on this watch and your phone" else null,
                        onClick = { if (confirmDelete) { vm.deleteIssue(issueId); onClose() } else confirmDelete = true },
                    )
                }
            }
            PrimaryActionRow(modifier = Modifier.padding(bottom = 10.dp, top = 2.dp)) {
                CompactGlyphButton("✓", "Done", onClose)
            }
        }
    }
}

@Composable
fun MemosScreen(vm: AssistantViewModel, angleDegrees: Float, nav: AssistantNavigator) {
    val memos by vm.memos.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val now = System.currentTimeMillis()
    // A recording is the person's own words and may be the only copy: deleting one takes two taps, and the first lapses by itself.
    var armedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armedId) { if (armedId != null) { delay(CONFIRM_WINDOW_MS); armedId = null } }
    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            item { Text("Voice memos", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold) }
            item { Hint("Short recordings kept only until your phone has turned them into text. No audio is kept after that.") }
            if (memos.memos.isEmpty()) item { Hint("None waiting.") }
            for (m in memos.memos.sortedByDescending { it.createdAtEpochMs }) {
                item(key = m.memoId) {
                    val label = when (m.state) {
                        MemoState.PENDING_PHONE -> "Waiting for your phone"
                        MemoState.UPLOADING -> "Sending to your phone…"
                        MemoState.TRANSCRIBING -> "Your phone is transcribing…"
                        MemoState.TRANSCRIPT_READY -> "Transcript ready — tap to check"
                        MemoState.FAILED -> m.error ?: "Failed"
                        MemoState.SENT -> "Sent"
                    }
                    // Being sent / transcribed right now: not deletable, unless it has been stuck so long the phone has clearly lost it.
                    val phoneIsOnIt = (m.state == MemoState.UPLOADING || m.state == MemoState.TRANSCRIBING) && now - m.createdAtEpochMs < IN_FLIGHT_GRACE_MS
                    val armed = armedId == m.memoId
                    ChipButton(
                        label = m.transcript?.take(60) ?: "${m.durationMs / 1000} s recording",
                        secondary = if (armed) "Tap again to delete this recording" else "$label · ${AssistantFormatting.ago(m.createdAtEpochMs, now)}",
                        onClick = {
                            when {
                                m.state == MemoState.TRANSCRIPT_READY -> nav.reviewMemo(m.memoId)
                                phoneIsOnIt -> Unit
                                armed -> { armedId = null; vm.deleteMemo(m.memoId) }
                                else -> armedId = m.memoId
                            }
                        },
                    )
                }
            }
            item { Hint("Tap a transcript to check, send or discard it. Tap a waiting or failed recording twice to delete it.") }
        }
    }
}

private const val CONFIRM_WINDOW_MS = 4_000L
private const val IN_FLIGHT_GRACE_MS = 5 * 60_000L
