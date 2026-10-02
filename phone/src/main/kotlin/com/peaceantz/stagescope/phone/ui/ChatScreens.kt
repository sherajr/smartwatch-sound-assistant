package com.peaceantz.stagescope.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.phone.PhoneContainer
import com.peaceantz.stagescope.phone.data.InboxEntry
import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.shared.actions.ActionState
import com.peaceantz.stagescope.shared.assistant.ChatTurn
import com.peaceantz.stagescope.shared.assistant.Conversation
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ToolStatus
import com.peaceantz.stagescope.shared.assistant.TurnRole
import kotlinx.coroutines.launch
import java.util.UUID

// The same states the watch's "needs you" list uses (and the Home screen counts): a Keep item "Ready on phone" is still waiting for the person to finish it.
internal val NEEDS_ATTENTION = setOf(ActionState.AWAITING_REVIEW, ActionState.AWAITING_INFORMATION, ActionState.AWAITING_PHONE, ActionState.OUTCOME_UNCERTAIN)

@Composable
fun ChatsScreen(container: PhoneContainer, openChat: (String) -> Unit) {
    val convs by container.data.conversations.state.collectAsState()
    val actions by container.data.actions.state.collectAsState()
    val inbox by container.data.inbox.state.collectAsState()
    val list = convs.conversations.values.sortedByDescending { it.updatedAtEpochMs }

    ScreenColumn {
        Button(onClick = { openChat(UUID.randomUUID().toString()) }, modifier = Modifier.fillMaxWidth()) { Text("New conversation") }
        if (list.isEmpty()) {
            Panel("Nothing here yet") {
                Text("Ask from your watch (Assistant page, swipe past Ring), or start a conversation here. Drafts, emails and events appear in the conversation that created them, where you review and confirm them.")
            }
        }
        list.forEach { c ->
            val needs = actions.actions.values.count { it.conversationId == c.id && it.state in NEEDS_ATTENTION }
            val running = inbox.entries.values.any { it.request.conversationId == c.id && (it.state == InboxState.RECEIVED || it.state == InboxState.RUNNING) }
            Panel(modifier = Modifier.clickable { openChat(c.id) }) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // The model that actually answered last (as in the conversation and on the watch), not just the one currently configured.
                val lastAnswer = c.turns.lastOrNull { it.role == TurnRole.ASSISTANT }
                Note("${(lastAnswer?.providerId ?: c.providerId).shortLabel} · ${lastAnswer?.modelId ?: c.modelId} · ${formatWhen(c.updatedAtEpochMs)}")
                lastAnswer?.let {
                    Text(it.watchSummary ?: it.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
                if (running) Note("Working…", tone = Tone.WARN)
                if (needs > 0) Note("$needs item${if (needs == 1) "" else "s"} need${if (needs == 1) "s" else ""} your attention", tone = Tone.WARN)
            }
        }
    }
}

/** Each action card is drawn once, under the *latest* turn that mentions it (a later edit mentions the same action again). */
internal fun cardOwners(turns: List<ChatTurn>): Map<String, String> = buildMap { turns.forEach { t -> t.actionIds.forEach { put(it, t.id) } } }

internal fun cardIdsUnder(turn: ChatTurn, owners: Map<String, String>): List<String> = turn.actionIds.distinct().filter { owners[it] == turn.id }

/**
 * Where [actionId]'s card sits in the conversation's flat list: [leadingItems] first, then for every turn one item for the turn
 * itself followed by one item per card drawn under it. Null if that card isn't drawn (unknown id, or the turn fell out of history).
 */
internal fun cardListIndex(turns: List<ChatTurn>, leadingItems: Int, actionId: String, exists: (String) -> Boolean): Int? {
    val owners = cardOwners(turns)
    var index = leadingItems
    for (turn in turns) {
        index++ // the turn's own item
        for (id in cardIdsUnder(turn, owners).filter(exists)) {
            if (id == actionId) return index
            index++
        }
    }
    return null
}

@Composable
fun ChatDetailScreen(container: PhoneContainer, conversationId: String, focus: ChatFocus? = null, openGoogleSetup: () -> Unit, openProviders: () -> Unit) {
    val convs by container.data.conversations.state.collectAsState()
    val actions by container.data.actions.state.collectAsState()
    val inbox by container.data.inbox.state.collectAsState()
    val settings by container.data.settings.state.collectAsState()
    val conv = convs.conversations[conversationId]
    var text by rememberSaveable(conversationId) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()

    val inFlight = inbox.entries.values.firstOrNull {
        it.request.conversationId == conversationId && (it.state == InboxState.RECEIVED || it.state == InboxState.RUNNING)
    }
    // Showing a conversation is what "opened on phone" means -- the watch is only told once this is really on screen.
    LaunchedEffect(conversationId) { container.continuations.onOpened(conversationId, null) }

    val itemCount = (conv?.turns?.size ?: 0) + actions.actions.values.count { it.conversationId == conversationId } + if (inFlight != null) 1 else 0
    // Items drawn before the turns (see the list below): the empty-state panel and the measurements panel.
    val leadingItems = (if (conv == null || conv.turns.isEmpty()) 1 else 0) + (if (conv != null && conv.measurements.isNotEmpty()) 1 else 0)
    val cardOwner = remember(conv) { cardOwners(conv?.turns.orEmpty()) }
    fun cardsOf(turn: ChatTurn) = cardIdsUnder(turn, cardOwner).mapNotNull { actions.actions[it] }
    // A link to one action brings *that action's card* into view once; after that, new messages scroll to the bottom as usual.
    var handledFocus by remember(conversationId) { mutableStateOf<ChatFocus?>(null) }
    LaunchedEffect(itemCount, focus) {
        if (itemCount == 0) return@LaunchedEffect
        val target = focus?.takeIf { it != handledFocus }
            ?.let { f -> cardListIndex(conv?.turns.orEmpty(), leadingItems, f.actionId) { actions.actions[it] != null } }
        if (target != null) {
            handledFocus = focus
            listState.scrollToItem(target)
        } else {
            listState.animateScrollToItem(itemCount.coerceAtLeast(1) - 1)
        }
    }

    val provider = settings.selectedProvider
    val ready = settings.devMode || container.providers.hasKey(provider)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (conv == null || conv.turns.isEmpty()) {
                item { Panel("New conversation") { Text("Ask about the sound, log an issue, or draft an email or calendar event. Nothing is sent or created without your confirmation.") } }
            }
            if (conv != null && conv.measurements.isNotEmpty()) {
                item {
                    Panel("Measurements in this conversation") {
                        conv.measurements.forEach { m ->
                            Note("${if (m.isDemo) "DEMO · " else ""}${formatWhen(m.capturedAtEpochMs)} · ${m.headline.ifBlank { "snapshot" }}")
                        }
                    }
                }
            }
            if (conv != null) {
                val lastAssistant = conv.turns.lastOrNull { it.role == TurnRole.ASSISTANT }
                // One item per turn and one per action card (so a link can scroll to a single card; see the focus effect above).
                conv.turns.forEach { turn ->
                    item(key = turn.id) {
                        TurnView(container, turn, conv, canRetry = turn === lastAssistant && inFlight == null, inbox = turn.requestId?.let { inbox.entries[it] }) { scope.launch { container.retry(it) } }
                    }
                    cardsOf(turn).forEach { rec ->
                        item(key = "action:${rec.actionId}") { ActionCard(container, rec, settings, openGoogleSetup) }
                    }
                }
            }
            if (inFlight != null) {
                item { InFlight(inFlight) { container.appScope.launch { container.cancelRequest(inFlight.requestId) } } }
            }
        }
        HorizontalDivider()
        if (!ready) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Note("${provider.label} isn't set up yet.", Modifier.weight(1f), Tone.WARN)
                TextButton(onClick = openProviders) { Text("Set up") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), maxLines = 5,
                placeholder = { Text("Ask or tell the assistant…") },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = text.isNotBlank() && inFlight == null && ready,
                onClick = {
                    val toSend = text.trim()
                    text = ""
                    container.appScope.launch { container.submitFromPhone(toSend, conversationId = conversationId) }
                },
            ) { Text("Send") }
        }
    }
}

