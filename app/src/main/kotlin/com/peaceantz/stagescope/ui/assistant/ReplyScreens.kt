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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.assistant.AssistantFormatting
import com.peaceantz.stagescope.assistant.ContinueUi
import com.peaceantz.stagescope.assistant.OutboxState
import com.peaceantz.stagescope.shared.actions.ActionCard
import com.peaceantz.stagescope.shared.actions.ActionDraft
import com.peaceantz.stagescope.shared.actions.ActionKind
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.IssueLogDraft
import com.peaceantz.stagescope.shared.actions.KeepDraft
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.protocol.ActionCommandKind
import com.peaceantz.stagescope.shared.protocol.ContinueOutcome
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.PrimaryActionRow
import com.peaceantz.stagescope.ui.components.RotatedContent
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle

/** The assistant's answer: what it says, which model said it, what it cost, and what it prepared for you. */
@Composable
fun ReplyScreen(vm: AssistantViewModel, conversationId: String, angleDegrees: Float, nav: AssistantNavigator) {
    val cache by vm.cache.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val continues by vm.continueStates.collectAsStateWithLifecycle()
    val thread = cache.threads.firstOrNull { it.conversationId == conversationId }
    val listState = rememberScalingLazyListState()
    var confirmSpeak by remember { mutableStateOf(false) }
    var speakNote by remember { mutableStateOf<String?>(null) }
    var continueId by remember { mutableStateOf<String?>(null) }
    val entry = outbox.entries.lastOrNull { it.conversationId == conversationId }
    val now = System.currentTimeMillis()

    RotatedContent(angleDegrees) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        ) {
            if (thread == null) {
                item { Text("Waiting for the answer…", color = MaterialTheme.colorScheme.onBackground) }
                if (entry != null) {
                    item { Hint(AssistantFormatting.outboxLabel(entry)) }
                    if (!entry.state.isFinal) item { ChipButton("Cancel", onClick = { vm.cancelQuestion(entry.requestId) }) }
                    if (entry.state == OutboxState.FAILED) item { ChipButton("Retry", onClick = { vm.retryQuestion(entry.requestId) }) }
                }
                return@ScalingLazyColumn
            }

            // The provider and the *actual* model are always shown: nothing here is ever answered by a substitute.
            item { Hint("${thread.providerLabel} · ${thread.modelId}") }
            if (thread.requestState != RequestState.COMPLETED) item { Hint(thread.requestState.name.lowercase().replace('_', ' ')) }
            thread.summary?.let {
                item { Text(it, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            // One item per paragraph: a single tall item is clipped by the round bezel as a block, paragraph items scale away cleanly.
            thread.detail?.let { d ->
                AssistantFormatting.paragraphs(d).forEach { para ->
                    item { Text(para, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
                }
            }
            if (thread.detailTruncated) item { Hint("The full answer is on your phone.") }
            thread.error?.let { item { Hint(it.message, Tone.BAD) } }

            when (thread.webSearchUsed) {
                true -> item {
                    val names = thread.sources.joinToString { it.title.ifBlank { it.url } }.take(120)
                    Hint("Web search was used." + if (names.isEmpty()) "" else " Sources: $names")
                }
                false -> item { Hint("Web search was on but not used — nothing here is from the web.") }
                null -> Unit
            }
            thread.measurementHeadline?.let { item { Hint("About: $it") } }
            AssistantFormatting.usageLine(thread.usage)?.let { item { Hint(it) } }

            items(thread.actions, key = { it.actionId }) { card ->
                ChipButton(card.title, secondary = card.statusLine, onClick = { nav.action(conversationId, card.actionId) })
            }

            item { SectionLabel("Next") }
            if (confirmSpeak) {
                item { Hint("The speaker is audible to everyone nearby. Speak anyway?", Tone.WARN) }
                item {
                    PrimaryActionRow {
                        CompactGlyphButton("✕", "Don't speak", { confirmSpeak = false })
                        CompactGlyphButton("✓", "Speak anyway", {
                            confirmSpeak = false
                            speakNote = (vm.speak(thread, confirmed = true) as? AssistantViewModel.SpeakDecision.Unavailable)?.reason
                        })
                    }
                }
            } else {
                item {
                    if (speaking) {
                        ChipButton("Stop speaking", onClick = vm::stopSpeaking)
                    } else {
                        ChipButton("Speak this", secondary = "Only when you ask", onClick = {
                            when (val d = vm.speak(thread)) {
                                AssistantViewModel.SpeakDecision.NeedsConfirm -> confirmSpeak = true
                                is AssistantViewModel.SpeakDecision.Unavailable -> speakNote = d.reason
                                AssistantViewModel.SpeakDecision.Started -> speakNote = null
                            }
                        })
                    }
                }
                speakNote?.let { item { Hint(it, Tone.WARN) } }
            }
            item { ChipButton("Ask a follow-up", onClick = { nav.ask(TaskKind.FREE_CHAT, SnapshotOrigin.ASSISTANT, conversationId, null) }) }
            item {
                ChipButton("Continue on phone", secondary = continueWording(continueId?.let { continues[it] }), onClick = {
                    vm.continueOnPhone(conversationId, null) { continueId = it }
                })
            }
            if (entry != null && entry.state == OutboxState.FAILED) item { ChipButton("Retry", onClick = { vm.retryQuestion(entry.requestId) }) }
            item { Hint("Updated ${AssistantFormatting.ago(thread.updatedAtPhoneEpochMs.takeIf { it > 0 } ?: now, now)}") }
        }
    }
}

/** Exactly what the phone said -- "Opened" only when the phone itself confirmed the item is on screen. */
internal fun continueWording(state: ContinueUi?): String = when (state) {
    null -> "Opens this on your phone"
    ContinueUi.Asking -> "Asking your phone…"
    ContinueUi.NoAnswer -> "No answer from your phone"
    is ContinueUi.Done -> when (state.outcome) {
        ContinueOutcome.OPENED -> "Opened on your phone"
        ContinueOutcome.NOTIFICATION_POSTED -> "Sent a notification to your phone — tap it"
        ContinueOutcome.SAVED_NOTIFICATIONS_DISABLED -> "Saved on your phone. Notifications are off — open StageScope there"
        ContinueOutcome.NOT_FOUND -> "That isn't on your phone any more"
    }
}

/** Review of one prepared item (email / calendar event / Keep item / logged issue), with only the controls its state allows. */
@Composable
fun ActionReviewScreen(vm: AssistantViewModel, conversationId: String, actionId: String, angleDegrees: Float, nav: AssistantNavigator) {
    val cache by vm.cache.collectAsStateWithLifecycle()
    val reply by vm.lastActionReply.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val continues by vm.continueStates.collectAsStateWithLifecycle()
    val card = cache.threads.firstOrNull { it.conversationId == conversationId }?.actions?.firstOrNull { it.actionId == actionId }
    val listState = rememberScalingLazyListState()
    var continueId by remember { mutableStateOf<String?>(null) }
    val shownReply = reply?.takeIf { it.actionId == actionId }
    val shownNotice = notice?.takeIf { it.scope == ScreenNotice.forAction(actionId) }?.message

    // Confirm and Cancel are tapped with the draft scrolled to wherever it was being read. The phone's answer (or "waiting", or
    // "no answer") sits right under the status line, so bring that into view when it appears -- a tap must never look like nothing happened.
    LaunchedEffect(shownReply, shownNotice) {
        if (shownReply != null || shownNotice != null) listState.animateScrollToItem(1)
    }

    RotatedContent(angleDegrees) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScalingLazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 28.dp, bottom = 6.dp, start = 16.dp, end = 16.dp),
            ) {
                if (card == null) {
                    item { Text("That item isn't on your watch any more.", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center) }
                    return@ScalingLazyColumn
                }
                // Item 0 = title, item 1 = status; what happened, and anything the person must know, comes before the draft itself --
                // a warning that sits after a long message is a warning nobody reads before tapping Confirm.
                item { Text(card.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                item { Hint(card.statusLine, statusTone(card.state)) }
                card.errorMessage?.let { item { Hint(it, Tone.BAD) } }
                shownReply?.let { item { Hint(it.message, if (it.accepted) Tone.NEUTRAL else Tone.WARN) } }
                shownNotice?.let { item { Hint(it, Tone.WARN) } }
                if (card.state == ActionState.OUTCOME_UNCERTAIN) {
                    item { Hint("Check your ${if (card.kind == ActionKind.EMAIL) "Gmail Sent folder" else "calendar"}, then tell me what you found.") }
                }
                card.missingInformation.forEach { item { Hint("Needs: $it", Tone.WARN) } }
                card.warnings.forEach { item { Hint("Check: $it", Tone.WARN) } }
                if (card.state == ActionState.AWAITING_REVIEW && !card.canConfirmOnWatch) {
                    item { Hint("Too long to review properly on a watch. Open it on your phone to read it all.", Tone.WARN) }
                }
                for ((label, value) in draftLines(card.draft)) {
                    // Paragraph by paragraph (see AssistantFormatting.paragraphs): the whole text is still shown, in order.
                    AssistantFormatting.paragraphs(value).ifEmpty { listOf(value) }.forEachIndexed { i, part ->
                        item { LabeledLine(if (i == 0) label else null, part) }
                    }
                }

                val phoneWording = continueWording(continueId?.let { continues[it] })
                if (card.state == ActionState.AWAITING_REVIEW || card.state == ActionState.AWAITING_PHONE) {
                    item { ChipButton("Continue on phone", secondary = phoneWording, onClick = { vm.continueOnPhone(conversationId, card.actionId) { continueId = it } }) }
                }
                if (card.state == ActionState.AWAITING_REVIEW || card.state == ActionState.AWAITING_INFORMATION) {
                    item {
                        ChipButton("Change it by voice", secondary = "Say what to change", onClick = {
                            nav.ask(taskFor(card.kind), SnapshotOrigin.ASSISTANT, conversationId, card.actionId)
                        })
                    }
                }
                when (card.state) {
                    ActionState.AWAITING_PHONE -> if (card.kind.needsConfirmation) {
                        item { ChipButton("Review again", onClick = { vm.sendAction(card.actionId, ActionCommandKind.REOPEN) }) }
                    }
                    ActionState.FAILED -> {
                        if (card.kind.needsConfirmation) item { ChipButton("Review again", onClick = { vm.sendAction(card.actionId, ActionCommandKind.REOPEN) }) }
                        item { ChipButton("Dismiss", onClick = { vm.sendAction(card.actionId, ActionCommandKind.CANCEL) }) }
                    }
                    else -> Unit
                }
            }
            ActionControlRow(vm, card)
        }
    }
}

private fun statusTone(s: ActionState): Tone = when (s) {
    ActionState.COMPLETED -> Tone.GOOD
    ActionState.FAILED, ActionState.OUTCOME_UNCERTAIN -> Tone.BAD
    ActionState.AWAITING_INFORMATION, ActionState.AWAITING_REVIEW, ActionState.AWAITING_PHONE -> Tone.WARN
    else -> Tone.NEUTRAL
}

/** [label] is null for the continuation of a long value, which reads on from the piece above it. */
@Composable
private fun LabeledLine(label: String?, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        if (label != null) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = chartAnnotationStyle())
        Text(value, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** The whole draft, exactly as it would be sent -- a confirmation is only meaningful if the person can see it all. */
internal fun draftLines(draft: ActionDraft): List<Pair<String, String>> = when (draft) {
    is EmailDraft -> buildList {
        add("From" to (draft.senderAccount ?: "your connected Gmail"))
        add("To" to draft.to.joinToString { it.display() }.ifEmpty { "— none yet" })
        if (draft.cc.isNotEmpty()) add("Cc" to draft.cc.joinToString { it.display() })
        if (draft.bcc.isNotEmpty()) add("Bcc" to draft.bcc.joinToString { it.display() })
        add("Subject" to draft.subject.ifBlank { "(no subject)" })
        add("Message" to draft.body)
    }
    is CalendarDraft -> buildList {
        add("Title" to draft.title)
        when {
            draft.startLocal.isBlank() -> add("When" to "— still needed")
            draft.allDay -> add("When" to "${draft.startLocal.take(10)} (all day) · ${draft.timezoneId}")
            else -> {
                add("Starts" to "${draft.startLocal.replace('T', ' ')} (UTC${draft.startOffset}) · ${draft.timezoneId}")
                add("Ends" to "${draft.endLocal.replace('T', ' ')} (UTC${draft.endOffset})")
            }
        }
        draft.location?.let { add("Where" to it) }
        add("Calendar" to draft.calendarLabel + (draft.accountEmail?.let { " · $it" } ?: ""))
        if (draft.invitees.isNotEmpty()) {
            add("Invitees" to draft.invitees.joinToString { it.display() })
            add("Note" to "Google will email an invitation to them when you confirm.")
        }
        draft.description?.let { add("Notes" to it) }
        draft.assumptions.forEach { add("Assumed" to it) }
    }
    is KeepDraft -> buildList {
        add("Item" to draft.itemText)
        draft.listName?.let { add("List" to it) }
        add("Note" to "Not in Keep yet. Finish it on your phone, then mark it done.")
    }
    is IssueLogDraft -> listOf("Logged" to draft.summary)
}

private fun taskFor(kind: ActionKind): TaskKind = when (kind) {
    ActionKind.EMAIL -> TaskKind.EMAIL_REPORT
    ActionKind.CALENDAR_EVENT -> TaskKind.CALENDAR_EVENT
    ActionKind.KEEP_ITEM -> TaskKind.KEEP_ITEM
    ActionKind.LOG_ISSUE -> TaskKind.LOG_ISSUE
}

/** At most two round controls: the decision this state actually calls for. */
@Composable
private fun ActionControlRow(vm: AssistantViewModel, card: ActionCard?) {
    if (card == null) return
    PrimaryActionRow(modifier = Modifier.padding(bottom = 10.dp, top = 2.dp)) {
        when (card.state) {
            ActionState.AWAITING_REVIEW -> {
                CompactGlyphButton("✕", "Cancel this ${card.kind.label.lowercase()}", { vm.sendAction(card.actionId, ActionCommandKind.CANCEL) })
                // Confirm is bound to the exact revision and content hash shown above; only offered when it can be reviewed in full here.
                if (card.canConfirmOnWatch) CompactGlyphButton("✓", "Confirm", { vm.sendAction(card.actionId, ActionCommandKind.CONFIRM, card) })
            }
            ActionState.AWAITING_INFORMATION -> CompactGlyphButton("✕", "Cancel", { vm.sendAction(card.actionId, ActionCommandKind.CANCEL) })
            ActionState.AWAITING_PHONE -> {
                CompactGlyphButton("✕", "Cancel", { vm.sendAction(card.actionId, ActionCommandKind.CANCEL) })
                CompactGlyphButton("✓", "I did it — mark done", { vm.sendAction(card.actionId, ActionCommandKind.MARK_DONE) })
            }
            ActionState.OUTCOME_UNCERTAIN -> {
                CompactGlyphButton("✕", "It wasn't done", { vm.sendAction(card.actionId, ActionCommandKind.UNCERTAIN_NOT_DONE) })
                CompactGlyphButton("✓", "It was done", { vm.sendAction(card.actionId, ActionCommandKind.UNCERTAIN_WAS_DONE) })
            }
            ActionState.COMPLETED ->
                if (card.kind == ActionKind.LOG_ISSUE) CompactGlyphButton("✕", "Undo this logged issue", { vm.sendAction(card.actionId, ActionCommandKind.UNDO) })
            else -> Unit
        }
    }
}
