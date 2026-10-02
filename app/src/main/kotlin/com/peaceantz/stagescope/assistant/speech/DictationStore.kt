package com.peaceantz.stagescope.assistant.speech

import com.peaceantz.stagescope.shared.store.PersistentState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The tiny amount of dictation state worth surviving a closed app or a killed process: the system screen that is currently
 * open (with the context it will report back into) and the one unsent draft. Plain JSON through [PersistentState], like the
 * assistant's other stores. No audio is ever kept here -- only the words, once they exist.
 */
class DictationStore(file: File) {
    private val store = PersistentState(file, DictationFile.serializer(), 1, { DictationFile() })

    /** The whole file as a flow; consumers pick `.draft` (the unsent words) or `.inFlight` out of it. */
    val state: StateFlow<DictationFile> = store.state

    val inFlight: InFlightDictation? get() = store.value.inFlight
    val currentDraft: DictationDraft? get() = store.value.draft

    suspend fun setInFlight(inFlight: InFlightDictation) { store.update { it.copy(inFlight = inFlight) } }

    /** Forgets the open screen's record -- only if it is still attempt [token]'s (null = whatever is there). An older attempt never erases a newer one's. */
    suspend fun clearInFlight(token: String? = null) {
        store.update { if (it.inFlight == null || (token != null && it.inFlight.token != token)) it else it.copy(inFlight = null) }
    }

    /** Saves the words as the one unsent draft and, in the same write, forgets the screen that produced them. */
    suspend fun commitDraft(draft: DictationDraft) { store.update { it.copy(inFlight = null, draft = draft) } }

    /** Forgets the draft -- but only if it is still [sessionId]'s: a newer draft is never removed by an older one finishing. */
    suspend fun clearDraft(sessionId: String) {
        store.update { if (it.draft?.request?.sessionId == sessionId) it.copy(draft = null) else it }
    }
}
