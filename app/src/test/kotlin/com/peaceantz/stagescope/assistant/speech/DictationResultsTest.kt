package com.peaceantz.stagescope.assistant.speech

import android.app.Activity
import android.speech.RecognizerIntent
import com.peaceantz.stagescope.shared.assistant.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationResultsTest {
    private fun speech(code: Int, vararg candidates: String?) = DictationResults.interpret(InputMethod.SPEECH, code, candidates.toList())

    @Test
    fun `a successful result gives the first non-blank hypothesis, trimmed and otherwise untouched`() {
        assertEquals(DictationOutcome.Text("What do these sound measurements suggest?"), speech(Activity.RESULT_OK, "  What do these sound measurements suggest?  ", "something else"))
        assertEquals("blank hypotheses are skipped, not turned into text", DictationOutcome.Text("log an issue"), speech(Activity.RESULT_OK, "", "   ", null, "log an issue"))
    }

    @Test
    fun `dictated content is preserved exactly - capitalisation, punctuation, numbers and inner spacing`() {
        val said = "Headset 6 is crackling,  scene 2 — channel 14!"
        assertEquals(DictationOutcome.Text(said), speech(Activity.RESULT_OK, said))
    }

    @Test
    fun `OK with nothing usable is no speech, not a made-up transcript`() {
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NO_SPEECH), speech(Activity.RESULT_OK))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NO_SPEECH), speech(Activity.RESULT_OK, "", "  "))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NO_SPEECH), DictationResults.interpret(InputMethod.SPEECH, Activity.RESULT_OK, null))
    }

    @Test
    fun `backing out is a cancellation, never no speech and never a network error`() {
        assertEquals(DictationOutcome.Cancelled, speech(Activity.RESULT_CANCELED))
        assertEquals("even if the screen left words behind, a cancelled screen gives none", DictationOutcome.Cancelled, speech(Activity.RESULT_CANCELED, "half a sentence"))
    }

    @Test
    fun `the recognizer's own error codes map honestly, and words are never taken after an error`() {
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NO_SPEECH), speech(RecognizerIntent.RESULT_NO_MATCH))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NETWORK), speech(RecognizerIntent.RESULT_NETWORK_ERROR, "a partial guess"))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.SERVER), speech(RecognizerIntent.RESULT_SERVER_ERROR, "a partial guess"))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.AUDIO), speech(RecognizerIntent.RESULT_AUDIO_ERROR))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.CLIENT), speech(RecognizerIntent.RESULT_CLIENT_ERROR))
    }

    @Test
    fun `a result code nobody knows is reported as unknown, not guessed at`() {
        assertEquals(DictationOutcome.Failed(DictationFailureKind.UNKNOWN_RESULT), speech(42, "text"))
    }

    @Test
    fun `the text screen has only OK and cancelled - recognizer codes mean nothing there`() {
        assertEquals(DictationOutcome.Text("typed"), DictationResults.interpret(InputMethod.KEYBOARD, Activity.RESULT_OK, listOf("typed")))
        assertEquals(DictationOutcome.Cancelled, DictationResults.interpret(InputMethod.KEYBOARD, Activity.RESULT_CANCELED, null))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.UNKNOWN_RESULT), DictationResults.interpret(InputMethod.KEYBOARD, RecognizerIntent.RESULT_NETWORK_ERROR, listOf("x")))
        assertEquals(DictationOutcome.Failed(DictationFailureKind.NO_SPEECH), DictationResults.interpret(InputMethod.KEYBOARD, Activity.RESULT_OK, listOf(null)))
    }
}

class DictationPresentationTest {
    @Test
    fun `every failure says something and offers a way forward`() {
        for (method in InputMethod.entries) for (kind in DictationFailureKind.entries) {
            val n = DictationPresentation.notice(kind, method)
            assertTrue("$kind/$method has words", n.message.isNotBlank())
            assertTrue("$kind/$method offers some next step", n.canRetry || n.offerTyping || n.offerPhone)
        }
    }

    @Test
    fun `no speech says it didn't catch that and offers retry and typing`() {
        val n = DictationPresentation.notice(DictationFailureKind.NO_SPEECH, InputMethod.SPEECH)
        assertEquals("Didn't catch that.", n.message)
        assertTrue(n.canRetry && n.offerTyping)
    }

    @Test
    fun `a network failure gives connection guidance and a way around it`() {
        val n = DictationPresentation.notice(DictationFailureKind.NETWORK, InputMethod.SPEECH)
        assertTrue(n.message.contains("Wi-Fi") || n.message.contains("connection"))
        assertTrue(n.canRetry && n.offerTyping && n.offerPhone)
    }

    @Test
    fun `a missing dictation screen explains itself, cannot be retried, and offers typing and the phone`() {
        val n = DictationPresentation.notice(DictationFailureKind.NO_HANDLER, InputMethod.SPEECH)
        assertTrue(n.message.contains("isn't available"))
        assertFalse(n.canRetry)
        assertTrue(n.offerTyping && n.offerPhone)
    }

    @Test
    fun `a plain cancellation has no wording at all - it is not a failure`() {
        // Cancelled is a DictationOutcome of its own; there is deliberately no DictationFailureKind for it.
        assertTrue(DictationFailureKind.entries.none { it.name.contains("CANCEL") })
    }

    @Test
    fun `the source is labelled honestly - never as an on-device or offline engine`() {
        assertEquals("Watch dictation", DictationPresentation.sourceLabel(InputMethod.SPEECH))
        assertEquals("Typed on this watch", DictationPresentation.sourceLabel(InputMethod.KEYBOARD))
        val everything = InputMethod.entries.map(DictationPresentation::sourceLabel) + DictationPresentation.NETWORK_NOTE
        assertTrue(everything.none { it.contains("Nano", true) || it.contains("offline", true) || it.contains("on-device", true) })
    }

    @Test
    fun `the help note is the agreed wording`() {
        assertEquals(
            "Watch dictation may need an internet connection. StageScope sends the transcript to your phone for the AI response.",
            DictationPresentation.NETWORK_NOTE,
        )
    }

    @Test
    fun `prompts are short and tied to the task`() {
        assertEquals("Describe the issue", DictationPresentation.prompt(TaskKind.LOG_ISSUE, InputMethod.SPEECH))
        assertEquals("Type the issue", DictationPresentation.prompt(TaskKind.LOG_ISSUE, InputMethod.KEYBOARD))
        assertEquals("Ask StageScope", DictationPresentation.prompt(TaskKind.ANALYZE_SOUND, InputMethod.SPEECH))
    }

    @Test
    fun `a replacement that did not work says the earlier words are still there`() {
        assertTrue(DictationPresentation.keptNote(DictationFailureKind.NO_SPEECH)!!.contains("still here"))
        assertEquals(null, DictationPresentation.keptNote(null))
    }
}
