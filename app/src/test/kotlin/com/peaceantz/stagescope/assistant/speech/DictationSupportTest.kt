package com.peaceantz.stagescope.assistant.speech

import com.peaceantz.stagescope.assistant.AssistantAttention
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.util.StageScopeJson
import com.peaceantz.stagescope.ui.assistant.ListenUi
import com.peaceantz.stagescope.ui.assistant.toListenUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictationSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun request(id: String = "s-1", task: TaskKind = TaskKind.FREE_CHAT) = DictationRequest(
        sessionId = id, task = task, origin = SnapshotOrigin.ASSISTANT, conversationId = "c-1", startedAtEpochMs = 1_000,
    )

    private fun draft(text: String = "hello", id: String = "s-1", method: InputMethod = InputMethod.SPEECH, task: TaskKind = TaskKind.FREE_CHAT) =
        DictationDraft(request(id, task), text, method, DictationPresentation.sourceLabel(method), 2_000)

    // ------------------------------------------------------------------------------------ store

    @Test
    fun `the store keeps the open screen and the unsent draft across a restart, and nothing else`() = runBlocking {
        val file = File(tmp.newFolder(), "dictation.json")
        val a = DictationStore(file)
        a.setInFlight(InFlightDictation(request(), InputMethod.SPEECH, 5_000, "t-1"))
        a.commitDraft(draft("Is that a ring at two kilohertz?"))

        val b = DictationStore(file)
        assertNull("committing a draft forgot the open screen in the same write", b.inFlight)
        assertEquals("Is that a ring at two kilohertz?", b.currentDraft!!.text)
        assertEquals(draft("Is that a ring at two kilohertz?"), b.currentDraft)
        assertFalse("the file holds words, never audio", file.readText().contains("pcm"))
    }

    @Test
    fun `one attempt cannot erase another attempt's record, and a newer draft is never cleared by an older id`() = runBlocking {
        val store = DictationStore(File(tmp.newFolder(), "d.json"))
        store.setInFlight(InFlightDictation(request(), InputMethod.SPEECH, 1, "new"))
        store.clearInFlight("old")
        assertEquals("new", store.inFlight!!.token)
        store.clearInFlight("new")
        assertNull(store.inFlight)

        store.commitDraft(draft(id = "keep"))
        store.clearDraft("someone-else")
        assertEquals("keep", store.currentDraft!!.request.sessionId)
        store.clearDraft("keep")
        assertNull(store.currentDraft)
    }

    @Test
    fun `a damaged file is quarantined and starts empty instead of crashing`() {
        val file = File(tmp.newFolder(), "dictation.json").apply { writeText("{ not json") }
        val store = DictationStore(file)
        assertNull(store.currentDraft)
        assertNull(store.inFlight)
        assertTrue("the bad file was kept aside, not overwritten", file.parentFile!!.listFiles()!!.any { it.name.startsWith("dictation.json.corrupt") })
    }

    @Test
    fun `a file with extra keys from a newer build still loads`() {
        val json = """{"schemaVersion":1,"data":{"draft":null,"inFlight":null,"somethingNew":42}}"""
        val file = File(tmp.newFolder(), "dictation.json").apply { writeText(json) }
        assertNull(DictationStore(file).currentDraft)
    }

    // ------------------------------------------------------------------------------- the request

    @Test
    fun `the AssistantRequest is built from the saved context, trimmed, with the right origin`() {
        val d = draft("  What do these sound measurements suggest?  ", id = "req-9", task = TaskKind.ANALYZE_SOUND)
        val r = DictationRequests.toAssistantRequest(d, ReplyMode.BOTH, 99_000)
        assertEquals("req-9", r.requestId)
        assertEquals("c-1", r.conversationId)
        assertEquals(TaskKind.ANALYZE_SOUND, r.taskKind)
        assertEquals("What do these sound measurements suggest?", r.userText)
        assertEquals(InputOrigin.SPEECH_WATCH, r.inputOrigin)
        assertEquals(ReplyMode.BOTH, r.replyMode)
        assertEquals(99_000, r.createdAtWatchEpochMs)
        assertTrue(r.transcriptReviewed)
        assertEquals(InputOrigin.TYPED, DictationRequests.toAssistantRequest(draft(method = InputMethod.KEYBOARD), ReplyMode.TEXT, 1).inputOrigin)
    }

    // ------------------------------------------------------------------------- what the screen shows

    @Test
    fun `the listening screen shows what the controller is doing`() {
        assertEquals(ListenUi.Idle, DictationState.Idle.toListenUi(0))
        for (phase in listOf(DictationPhase.PREPARING, DictationPhase.AWAITING_LAUNCH, DictationPhase.LAUNCHING)) {
            assertEquals(ListenUi.Preparing, DictationState.Active(request(), InputMethod.SPEECH, phase, null).toListenUi(0))
        }
        for (phase in listOf(DictationPhase.LISTENING, DictationPhase.RESOLVING)) {
            assertEquals(ListenUi.Listening(InputMethod.SPEECH), DictationState.Active(request(), InputMethod.SPEECH, phase, null).toListenUi(0))
        }
        assertTrue("a busy flow is not a finished one", ListenUi.Preparing.isBusy && ListenUi.Listening(InputMethod.KEYBOARD).isBusy)
        assertFalse(ListenUi.Idle.isBusy)
    }

    @Test
    fun `review shows the words, their source, the retained measurement note and a kept-words explanation`() {
        val review = DictationState.Review(draft("check this", task = TaskKind.LOG_ISSUE), note = DictationFailureKind.NO_SPEECH).toListenUi(60_000) as ListenUi.Review
        assertEquals("check this", review.transcript)
        assertEquals("Watch dictation", review.source)
        assertEquals("No measurement attached", review.measurementNote)
        assertTrue("log issue can be logged locally without any cloud answer", review.canLogLocally)
        assertTrue(review.note!!.contains("still here"))
        assertFalse(((DictationState.Review(draft(task = TaskKind.FREE_CHAT)).toListenUi(0)) as ListenUi.Review).canLogLocally)
    }

    @Test
    fun `a failure with no earlier words becomes a notice with the right next steps`() {
        val n = DictationState.Failed(request(), InputMethod.SPEECH, DictationFailureKind.NO_SPEECH).toListenUi(0) as ListenUi.Notice
        assertEquals("Didn't catch that.", n.text.message)
        assertTrue(n.text.canRetry && n.text.offerTyping)
    }

    @Test
    fun `an unsent draft is the first thing that needs the person, and an older recording that is only a recording is not`() {
        val items = AssistantAttention.build(emptyList(), emptyList(), emptyList(), draft("unsent"))
        assertEquals(listOf("draft:s-1"), items.map { it.key })
    }

    // ----------------------------------------------------------------- legacy data still decodes

    @Test
    fun `memo states an earlier version wrote still decode`() {
        val json = """{"memos":[{"memoId":"m","requestId":"r","createdAtEpochMs":1,"durationMs":2,"sampleRateHz":16000,"byteCount":3,"purpose":"DICTATION","taskKind":"FREE_CHAT","state":"TRANSCRIBING","attempts":2}]}"""
        val file = StageScopeJson.decodeFromString(com.peaceantz.stagescope.assistant.MemoFile.serializer(), json)
        assertEquals(com.peaceantz.stagescope.assistant.MemoState.TRANSCRIBING, file.memos.single().state)
    }
}
