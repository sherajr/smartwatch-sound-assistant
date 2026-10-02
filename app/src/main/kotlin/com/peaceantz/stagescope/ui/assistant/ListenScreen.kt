package com.peaceantz.stagescope.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.assistant.speech.InputMethod
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.PrimaryActionRow
import com.peaceantz.stagescope.ui.components.RotatedContent
import kotlinx.coroutines.delay

/**
 * The full-screen asking flow: the watch's own dictation screen opens (this app does not record), the words come back here to be
 * **checked**, and only an explicit Send (or Log) does anything with them. At most two round controls sit in the lower row, as on
 * every page; everything else is a list chip.
 *
 * This screen starts a dictation **once per visit and never again by itself**: the "already started" flag survives the Activity being
 * recreated or the process dying, so a restored screen just shows where things are -- it never reopens the microphone.
 */
@Composable
fun ListenScreen(
    vm: AssistantViewModel,
    task: TaskKind,
    origin: SnapshotOrigin,
    memoId: String?,
    conversationId: String?,
    editsActionId: String?,
    resumeDraft: Boolean,
    angleDegrees: Float,
    onClose: () -> Unit,
) {
    val state by vm.listen.collectAsStateWithLifecycle()
    val notice = vm.notice.collectAsStateWithLifecycle().value?.takeIf { it.scope == ScreenNotice.LISTEN }?.message

    // One-shot per visit, saved across recreation and process death. True on the very first frame of a *restored* screen.
    var begun by rememberSaveable { mutableStateOf(false) }
    val restored = remember { begun }

    LaunchedEffect(Unit) {
        if (!begun) {
            val opened = when {
                memoId != null -> vm.reviewMemo(memoId)
                resumeDraft -> vm.resumeDraft()
                else -> { vm.startListening(task, origin, attach = true, conversationId = conversationId, editsActionId = editsActionId); true }
            }
            begun = true
            if (!opened) onClose() // the transcript / draft is gone: nothing to show
        }
    }
    // Until this visit has begun, never show whatever an earlier visit left in the ViewModel (one stale frame of old words).
    val shown = if (begun) state else ListenUi.Preparing

    // Back to wherever the person came from once the flow has finished (sent, cancelled, closed). Only after it has actually
    // shown something: the very first frame is Idle too, and must not close the screen it just opened.
    var sawActivity by remember { mutableStateOf(false) }
    LaunchedEffect(shown, begun) {
        if (!begun) return@LaunchedEffect
        if (shown !is ListenUi.Idle) sawActivity = true else if (sawActivity || restored) onClose()
    }
    KeepScreenOnEffect(enabled = shown.isBusy)

    RotatedContent(angleDegrees) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val s = shown) {
                    ListenUi.Idle, ListenUi.Preparing -> Hint("Getting ready…")
                    is ListenUi.Listening -> ListeningBody(s)
                    is ListenUi.Review -> ReviewBody(vm, s)
                    is ListenUi.Logged -> LoggedBody(s)
                    is ListenUi.Notice -> NoticeBody(vm, s, notice)
                }
            }
            ControlRow(vm, shown, onClose)
        }
    }
}

@Composable
private fun ListeningBody(s: ListenUi.Listening) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        MicButton(onClick = {}, enabled = false, size = 56.dp)
        Text(if (s.method == InputMethod.KEYBOARD) "Text entry is open" else "Dictation is open", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Hint(if (s.method == InputMethod.KEYBOARD) "Type on the watch's keyboard, then confirm." else "Speak on the watch's dictation screen, then confirm. Swipe right there to cancel.")
        Hint("Measurement is paused until you're done.")
    }
}

@Composable
private fun ReviewBody(vm: AssistantViewModel, s: ListenUi.Review) {
    val listState = rememberScalingLazyListState()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDiscard) { if (confirmDiscard) { delay(4_000); confirmDiscard = false } }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 26.dp, bottom = 8.dp, start = 18.dp, end = 18.dp),
    ) {
        item { SectionLabel("Check your words") }
        item { Text("“${s.transcript}”", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        // Warnings go before anything else the person might act on: Send is on screen the whole time.
        s.note?.let { item { Hint(it, Tone.WARN) } }
        item { Hint(s.measurementNote) }
        item { Hint("Source: ${s.source}") }
        if (s.canLogLocally) {
            item { ChipButton("Ask the AI instead", secondary = "Sends it to your phone's assistant", onClick = vm::send, enabled = !s.sending) }
        }
        item { ChipButton("Type instead", secondary = "Replaces these words with ones you type", onClick = vm::typeInstead, enabled = !s.sending) }
        // The only way to get rid of words you don't want (they are kept until you do).
        item { ChipButton(if (confirmDiscard) "Tap again to discard" else "Discard", onClick = { if (confirmDiscard) vm.discardReview() else confirmDiscard = true }, enabled = !s.sending) }
    }
}

@Composable
private fun NoticeBody(vm: AssistantViewModel, s: ListenUi.Notice, phoneNote: String?) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 8.dp, start = 20.dp, end = 20.dp),
    ) {
        item { Text(s.text.message, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        phoneNote?.let { item { Hint(it, Tone.WARN) } }
        if (s.text.offerTyping) item { ChipButton("Type instead", secondary = "Use the watch's keyboard", onClick = vm::typeInstead) }
        if (s.text.offerPhone) item { ChipButton("Continue on phone", secondary = "Open StageScope there to type it", onClick = vm::continueOnPhoneToType) }
    }
}

@Composable
private fun LoggedBody(s: ListenUi.Logged) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (s.undone) "Undone" else "Logged", color = MaterialTheme.colorScheme.onBackground)
        Text(s.issue.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 4)
        if (s.undone) {
            Hint("Removed from the issue log on this watch and your phone.")
        } else {
            if (s.issue.tentativelyResolved) Hint("Saved as “seems resolved” — not confirmed.", Tone.WARN)
            Hint("Saved on this watch; it syncs to your phone. Undo for 10 s.")
        }
    }
}

@Composable
private fun ControlRow(vm: AssistantViewModel, state: ListenUi, onClose: () -> Unit) {
    PrimaryActionRow(modifier = Modifier.padding(bottom = 10.dp, top = 2.dp)) {
        when (val s = state) {
            is ListenUi.Listening -> CompactGlyphButton("✕", "Cancel", vm::cancelListening)
            is ListenUi.Review -> {
                CompactGlyphButton("●", "Dictate again", vm::redo, enabled = !s.sending)
                if (s.canLogLocally) CompactGlyphButton("✓", "Log it now", vm::logLocally, enabled = !s.sending)
                else CompactGlyphButton("▶", "Send to the assistant", vm::send, enabled = !s.sending)
            }
            is ListenUi.Logged -> {
                if (!s.undone) CompactGlyphButton("✕", "Undo", vm::undoLogged)
                CompactGlyphButton("✓", "Done", { vm.closeListening(); onClose() })
            }
            is ListenUi.Notice -> {
                CompactGlyphButton("✕", "Close", { vm.closeListening(); onClose() })
                if (s.text.canRetry) CompactGlyphButton("●", "Try again", vm::retry)
            }
            ListenUi.Idle, ListenUi.Preparing -> CompactGlyphButton("✕", "Cancel", { vm.closeListening(); onClose() })
        }
    }
}
