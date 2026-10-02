package com.peaceantz.stagescope.assistant.speech

import android.app.Activity
import android.speech.RecognizerIntent
import com.peaceantz.stagescope.shared.assistant.TaskKind

/**
 * Reads what a system input screen sent back. Pure (just numbers and strings), so every outcome is unit-tested without a device.
 *
 * Rules, all deliberate:
 *  - words are only ever taken from a **successful** result: after an error, partial guesses are not used;
 *  - the first *non-blank* hypothesis wins (the recognizer orders them by confidence), trimmed at both ends and otherwise untouched;
 *  - a screen the person backed out of ([Activity.RESULT_CANCELED]) is [DictationOutcome.Cancelled] -- not "no speech", and not a
 *    network error, because the platform cannot tell us which it was and we do not pretend to know.
 */
object DictationResults {
    fun interpret(method: InputMethod, resultCode: Int, candidates: List<String?>?): DictationOutcome {
        if (resultCode == Activity.RESULT_OK) {
            val text = candidates?.firstNotNullOfOrNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            return if (text != null) DictationOutcome.Text(text) else DictationOutcome.Failed(DictationFailureKind.NO_SPEECH)
        }
        if (resultCode == Activity.RESULT_CANCELED) return DictationOutcome.Cancelled
        // Only a speech screen reports these; the text screen has just OK and cancelled.
        if (method == InputMethod.SPEECH) {
            when (resultCode) {
                RecognizerIntent.RESULT_NO_MATCH -> return DictationOutcome.Failed(DictationFailureKind.NO_SPEECH)
                RecognizerIntent.RESULT_NETWORK_ERROR -> return DictationOutcome.Failed(DictationFailureKind.NETWORK)
                RecognizerIntent.RESULT_SERVER_ERROR -> return DictationOutcome.Failed(DictationFailureKind.SERVER)
                RecognizerIntent.RESULT_AUDIO_ERROR -> return DictationOutcome.Failed(DictationFailureKind.AUDIO)
                RecognizerIntent.RESULT_CLIENT_ERROR -> return DictationOutcome.Failed(DictationFailureKind.CLIENT)
            }
        }
        return DictationOutcome.Failed(DictationFailureKind.UNKNOWN_RESULT)
    }
}

/**
 * The plain words for each way a dictation can end, in one place so the wording is testable and the same everywhere. Nothing here
 * claims to know more than the platform told us: a screen that was simply closed says so and is not blamed on the network.
 */
object DictationPresentation {
    /** What the person can do next after a failure. At most two round buttons are shown; the rest are list chips. */
    data class NoticeText(
        val message: String,
        val canRetry: Boolean,
        val offerTyping: Boolean,
        val offerPhone: Boolean,
    )

    /** The short prompt a system screen may show above the field. */
    fun prompt(task: TaskKind, method: InputMethod): String = when {
        method == InputMethod.KEYBOARD -> if (task == TaskKind.LOG_ISSUE) "Type the issue" else "Type your question"
        task == TaskKind.LOG_ISSUE -> "Describe the issue"
        else -> "Ask StageScope"
    }

    fun sourceLabel(method: InputMethod): String = when (method) {
        InputMethod.SPEECH -> "Watch dictation"
        InputMethod.KEYBOARD -> "Typed on this watch"
    }

    fun notice(kind: DictationFailureKind, method: InputMethod): NoticeText = when (kind) {
        DictationFailureKind.NO_SPEECH ->
            if (method == InputMethod.KEYBOARD) NoticeText("Nothing was entered.", canRetry = true, offerTyping = false, offerPhone = true)
            else NoticeText("Didn't catch that.", canRetry = true, offerTyping = true, offerPhone = true)
        DictationFailureKind.NETWORK, DictationFailureKind.SERVER ->
            NoticeText("Dictation couldn't reach its service. Check the watch's Wi-Fi or phone connection, then try again.", true, true, true)
        DictationFailureKind.AUDIO ->
            NoticeText("The watch's microphone wasn't available to dictation. Try again in a moment.", true, true, true)
        DictationFailureKind.CLIENT, DictationFailureKind.UNKNOWN_RESULT ->
            NoticeText("Dictation stopped without giving any words. Try again, or type it.", true, true, true)
        DictationFailureKind.NO_HANDLER ->
            NoticeText(
                if (method == InputMethod.KEYBOARD) "This watch has no text-input screen StageScope can open."
                else "Watch dictation isn't available on this watch.",
                canRetry = false, offerTyping = method == InputMethod.SPEECH, offerPhone = true,
            )
        DictationFailureKind.LAUNCH_FAILED ->
            NoticeText("The dictation screen didn't open. Try again.", true, true, true)
        DictationFailureKind.PHONE_SPEAKING ->
            NoticeText("Your phone is still speaking. Try again when it stops.", true, true, false)
        DictationFailureKind.ABANDONED ->
            NoticeText("Dictation didn't finish. Try again.", true, true, true)
    }

    /** The line shown above kept words when a replacement attempt did not produce anything. Null = nothing to say. */
    fun keptNote(kind: DictationFailureKind?): String? = when (kind) {
        null -> null
        DictationFailureKind.NO_SPEECH -> "Didn't catch that — your earlier words are still here."
        DictationFailureKind.NO_HANDLER -> "Dictation isn't available — your earlier words are still here."
        else -> "That didn't work — your earlier words are still here."
    }

    /** Shown in Settings/help. */
    const val NETWORK_NOTE = "Watch dictation may need an internet connection. StageScope sends the transcript to your phone for the AI response."
}
