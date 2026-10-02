package com.peaceantz.stagescope.assistant.speech

import android.app.Activity
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.MeasurementControl
import com.peaceantz.stagescope.audio.PauseReason
import com.peaceantz.stagescope.shared.assistant.InputOrigin
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real Activity Result plumbing, on a real device: launch, result, the host being recreated while the screen is open, and a
 * handler that doesn't exist. The recognition screen is a fake, so this proves the lifecycle behaviour -- not that speech
 * recognition works (that needs a person speaking to the watch).
 */
class DictationLauncherBindingTest {
    private class FakeMeasurement : MeasurementControl {
        @Volatile var paused = false
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun pauseForAssistant(reason: PauseReason): Boolean { paused = true; events += "pause"; return true }
        override val isPausedForAssistant get() = paused
        override suspend fun awaitMicReleased() = Unit
        override fun resumeAfterAssistant() { paused = false; events += "resume" }
        override fun endPausedSession() { paused = false; events += "end" }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var measurement: FakeMeasurement
    private lateinit var controller: DictationController

    @Before
    fun setUp() {
        FakeRecognizerActivity.created.set(0)
        FakeRecognizerActivity.autoResult = null
        FakeRecognizerActivity.current = null
        DictationHostActivity.creations.set(0)
        DictationHostActivity.intentOverride = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        measurement = FakeMeasurement()
        val audio = AudioCoordinator({ System.nanoTime() / 1_000_000 }, { true }, { true }).also { it.attach(measurement) }
        val store = DictationStore(File(context.cacheDir, "dictation-test-${System.nanoTime()}.json"))
        controller = DictationController(
            scope = scope, audio = audio, store = store, availability = DictationAvailability { true },
            awaitForeground = { true }, stopSpeaking = {}, timing = DictationController.Timing(launchWaitMs = 1_500, listeningMaxMs = 60_000, resumeSettleMs = 50),
        )
        DictationHostActivity.controller = controller
    }

    @After
    fun tearDown() {
        FakeRecognizerActivity.current?.runOnUiThread { FakeRecognizerActivity.current?.finish() }
        scope.cancel()
        DictationHostActivity.controller = null
    }

    private fun request(id: String = "inst-1") = DictationRequest(
        sessionId = id, task = TaskKind.ANALYZE_SOUND, origin = SnapshotOrigin.ANALYZER, conversationId = "c-1", inputOrigin = InputOrigin.SPEECH_WATCH,
        startedAtEpochMs = System.currentTimeMillis(),
    )

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(40)
        }
        throw AssertionError("Timed out waiting for: $what (state=${controller.state.value})")
    }

    private fun review() = controller.state.value as? DictationState.Review

    @Test
    fun a_result_travels_through_the_real_Activity_Result_API_into_review() {
        FakeRecognizerActivity.autoResult = Activity.RESULT_OK to listOf("What do these sound measurements suggest?")
        ActivityScenario.launch(DictationHostActivity::class.java).use {
            onMain { controller.begin(request()) }
            waitFor("review") { review() != null }
            assertEquals("What do these sound measurements suggest?", review()!!.draft.text)
            assertEquals("exactly one dictation screen was opened", 1, FakeRecognizerActivity.created.get())
            waitFor("measurement resumed once") { measurement.events.count { it == "resume" } == 1 }
            assertEquals(listOf("pause", "resume"), measurement.events.toList())
        }
    }

    @Test
    fun backing_out_of_the_screen_is_a_cancellation_not_an_error() {
        FakeRecognizerActivity.autoResult = Activity.RESULT_CANCELED to null
        ActivityScenario.launch(DictationHostActivity::class.java).use {
            onMain { controller.begin(request()) }
            waitFor("back to idle") { controller.state.value == DictationState.Idle }
            assertEquals(1, FakeRecognizerActivity.created.get())
        }
    }

    @Test
    fun the_host_being_recreated_while_the_screen_is_open_neither_relaunches_it_nor_loses_its_result() {
        ActivityScenario.launch(DictationHostActivity::class.java).use { scenario ->
            onMain { controller.begin(request("survives-recreation")) }
            waitFor("the screen to be open") { FakeRecognizerActivity.current != null && (controller.state.value as? DictationState.Active)?.phase == DictationPhase.LISTENING }
            assertEquals(1, DictationHostActivity.creations.get())

            // The host is recreated underneath the open screen (a configuration change, or the system reclaiming it).
            scenario.onActivity { it.recreate() }
            waitFor("the host to be created again") { DictationHostActivity.creations.get() == 2 }
            Thread.sleep(2_500) // longer than the launch bound (1.5 s here): a launch timeout wrongly armed over the open screen would show itself
            assertEquals("no second dictation screen", 1, FakeRecognizerActivity.created.get())
            assertEquals(DictationPhase.LISTENING, (controller.state.value as DictationState.Active).phase)

            // The screen finishes while the host is still behind it: the result is held and handed over when the host is back.
            FakeRecognizerActivity.current!!.let { r -> r.runOnUiThread { r.finishWith(Activity.RESULT_OK, listOf("after recreation")) } }
            waitFor("review") { review() != null }
            assertEquals("after recreation", review()!!.draft.text)
            assertEquals("survives-recreation", review()!!.draft.request.sessionId)
            assertEquals("still only one screen was ever opened", 1, FakeRecognizerActivity.created.get())
        }
    }

    @Test
    fun a_handler_that_does_not_exist_ends_the_session_cleanly_and_gives_the_microphone_back() {
        DictationHostActivity.intentOverride = { Intent().setComponent(ComponentName("com.example.nobody.handles.this", "com.example.nobody.Nothing")) }
        ActivityScenario.launch(DictationHostActivity::class.java).use {
            onMain { controller.begin(request()) }
            waitFor("failure") { controller.state.value is DictationState.Failed }
            assertEquals(DictationFailureKind.NO_HANDLER, (controller.state.value as DictationState.Failed).kind)
            waitFor("measurement resumed") { measurement.events.contains("resume") }
            assertFalse(measurement.paused)
            assertEquals(0, FakeRecognizerActivity.created.get())
        }
    }

    @Test
    fun a_typed_result_is_read_from_the_text_input_screens_RemoteInput_shape() {
        val intent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(DictationIntents.TEXT_KEY).build()), intent,
            Bundle().apply { putCharSequence(DictationIntents.TEXT_KEY, "typed words") },
        )
        val raw = DictationIntents.toRaw(Activity.RESULT_OK, intent)
        assertEquals("typed words", raw.typed)
        assertNull(DictationIntents.toRaw(Activity.RESULT_CANCELED, null).typed)
    }

    @Test
    fun the_dictation_intent_is_the_plain_documented_one() {
        val i = DictationIntents.speech("Ask StageScope")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, i.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertFalse("offline is never forced", i.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertFalse("the watch's own language is used", i.hasExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertNull("no component is named", i.component)
        assertNull("no package is named", i.`package`)
    }

    @Test
    fun on_a_real_watch_both_system_screens_resolve_with_the_manifests_package_visibility_entries() {
        assumeTrue("only meaningful on a watch", context.packageManager.hasSystemFeature("android.hardware.type.watch"))
        assumeTrue("an emulator image may have no dictation screen", !Build.FINGERPRINT.contains("generic"))
        val availability = DictationIntents.Availability(context)
        assertTrue("watch dictation resolves from this app's own context", availability.isAvailable(InputMethod.SPEECH))
        assertTrue("the text-input screen resolves too", availability.isAvailable(InputMethod.KEYBOARD))
        val resolved = DictationIntents.speech(null).resolveActivity(context.packageManager)
        assertNotNull(resolved)
    }
}