@Composable
private fun InFlight(entry: InboxEntry, onCancel: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (entry.state == InboxState.RECEIVED) "Queued…" else "Thinking…")
                Note("Cancelling stops waiting for the answer. Anything already done is not undone.")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun TurnView(
    container: PhoneContainer, turn: ChatTurn, conv: Conversation, canRetry: Boolean, inbox: InboxEntry?, onRetry: (String) -> Unit,
) {
    val context = LocalContext.current
    val isUser = turn.role == TurnRole.USER
    val header = if (isUser) {
        "You" + when (turn.inputOrigin) {
            InputOrigin.SPEECH_WATCH -> " · spoken on watch (reviewed)"
            InputOrigin.SPEECH_PHONE -> " · spoken on phone"
            InputOrigin.VOICE_MEMO_TRANSCRIBED -> " · voice memo"
            InputOrigin.QUICK_ACTION -> " · quick action"
            else -> ""
        }
    } else {
        "${(turn.providerId ?: conv.providerId).shortLabel} · ${turn.modelId ?: conv.modelId}"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Note(header)
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
        ) {
            SelectionContainer {
                Text(boldMarkdown(turn.text.ifBlank { "(no text)" }), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!isUser) {
            turn.toolOutcomes.forEach { o ->
                Note("${o.toolName}: ${o.resultSummary}", tone = if (o.status == ToolStatus.OK) Tone.NEUTRAL else Tone.WARN)
            }
            when (turn.webSearchUsed) {
                true -> Note("Web search was used.${if (turn.sources.isEmpty()) "" else " Sources:"}")
                false -> Note("Web search was on but the model didn't use it, so nothing here is from the web.")
                null -> Unit
            }
            turn.sources.forEach { s ->
                Text(
                    "↗ ${s.title.ifBlank { s.url }}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { openUrl(context, s.url) },
                )
            }
            turn.usage?.let { u ->
                if (u.inputTokens > 0 || u.outputTokens > 0) {
                    val cost = u.reportedCostMicros?.let { formatUsd(it) + " reported" } ?: u.estimatedCostMicros?.let { formatUsd(it) + " est." } ?: "cost unknown"
                    Note("${u.inputTokens} in · ${u.outputTokens} out tokens · $cost")
                }
            }
            turn.error?.let { e ->
                Note(e.message, tone = Tone.BAD)
                if (canRetry && turn.requestId != null && inbox != null) {
                    OutlinedButton(onClick = { onRetry(turn.requestId!!) }) { Text("Retry") }
                }
            }
            if (turn.text.isNotBlank() && turn.error == null) {
                Row {
                    TextButton(onClick = { container.speech.speak(turn.text) }) { Text("Speak") }
                    TextButton(onClick = { container.speech.stop() }) { Text("Stop") }
                    TextButton(onClick = { copyToClipboard(context, "StageScope reply", turn.text) }) { Text("Copy") }
                }
            }
        }
    }
}

/** Renders `**bold**` spans (the only inline formatting the system prompt asks for); anything else stays plain. */
fun boldMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("**", i)
        if (open < 0) { append(text.substring(i)); break }
        val close = text.indexOf("**", open + 2)
        if (close < 0) { append(text.substring(i)); break }
        append(text.substring(i, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(open + 2, close)) }
        i = close + 2
    }
}
