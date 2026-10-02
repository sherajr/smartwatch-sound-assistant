package com.peaceantz.stagescope.assistant.speech

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.wear.input.RemoteInputIntentHelper

/**
 * The two system screens StageScope can open to get words from the person, and how to read what they return. Everything here is a
 * thin wrapper over documented platform calls -- the decisions live in [DictationController] and [DictationResults].
 *
 * Dictation follows Google's Wear OS guidance: an implicit `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with the free-form language
 * model, started through the Activity Result API, reading `EXTRA_RESULTS`. It deliberately sets **no** `EXTRA_LANGUAGE` (the
 * watch's own language is used), **no** `EXTRA_PREFER_OFFLINE` (the installed speech service decides how to reach its recognizer,
 * which may be Google's servers), and names **no** package or component (the person's installed handler is used).
 */
object DictationIntents {
    /** The key the text-input screen returns the typed words under. */
    const val TEXT_KEY = "stagescope_typed_text"

    fun speech(prompt: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (!prompt.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }

    /** The watch's own text-input screen (keyboard / handwriting / its own voice option). Its words come back under [TEXT_KEY]. */
    fun keyboard(label: String): Intent = RemoteInputIntentHelper.createActionRemoteInputIntent().also { intent ->
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(TEXT_KEY).setLabel(label).build()))
    }

    fun intentFor(method: InputMethod, prompt: String?): Intent = when (method) {
        InputMethod.SPEECH -> speech(prompt)
        InputMethod.KEYBOARD -> keyboard(prompt ?: "Type your question")
    }

    /** Both shapes are read for every result; the controller uses the one that matches the screen it opened. */
    fun toRaw(resultCode: Int, data: Intent?): RawDictationResult = RawDictationResult(
        resultCode = resultCode,
        speech = runCatching { data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.toList() }.getOrNull(),
        typed = runCatching { data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(TEXT_KEY)?.toString() }.getOrNull(),
    )

    /** True if some installed activity answers [method]'s intent. Needs the `<queries>` entries in the manifest (Android 11+ package visibility). */
    class Availability(private val context: Context) : DictationAvailability {
        override fun isAvailable(method: InputMethod): Boolean =
            runCatching { intentFor(method, null).resolveActivity(context.packageManager) != null }.getOrDefault(false)
    }
}
