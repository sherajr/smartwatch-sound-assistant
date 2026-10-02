package com.peaceantz.stagescope.assistant.speech

import android.app.Activity
import android.speech.RecognizerIntent
import com.peaceantz.stagescope.assistant.AssistantRepository
import com.peaceantz.stagescope.assistant.FakePhoneLink
import com.peaceantz.stagescope.assistant.OutboxState
import com.peaceantz.stagescope.assistant.measure.MeasurementSnapshotBuilder
import com.peaceantz.stagescope.assistant.measure.TestMeasurements
import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.AudioLeaseKind
import com.peaceantz.stagescope.audio.AudioMode
import com.peaceantz.stagescope.audio.FakeMeasurementControl
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.ReplyMode
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.MeasurementContext
import com.peaceantz.stagescope.shared.measurement.RunState
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.shared.protocol.AssistantRequest
import com.peaceantz.stagescope.shared.protocol.VoiceOffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The dictation session as a state machine, driven through fake launch/result boundaries and fake audio/phone links. These prove
 * the rules (one launch, review before send, intact context, lease handling, recovery); they cannot prove that real speech
 * recognition works -- that needs the watch and a person speaking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 10_000_000L
    private var foreground = true
    private var handlerInstalled = true
    private var tokens = 0
    private var speechStops = 0
    private val logs = mutableListOf<String>()
    private val control = FakeMeasurementControl()
    private val audio = AudioCoordinator({ now }, { foreground }, { true }).also { it.attach(control) }
    private val timing = DictationController.Timing(launchWaitMs = 8_000, listeningMaxMs = 180_000, resumeSettleMs = 350)

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    private fun store() = DictationStore(File(dir, "dictation.json"))

    private fun TestScope.controller(store: DictationStore = store()) = DictationController(
        scope = backgroundScope, audio = audio, store = store,
        availability = DictationAvailability { handlerInstalled },
        // Bounded exactly like the real one: wait for the app to be on screen, give up after a few seconds.
        awaitForeground = { var waited = 0; while (!foreground && waited < 5_000) { delay(100); waited += 100 }; foreground },
        stopSpeaking = { speechStops++ }, clock = { now }, log = { logs += it }, timing = timing, newToken = { "token-${++tokens}" },
    )

    private fun snapshot(): MeasurementContext = MeasurementSnapshotBuilder.build(
        TestMeasurements.inputs(analyzer = TestMeasurements.analyzerSample(TestMeasurements.reading()), nowEpoch = 1_800_000_000_000L, runState = RunState.RUNNING),
    )

    private fun request(id: String = "session-1", task: TaskKind = TaskKind.ANALYZE_SOUND, snapshot: MeasurementContext? = snapshot()) = DictationRequest(
        sessionId = id, task = task, origin = SnapshotOrigin.ANALYZER, snapshot = snapshot, conversationId = "conv-1", editsActionId = null,
        performanceId = "perf-7", inputOrigin = InputOrigin.SPEECH_WATCH, startedAtEpochMs = now, measurementWasRunning = true,
    )

    private fun ok(vararg words: String) = RawDictationResult(Activity.RESULT_OK, words.toList(), null)
    private fun typed(text: String) = RawDictationResult(Activity.RESULT_OK, null, text)
    private fun cancelled() = RawDictationResult(Activity.RESULT_CANCELED, null, null)

    private fun TestScope.settle(ms: Long = 1_000) { advanceTimeBy(ms); runCurrent() }

    /** Drives one session to "the system screen is open": begin -> ready -> host claims and launches. */
    private fun TestScope.openScreen(c: DictationController, r: DictationRequest = request(), method: InputMethod = InputMethod.SPEECH, previous: DictationDraft? = null): LaunchTicket {
        assertTrue(c.begin(r, method, previous))
        runCurrent()
        val ticket = c.claimLaunch()
        assertNotNull("a session that is ready can be claimed", ticket)
        c.onLaunched(ticket!!)
        return ticket
    }

    private val DictationController.phase get() = (state.value as? DictationState.Active)?.phase
    private val DictationController.review get() = state.value as? DictationState.Review

    // ------------------------------------------------------------------------------- launching

    @Test
    fun `native dictation opens whenever a handler is installed - there is no offline-recognizer prerequisite anywhere`() = runTest {
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent()
        assertEquals(DictationPhase.AWAITING_LAUNCH, c.phase)
        val ticket = c.claimLaunch()!!
        assertEquals(InputMethod.SPEECH, ticket.method)
        // The only availability question the controller can ask is "is there a screen to open?"; it has no way to ask about offline recognition.
        assertTrue(DictationAvailability::class.java.methods.all { it.name == "isAvailable" })
    }

    @Test
    fun `one request opens exactly one screen - a second claim, a second begin and a recreated host all do nothing`() = runTest {
        val c = controller()
        assertTrue(c.begin(request()))
        assertFalse("rapid second tap while preparing", c.begin(request("session-2")))
        assertFalse("and a third", c.begin(request("session-3")))
        runCurrent()
        assertEquals("only one lease", 1, control.count("pause:ASSISTANT_LISTENING"))

        val first = c.claimLaunch()
        assertNotNull(first)
        assertNull("a host that was recreated and collects the same state again cannot claim it twice", c.claimLaunch())
        c.onLaunched(first!!)
        assertNull("and not while the screen is open either", c.claimLaunch())
        assertFalse("nor can a new session start on top", c.begin(request("session-4")))
        assertEquals(DictationPhase.LISTENING, c.phase)
    }

    @Test
    fun `no handler installed explains itself, never pauses measurement, and can be retried when one appears`() = runTest {
        handlerInstalled = false
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent()
        val failed = c.state.value as DictationState.Failed
        assertEquals(DictationFailureKind.NO_HANDLER, failed.kind)
        assertTrue("measurement was never touched", control.events.isEmpty())
        assertEquals(AudioMode.IDLE, audio.mode.value)

        handlerInstalled = true
        assertTrue(c.retry())
        runCurrent()
        assertEquals(DictationPhase.AWAITING_LAUNCH, c.phase)
    }

    @Test
    fun `a launch the system refuses ends the session, gives the microphone straight back and can be retried`() = runTest {
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent()
        val ticket = c.claimLaunch()!!
        c.onLaunchFailed(ticket, DictationFailureKind.LAUNCH_FAILED)
        settle()

        assertEquals(DictationFailureKind.LAUNCH_FAILED, (c.state.value as DictationState.Failed).kind)
        assertEquals("the screen never ran, so measurement is simply resumed", 1, control.count("resume"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
        assertNull("no open-screen record is left behind", store().inFlight)
        assertTrue(c.retry())
    }

    @Test
    fun `a host that claims the launch from inside the state publication - as the real one does - keeps its listening bound`() = runTest {
        // Found on the real watch: the host collects on the main dispatcher and claims + launches INSIDE the publication of
        // AWAITING_LAUNCH. A launch-timeout armed after that publication overwrote the listening watchdog and failed a session whose
        // screen was open, eight seconds in. An Unconfined collector reproduces that re-entrancy.
        val c = controller()
        var launches = 0
        backgroundScope.launch(Dispatchers.Unconfined) {
            c.state.map { (it as? DictationState.Active)?.phase }.distinctUntilChanged().collect { phase ->
                if (phase == DictationPhase.AWAITING_LAUNCH) c.claimLaunch()?.let { launches++; c.onLaunched(it) }
            }
        }
        assertTrue(c.begin(request()))
        runCurrent()
        assertEquals(1, launches)
        assertEquals(DictationPhase.LISTENING, c.phase)

        settle(timing.launchWaitMs + 5_000)
        assertEquals("well past the launch bound, an open screen has not been timed out", DictationPhase.LISTENING, c.phase)
        assertEquals(0, control.count("resume"))
        assertTrue("it is still paused for the recognizer", control.paused)

        c.onResult(ok("said it after more than eight seconds"))
        settle()
        assertEquals("said it after more than eight seconds", c.review!!.draft.text)
    }

    @Test
    fun `a screen claimed from inside the publication is still abandoned after the listening bound, not left open forever`() = runTest {
        val c = controller()
        backgroundScope.launch(Dispatchers.Unconfined) {
            c.state.map { (it as? DictationState.Active)?.phase }.distinctUntilChanged().collect { phase ->
                if (phase == DictationPhase.AWAITING_LAUNCH) c.claimLaunch()?.let { c.onLaunched(it) }
            }
        }
        c.begin(request())
        runCurrent()
        settle(timing.listeningMaxMs - 1_000)
        assertEquals(DictationPhase.LISTENING, c.phase)
        settle(2_000)
        assertEquals("the listening watchdog was not lost", DictationFailureKind.ABANDONED, (c.state.value as DictationState.Failed).kind)
        assertEquals("measurement was ended, not reopened under a screen that may still be listening", 0, control.count("resume"))
        assertEquals(1, control.count("end"))
    }

    @Test
    fun `the launch timeout never fires on a session that has already been claimed`() = runTest {
        val c = controller()
        c.begin(request())
        runCurrent()
        val ticket = c.claimLaunch()!!
        c.onLaunched(ticket)
        settle(timing.launchWaitMs + 1_000)
        assertEquals(DictationPhase.LISTENING, c.phase)
        assertFalse(logs.any { it.contains("not claimed in time") })
    }

    @Test
    fun `a ready launch the host never claims is given up on, not left to open a microphone later`() = runTest {
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent()
        assertEquals(DictationPhase.AWAITING_LAUNCH, c.phase)
        settle(timing.launchWaitMs + 1_000)

        assertEquals(DictationFailureKind.LAUNCH_FAILED, (c.state.value as DictationState.Failed).kind)
        assertNull("so it can never be claimed late", c.claimLaunch())
        assertEquals(1, control.count("resume"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
    }

    @Test
    fun `the phone speaking blocks dictation, because the watch would hear it`() = runTest {
        val lease = audio.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent()
        assertEquals(DictationFailureKind.PHONE_SPEAKING, (c.state.value as DictationState.Failed).kind)
        assertFalse("no microphone lease was taken", audio.isHeld(AudioLeaseKind.LISTENING))
        audio.release(lease)
    }

    @Test
    fun `the watch's own speech is stopped before the screen opens`() = runTest {
        val c = controller()
        c.begin(request())
        runCurrent()
        assertEquals(1, speechStops)
        assertEquals("and that happened before the screen was offered to the host", DictationPhase.AWAITING_LAUNCH, c.phase)
    }

    // ------------------------------------------------------------------- result -> review -> send

    @Test
    fun `a result leads to review only - nothing is sent and no audio leaves the watch until Send`() = runTest {
        val link = FakePhoneLink()
        val repo = AssistantRepository(File(dir, "assistant").apply { mkdirs() }, link, "0.3.0", 3, clock = { now })
        val c = controller()
        openScreen(c)
        c.onResult(ok("What do these sound measurements suggest?"))
        settle()

        val review = c.review!!
        assertEquals("What do these sound measurements suggest?", review.draft.text)
        assertEquals("Watch dictation", review.draft.source)
        assertTrue("nothing at all went to the phone", link.sent.isEmpty())
        assertTrue(repo.outbox.value.entries.isEmpty())
    }

    @Test
    fun `Send uses the same pre-speech snapshot, task, conversation and request identity`() = runTest {
        val link = FakePhoneLink()
        val repo = AssistantRepository(File(dir, "assistant").apply { mkdirs() }, link, "0.3.0", 3, clock = { now })
        val c = controller()
        val asked = request(id = "session-42")
        openScreen(c, asked)
        now += 90_000 // the person takes a while to speak and read
        c.onResult(ok("What do these sound measurements suggest?"))
        settle()

        val sent = c.complete { d -> repo.submit(DictationRequests.toAssistantRequest(d, ReplyMode.TEXT, now)) }
        assertEquals("session-42", sent)

        val req = link.sentOf<AssistantRequest>().single()
        assertEquals("session-42", req.requestId)
        assertEquals(asked.conversationId, req.conversationId)
        assertEquals(TaskKind.ANALYZE_SOUND, req.taskKind)
        assertEquals("perf-7", req.performanceId)
        assertEquals("What do these sound measurements suggest?", req.userText)
        assertEquals(InputOrigin.SPEECH_WATCH, req.inputOrigin)
        assertTrue(req.transcriptReviewed)
        assertEquals("the snapshot is the one taken before speaking, not a new one", asked.snapshot, req.measurement)
        assertEquals("with its original capture time", 1_800_000_000_000L, req.measurement!!.capturedAtEpochMs)
        assertTrue("and no audio, offer or channel anywhere in the flow", link.sent.none { it is VoiceOffer })
        assertEquals(OutboxState.SENDING, repo.outbox.value.entries.single().state)
        assertEquals(DictationState.Idle, c.state.value)
        assertNull("the draft is gone once it has been sent", store().currentDraft)
    }

    @Test
    fun `Send twice, or Send then Log, does the action once`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("log an issue headset six is crackling"))
        settle()

        var runs = 0
        val first = launch { c.complete { delay(50); runs++ } }
        runCurrent()
        assertNull("a second tap while the first is running does nothing", c.complete { runs++ })
        first.join()
        assertEquals(1, runs)
        assertNull("and neither does one afterwards", c.complete { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun `if Send fails the words are still there to try again`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("words that must not be lost"))
        settle()

        var failed = false
        try { c.complete<Unit> { error("boom") } } catch (e: IllegalStateException) { failed = true }
        assertTrue(failed)
        val review = c.review!!
        assertFalse(review.sending)
        assertEquals("words that must not be lost", review.draft.text)
        assertEquals("words that must not be lost", store().currentDraft!!.text)
        assertEquals("and a retry works", "ok", c.complete { "ok" })
    }

    @Test
    fun `typed text goes through the same review and is labelled as typed`() = runTest {
        val c = controller()
        openScreen(c, method = InputMethod.KEYBOARD)
        c.onResult(typed("Is the ring at 2k from mic 3?"))
        settle()
        val d = c.review!!.draft
        assertEquals("Is the ring at 2k from mic 3?", d.text)
        assertEquals("Typed on this watch", d.source)
        assertEquals(InputOrigin.TYPED, d.inputOrigin)
    }

    // --------------------------------------------------------------------- cancel and failures

    @Test
    fun `backing out of the system screen discards the utterance without creating a question or a draft`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(cancelled())
        settle()
        assertEquals("cancellation is not 'no speech'", DictationState.Idle, c.state.value)
        assertNull(store().currentDraft)
        assertNull(store().inFlight)
        assertEquals(1, control.count("resume"))
    }

    @Test
    fun `no speech, an empty result and the recognizer's error codes each end in an honest failure`() = runTest {
        val cases = listOf(
            RawDictationResult(Activity.RESULT_OK, emptyList(), null) to DictationFailureKind.NO_SPEECH,
            RawDictationResult(Activity.RESULT_OK, listOf("  "), null) to DictationFailureKind.NO_SPEECH,
            RawDictationResult(RecognizerIntent.RESULT_NO_MATCH, null, null) to DictationFailureKind.NO_SPEECH,
            RawDictationResult(RecognizerIntent.RESULT_NETWORK_ERROR, listOf("partial"), null) to DictationFailureKind.NETWORK,
            RawDictationResult(RecognizerIntent.RESULT_SERVER_ERROR, null, null) to DictationFailureKind.SERVER,
            RawDictationResult(RecognizerIntent.RESULT_AUDIO_ERROR, null, null) to DictationFailureKind.AUDIO,
            RawDictationResult(RecognizerIntent.RESULT_CLIENT_ERROR, null, null) to DictationFailureKind.CLIENT,
            RawDictationResult(99, listOf("text"), null) to DictationFailureKind.UNKNOWN_RESULT,
        )
        for ((raw, kind) in cases) {
            val c = controller(DictationStore(File(tmp.newFolder(), "d.json")))
            openScreen(c)
            c.onResult(raw)
            settle()
            assertEquals(kind, (c.state.value as DictationState.Failed).kind)
            assertNull("no words are invented after a failure", (c.state.value as? DictationState.Review))
        }
    }

    @Test
    fun `an explicit Cancel from the app's own screen ends the session and a late result changes nothing`() = runTest {
        val c = controller()
        openScreen(c)
        c.cancel()
        settle()
        assertEquals(DictationState.Idle, c.state.value)
        assertNull("the open-screen record is cleared", store().inFlight)

        c.onResult(ok("a result that arrives after the person cancelled"))
        settle()
        assertEquals("a canceled task is never reopened", DictationState.Idle, c.state.value)
        assertNull(store().currentDraft)
    }

    @Test
    fun `a duplicate result cannot replace the words or release the microphone twice`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("first"))
        c.onResult(ok("second, delivered twice"))
        settle()
        assertEquals("first", c.review!!.draft.text)
        assertEquals("the microphone went back exactly once", 1, control.count("resume"))
        assertTrue(logs.any { it.contains("result ignored") })
    }

    @Test
    fun `a result that arrives while newer words are under review is ignored and never overwrites them`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("newer words"))
        settle()
        c.onResult(ok("an old straggler"))
        settle()
        assertEquals("newer words", c.review!!.draft.text)
        assertEquals("newer words", store().currentDraft!!.text)
    }

    @Test
    fun `a result delivered while the app is still preparing the next launch is ignored`() = runTest {
        val c = controller()
        c.begin(request())
        runCurrent()
        c.onResult(ok("a stale result from an earlier screen"))
        settle()
        assertEquals(DictationPhase.AWAITING_LAUNCH, c.phase)
    }

    @Test
    fun `a result in the instant between the claim and the launch report is still accepted`() = runTest {
        val c = controller()
        c.begin(request())
        runCurrent()
        c.claimLaunch()!!
        c.onResult(ok("fast"))
        settle()
        assertEquals("fast", c.review!!.draft.text)
    }

    // -------------------------------------------------------------- dictate again keeps the words

    @Test
    fun `dictating again keeps the words until a usable replacement arrives`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("original words"))
        settle()
        val original = c.review!!.draft

        // 1. backing out of the replacement returns to the original, which was never removed.
        assertTrue(c.redo())
        runCurrent()
        assertEquals("the original is still stored while the replacement is being dictated", "original words", store().currentDraft!!.text)
        c.claimLaunch()?.let(c::onLaunched)
        c.onResult(cancelled())
        settle()
        assertEquals(original, c.review!!.draft)

        // 2. a replacement that yields nothing returns to the original with an explanation.
        assertTrue(c.redo())
        runCurrent()
        c.claimLaunch()?.let(c::onLaunched)
        c.onResult(ok())
        settle()
        assertEquals(original, c.review!!.draft)
        assertEquals(DictationFailureKind.NO_SPEECH, c.review!!.note)
        assertEquals("original words", store().currentDraft!!.text)

        // 3. a usable replacement replaces it, with the same pre-speech context.
        assertTrue(c.redo())
        runCurrent()
        c.claimLaunch()?.let(c::onLaunched)
        c.onResult(ok("replacement words"))
        settle()
        assertEquals("replacement words", c.review!!.draft.text)
        assertEquals("replacement words", store().currentDraft!!.text)
        assertEquals(original.request, c.review!!.draft.request)
    }

    @Test
    fun `typing instead replaces dictated words and is labelled typed`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("dictated"))
        settle()
        assertTrue(c.redo(InputMethod.KEYBOARD))
        runCurrent()
        c.claimLaunch()!!.also { assertEquals(InputMethod.KEYBOARD, it.method); c.onLaunched(it) }
        c.onResult(typed("typed instead"))
        settle()
        assertEquals(InputOrigin.TYPED, c.review!!.draft.inputOrigin)
    }

    @Test
    fun `Cancel during a replacement keeps the original draft`() = runTest {
        val c = controller()
        openScreen(c)
        c.onResult(ok("keep me"))
        settle()
        c.redo()
        runCurrent()
        c.cancel()
        settle()
        assertEquals("keep me", c.review!!.draft.text)
        assertEquals("keep me", store().currentDraft!!.text)
    }

    @Test
    fun `dictating again over words from an older recording is watch dictation, not a recording`() = runTest {
        val c = controller()
        val old = request(id = "memo-req").copy(inputOrigin = InputOrigin.VOICE_MEMO_TRANSCRIBED, legacyMemoId = "memo-1")
        assertTrue(c.openForReview(DictationDraft(old, "older words", InputMethod.SPEECH, "Older recording", now)))
        assertTrue(c.redo())
        runCurrent()
        c.claimLaunch()?.let(c::onLaunched)
        c.onResult(ok("fresh dictation"))
        settle()
        assertEquals(InputOrigin.SPEECH_WATCH, c.review!!.draft.inputOrigin)
        assertEquals("the older memo still goes with it", "memo-1", c.review!!.draft.request.legacyMemoId)
    }

    // ------------------------------------------------------------------------- the microphone

    @Test
    fun `the lease is taken before the screen opens, measurement stays intact, and it resumes once afterwards`() = runTest {
        val c = controller()
        c.begin(request())
        runCurrent()
        assertEquals(AudioMode.LISTENING, audio.mode.value)
        assertTrue(control.paused)
        assertEquals(listOf("pause:ASSISTANT_LISTENING", "awaitReleased"), control.events)

        c.claimLaunch()!!.let(c::onLaunched)
        assertTrue("still paused the whole time the screen is open", control.paused)
        settle(60_000)
        assertTrue("a minute later, still paused: no short timer resumes it under the recognizer", control.paused)
        assertEquals(0, control.count("resume"))

        c.onResult(ok("hello"))
        assertTrue("not yet - it waits for the recognizer to let go", control.paused)
        settle()
        assertFalse(control.paused)
        assertEquals("resumed exactly once", 1, control.count("resume"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
        assertEquals("the coordinator only ever pauses and resumes: nothing here restarts the session", 0, control.count("end"))
    }

    @Test
    fun `a result that arrives before the app is foreground again resumes only once it is`() = runTest {
        foreground = false // the dictation screen still covers StageScope when the result is handed over
        val c = controller()
        openScreen(c)
        c.onResult(ok("hello"))
        runCurrent()
        assertTrue("not resumed while in the background", control.paused)
        advanceTimeBy(800)
        foreground = true // the app comes back within the bound
        settle()
        assertEquals(1, control.count("resume"))
        assertEquals(0, control.count("end"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
    }

    @Test
    fun `if the app never comes back, the paused session is ended rather than reopening the microphone`() = runTest {
        foreground = false
        val c = controller()
        openScreen(c)
        c.onResult(ok("hello"))
        settle(10_000)
        assertEquals(0, control.count("resume"))
        assertEquals(1, control.count("end"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
        assertEquals("the words are kept regardless", "hello", c.review!!.draft.text)
    }

    @Test
    fun `pressing Stop while the screen is open cancels the resume`() = runTest {
        val c = controller()
        openScreen(c)
        control.sessionEndedByUser() // Stop, or the keep-awake countdown ending
        c.onResult(ok("hello"))
        settle()
        assertEquals(0, control.count("resume"))
        assertFalse(control.running)
        assertEquals(AudioMode.IDLE, audio.mode.value)
    }

    @Test
    fun `an abandoned screen is cleaned up by ending the paused session - the recognizer may still own the microphone`() = runTest {
        val c = controller()
        openScreen(c)
        settle(timing.listeningMaxMs - 1_000)
        assertEquals("ordinary dictation is never interrupted", DictationPhase.LISTENING, c.phase)
        settle(2_000)
        assertEquals(DictationFailureKind.ABANDONED, (c.state.value as DictationState.Failed).kind)
        assertEquals("never reopened", 0, control.count("resume"))
        assertEquals(1, control.count("end"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
        assertNull(store().inFlight)

        c.onResult(ok("far too late"))
        settle()
        assertTrue("a result after abandonment is ignored", c.state.value is DictationState.Failed)
    }

    @Test
    fun `an Activity that is finishing for good ends the session the same safe way`() = runTest {
        val c = controller()
        openScreen(c)
        c.onHostFinished()
        settle()
        assertEquals(DictationFailureKind.ABANDONED, (c.state.value as DictationState.Failed).kind)
        assertEquals(0, control.count("resume"))
        assertEquals(AudioMode.IDLE, audio.mode.value)
    }

    @Test
    fun `cancelling while the microphone is still being freed hands the lease straight back`() = runTest {
        control.micGate = CompletableDeferred()
        val c = controller()
        assertTrue(c.begin(request()))
        runCurrent() // paused, waiting for the microphone to be released
        assertEquals(AudioMode.LISTENING, audio.mode.value)

        c.cancel()
        control.micGate!!.complete(Unit)
        settle()
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("the lease it was about to hold was given back", AudioMode.IDLE, audio.mode.value)
        assertEquals(1, control.count("resume"))
        assertNull("and nothing was left recorded as open", store().inFlight)
        assertNull("no screen can be claimed any more", c.claimLaunch())
    }

    @Test
    fun `a lease is released exactly once on every way out`() = runTest {
        for (way in listOf("result", "cancelled", "failure", "cancel", "launch failed")) {
            control.events.clear(); control.paused = false; control.running = true
            val c = controller(DictationStore(File(tmp.newFolder(), "d.json")))
            c.begin(request())
            runCurrent()
            val ticket = c.claimLaunch()!!
            when (way) {
                "result" -> { c.onLaunched(ticket); c.onResult(ok("x")) }
                "cancelled" -> { c.onLaunched(ticket); c.onResult(cancelled()) }
                "failure" -> { c.onLaunched(ticket); c.onResult(RawDictationResult(RecognizerIntent.RESULT_NETWORK_ERROR, null, null)) }
                "cancel" -> { c.onLaunched(ticket); c.cancel() }
                "launch failed" -> c.onLaunchFailed(ticket, DictationFailureKind.LAUNCH_FAILED)
            }
            settle()
            assertEquals("$way: resumed once", 1, control.count("resume"))
            assertEquals("$way: nothing left held", AudioMode.IDLE, audio.mode.value)
            assertFalse("$way: not stranded paused", control.paused)
        }
    }

    // ------------------------------------------------------------------------- recreation, restoration

    @Test
    fun `the host being recreated mid-dictation neither relaunches nor loses the result`() = runTest {
        val c = controller()
        val asked = request()
        openScreen(c, asked)
        // Activity recreated: a fresh binding collects the same state and tries to claim.
        assertNull(c.claimLaunch())
        assertEquals(DictationPhase.LISTENING, c.phase)
        // The recreated Activity's registered callback receives the result.
        c.onResult(ok("after recreation"))
        settle()
        assertEquals(asked, c.review!!.draft.request)
    }

    @Test
    fun `after the process dies mid-dictation the result still lands in its own saved context`() = runTest {
        val asked = request(id = "survivor")
        val first = controller()
        openScreen(first, asked)
        assertNotNull("the open screen was recorded before it was launched", store().inFlight)

        now += 20_000
        val second = controller() // a new process: same files, no memory
        assertEquals("it is waiting for that screen, not starting a new one", DictationPhase.LISTENING, second.phase)
        assertNull("it will not open another screen", second.claimLaunch())
        assertEquals(0, control.count("end"))
        second.onResult(ok("typed in the new process"))
        settle()
        assertEquals("typed in the new process", second.review!!.draft.text)
        assertEquals("the snapshot and ids came from the saved record, not from a guess", asked, second.review!!.draft.request)
        assertNull(store().inFlight)
    }

    @Test
    fun `an open-screen record from long ago is not resurrected - a restart makes no session and no microphone`() = runTest {
        val first = controller()
        openScreen(first)
        now += timing.listeningMaxMs + 1
        val second = controller()
        runCurrent()
        assertEquals(DictationState.Idle, second.state.value)
        assertNull(second.claimLaunch())
        assertNull(store().inFlight)
        second.onResult(ok("nobody was waiting"))
        assertEquals(DictationState.Idle, second.state.value)
    }

    @Test
    fun `restoring the app creates no request and no draft by itself`() = runTest {
        val link = FakePhoneLink()
        val c = controller()
        assertEquals(DictationState.Idle, c.state.value)
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertEquals(0, control.events.size)
        assertEquals(0, speechStops)
    }

    @Test
    fun `an unsent draft survives closing the app and is offered back for review`() = runTest {
        val first = controller()
        openScreen(first, request(id = "kept"))
        first.onResult(ok("Log an issue: headset six is crackling during scene two"))
        settle()

        val reopened = controller()
        assertEquals(DictationState.Idle, reopened.state.value)
        assertEquals("Log an issue: headset six is crackling during scene two", reopened.stored.value.draft!!.text)
        assertTrue(reopened.resumeDraft())
        assertEquals("kept", reopened.review!!.draft.request.sessionId)
        assertNotNull("with its pre-speech snapshot", reopened.review!!.draft.request.snapshot)

        assertNotNull(reopened.discard())
        assertNull("discarding removes it from disk too", store().currentDraft)
        assertEquals(DictationState.Idle, reopened.state.value)
    }

    @Test
    fun `an older draft's completion never removes a newer draft`() = runTest {
        val c = controller()
        openScreen(c, request(id = "old"))
        c.onResult(ok("old words"))
        settle()
        c.dismiss() // leaves the old words stored, with no session
        openScreen(c, request(id = "new"))
        c.onResult(ok("new words"))
        settle()
        store().clearDraft("old")
        assertEquals("new words", store().currentDraft!!.text)
    }

    // ------------------------------------------------------------------------------ diagnostics

    @Test
    fun `diagnostics describe states and categories but never words, measurements or ids`() = runTest {
        val c = controller()
        openScreen(c, request(id = "SECRET-SESSION-ID"))
        c.onResult(ok("SECRET WORDS: the lead's wireless pack is ch 14"))
        settle()
        c.complete { }
        settle()
        assertTrue("it logged something", logs.isNotEmpty())
        assertTrue(logs.any { it.startsWith("begin") } && logs.any { it.startsWith("lease") } && logs.any { it.startsWith("result") })
        assertTrue(logs.none { it.contains("SECRET") || it.contains("wireless") || it.contains("ch 14") || it.contains("perf-7") || it.contains("conv-1") })
    }

    @Test
    fun `no state-changing call ever starts the AI - the controller has no way to reach the phone at all`() = runTest {
        // The controller's constructor takes no PhoneLink and no repository: only an explicit complete { } runs caller-supplied work.
        val params = DictationController::class.java.constructors.flatMap { it.parameterTypes.toList() }.map { it.simpleName }
        assertTrue(params.none { it.contains("Phone") || it.contains("Repository") || it.contains("Link") })
    }
}
