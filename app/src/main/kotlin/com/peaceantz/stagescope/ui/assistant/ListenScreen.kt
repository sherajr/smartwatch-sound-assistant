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
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.components.CompactGlyphButton
import com.peaceantz.stagescope.ui.components.KeepScreenOnEffect
import com.peaceantz.stagescope.ui.components.PrimaryActionRow
import com.peaceantz.stagescope.ui.components.RotatedContent
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import kotlinx.coroutines.delay

/**
 * The full-screen voice flow: Listening -> (transcribing on the phone, if the watch can't) -> Review -> Send. Nothing
 * records until this screen is showing, and nothing is sent until the person has read the words and tapped Send. At most two
 * round controls sit in the lower row, as on every page.
 */
@Composable
fun ListenScreen(
    vm: AssistantViewModel,
    task: TaskKind,
    origin: SnapshotOrigin,
    memoId: String?,
    conversationId: String?,
    editsActionId: String?,
    angleDegrees: Float,
    onClose: () -> Unit,
) {
    val state by vm.listen.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    val begin = { vm.startListening(task, origin, attach = true, conversationId = conversationId, editsActionId = editsActionId) }
    val requestAndBegin = rememberAudioPermissionRequester(onGranted = begin)

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            if (memoId != null) vm.reviewMemo(memoId) else if (!state.isBusy) requestAndBegin()
        }
    }
    // Back to wherever the person came from once the flow has finished (sent, cancelled, closed). Only after it has actually
    // shown something: the very first frame is Idle too, and must not close the screen it just opened.
    var sawActivity by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is ListenUi.Idle) sawActivity = true else if (sawActivity) onClose()
    }
    KeepScreenOnEffect(enabled = state.isBusy)

    RotatedContent(angleDegrees) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val s = state) {
                    ListenUi.Idle, ListenUi.Preparing -> Hint("Getting ready…")
                    is ListenUi.Listening -> ListeningBody(s, task)
                    ListenUi.Transcribing -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                        Text("Transcribing…", color = MaterialTheme.colorScheme.onBackground)
                        Hint("Your phone is turning the recording into text. Nothing is sent yet.")
                    }
                    is ListenUi.Review -> ReviewBody(vm, s)
                    is ListenUi.Logged -> LoggedBody(s)
                    is ListenUi.Notice -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp)) {
                        Text(s.message, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                    }
                }
            }
            ControlRow(vm, state, requestAndBegin, onClose)
        }
    }
}

@Composable
private fun ListeningBody(s: ListenUi.Listening, task: TaskKind) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        MicButton(onClick = {}, enabled = false, size = 56.dp)
        Text(if (s.usingRecorder) "Recording…" else "Listening…", color = MaterialTheme.colorScheme.onBackground)
        Hint(task.label)
        if (!s.partial.isNullOrBlank()) {
            Text(s.partial, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
        } else {
            Hint(if (s.usingRecorder) "Speak, then tap ✓. Your phone will turn it into text." else "Speak now. It stops when you pause.")
        }
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
        item { SectionLabel("Check what I heard") }
        item { Text("“${s.transcript}”", color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        item { Hint(s.measurementNote) }
        s.engine?.let { item { Hint("Heard by: $it") } }
        if (s.canLogLocally) {
            item { ChipButton("Ask the AI instead", secondary = "Sends it to your phone's assistant", onClick = { vm.send(s.transcript) }) }
        }
        // The only way to get rid of a transcript you don't want (a voice memo's transcript stays on the watch until you do).
        item { ChipButton(if (confirmDiscard) "Tap again to discard" else "Discard", onClick = { if (confirmDiscard) vm.discardReview() else confirmDiscard = true }) }
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
private fun ControlRow(vm: AssistantViewModel, state: ListenUi, retry: () -> Unit, onClose: () -> Unit) {
    PrimaryActionRow(modifier = Modifier.padding(bottom = 10.dp, top = 2.dp)) {
        when (val s = state) {
            is ListenUi.Listening -> {
                CompactGlyphButton("✕", "Cancel", vm::cancelListening)
                CompactGlyphButton("✓", "Done speaking", vm::finishListening)
            }
            ListenUi.Transcribing -> CompactGlyphButton("✕", "Cancel", vm::cancelListening)
            is ListenUi.Review -> {
                CompactGlyphButton("●", "Record again", vm::redo)
                if (s.canLogLocally) CompactGlyphButton("✓", "Log it now", { vm.logLocally(s.transcript) })
                else CompactGlyphButton("▶", "Send to the assistant", { vm.send(s.transcript) })
            }
            is ListenUi.Logged -> {
                if (!s.undone) CompactGlyphButton("✕", "Undo", vm::undoLogged)
                CompactGlyphButton("✓", "Done", { vm.closeListening(); onClose() })
            }
            is ListenUi.Notice -> {
                CompactGlyphButton("✕", "Close", { vm.closeListening(); onClose() })
                if (s.canRetry) CompactGlyphButton("●", if (s.needsPermission) "Allow the microphone" else "Try again", retry)
            }
            ListenUi.Idle, ListenUi.Preparing -> CompactGlyphButton("✕", "Cancel", { vm.closeListening(); onClose() })
        }
    }
}
