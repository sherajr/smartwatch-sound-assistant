package com.peaceantz.stagescope.ui.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.assistant.AssistantFormatting
import com.peaceantz.stagescope.assistant.MemoState
import com.peaceantz.stagescope.assistant.StatusSeverity
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle

private val QUICK_TASKS = listOf(
    TaskKind.ANALYZE_SOUND to "Analyze this sound",
    TaskKind.LOG_ISSUE to "Log an issue",
    TaskKind.EMAIL_REPORT to "Email show report",
    TaskKind.EMAIL_ISSUE to "Email about an issue",
    TaskKind.KEEP_ITEM to "Add to Keep",
    TaskKind.CALENDAR_EVENT to "Add calendar event",
)

/**
 * The third pager page, after ANALYZER and RING. The big microphone opens the listening screen (it never records by
 * itself); task cards start the same flow with the task preselected. This page is a scrolling list so the crown scrolls
 * it -- it deliberately does *not* rotate the instrument like the two measurement pages do -- and it holds no fixed lower
 * action row at all, so the round-screen limit of two bottom controls can't be exceeded.
 */
@Composable
fun AssistantScreen(vm: AssistantViewModel, angleDegrees: Float, nav: AssistantNavigator) {
    val reachable by vm.reachable.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val mode by vm.audioMode.collectAsStateWithLifecycle()
    val inFlight by vm.inFlight.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val providerChip by vm.providerChip.collectAsStateWithLifecycle()
    val showChip by vm.showChip.collectAsStateWithLifecycle()
    val ledger by vm.issueLedger.collectAsStateWithLifecycle()
    val memos by vm.memos.collectAsStateWithLifecycle()
    val listenState by vm.listen.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    val status = AssistantFormatting.pageStatus(reachable, cache.providers, mode, inFlight.firstOrNull())
    val openIssues = ledger.issues.values.count { v -> v.view().let { !it.deleted && it.isOpen } }
    val pendingMemos = memos.memos.count { it.state != MemoState.SENT }
    val latest = cache.threads.firstOrNull()

    Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angleDegrees }, contentAlignment = Alignment.Center) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 28.dp, bottom = 30.dp, start = 14.dp, end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    "ASSISTANT ›", style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = nav.settings).semantics {
                        contentDescription = "Open assistant settings and setup"
                        role = Role.Button
                    },
                )
            }
            item { MicButton(onClick = { nav.ask(TaskKind.FREE_CHAT, SnapshotOrigin.ASSISTANT, null, null) }) }
            item {
                Hint(
                    if (listenState.isBusy) "Listening… tap to open" else status.text,
                    tone = when (status.severity) { StatusSeverity.OK -> Tone.NEUTRAL; StatusSeverity.NOTE -> Tone.WARN; StatusSeverity.PROBLEM -> Tone.BAD },
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            items(inFlight, key = { it.requestId }) { e ->
                // What is being worked on, then where it is; tapping cancels it (the label says so).
                ChipButton(
                    e.request.userText.ifBlank { e.request.taskKind.label }.take(60),
                    secondary = "${AssistantFormatting.outboxLabel(e)} · tap to cancel",
                    onClick = { vm.cancelQuestion(e.requestId) },
                )
            }

            item { ChipButton(providerChip, onClick = nav.providers, secondary = "AI provider") }
            item { ChipButton(showChip, onClick = nav.shows, secondary = "Show / performance") }

            if (latest != null) {
                item {
                    ChipButton(
                        label = latest.summary ?: latest.title, secondary = "${latest.providerLabel} · ${latest.modelId}",
                        onClick = { nav.reply(latest.conversationId) },
                    )
                }
            }
            if (attention.isNotEmpty()) {
                item { ChipButton("${attention.size} need${if (attention.size == 1) "s" else ""} you", secondary = "Drafts to review, questions to decide", onClick = nav.tasks) }
            }

            item { SectionLabel("Ask about…") }
            items(QUICK_TASKS, key = { it.first.name }) { (task, label) ->
                ChipButton(label, onClick = { nav.ask(task, SnapshotOrigin.ASSISTANT, null, null) })
            }

            item { SectionLabel("On this watch") }
            item { ChipButton("Issue log", secondary = if (openIssues == 0) "No open issues" else "$openIssues open", onClick = nav.issues) }
            if (pendingMemos > 0) item { ChipButton("Voice memos", secondary = "$pendingMemos waiting", onClick = nav.memos) }
            item { ChipButton("Settings & setup", onClick = nav.settings) }
        }
    }
}
