package com.peaceantz.stagescope.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guard on the *source*, because the rule it protects ("StageScope does not record the person or send audio to the phone for
 * transcription") is exactly the kind a later edit could quietly break. Unit tests run with the module directory as the working
 * directory. Comments are stripped first, so explaining the rule never trips the check.
 */
class NoRecordingRegressionTest {
    private val mainSources: List<File> = run {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun offenders(pattern: Regex): List<String> = mainSources.filter { pattern.containsMatchIn(code(it)) }.map { it.name }

    @Test
    fun `the sources were found`() {
        assertTrue(mainSources.any { it.name == "DictationController.kt" })
    }

    @Test
    fun `no recognizer of our own - dictation is only ever the system screen`() {
        assertEquals(emptyList<String>(), offenders(Regex("""createOnDeviceSpeechRecognizer|isOnDeviceRecognitionAvailable|createSpeechRecognizer|SpeechRecognizer\.|RecognitionListener""")))
    }

    @Test
    fun `offline is never forced or required`() {
        assertEquals(emptyList<String>(), offenders(Regex("""EXTRA_PREFER_OFFLINE|EXTRA_PARTIAL_RESULTS""")))
    }

    @Test
    fun `no audio is sent to the phone - no voice offer, no voice channel, no streaming`() {
        assertEquals(emptyList<String>(), offenders(Regex("""\bVoiceOffer\s*\(|CHANNEL_VOICE|sendVoice|openChannel|getChannelClient|getOutputStream""")))
    }

    @Test
    fun `the old recorder is gone - the only AudioRecord left is the measurement engine's`() {
        assertEquals(listOf("AudioCaptureEngine.kt"), offenders(Regex("""\bAudioRecord\s*\(|MediaRecorder""")))
    }

    @Test
    fun `nothing starts a dictation except an explicit call - not the app, a service, a tile or a notification`() {
        // The only code that asks the controller to begin a session is the ViewModel's explicit startListening / redo / retry.
        val callers = mainSources.filter { Regex("""\.begin\(""").containsMatchIn(code(it)) }.map { it.name }.toSet() - setOf("DictationController.kt")
        assertEquals(setOf("AssistantViewModel.kt"), callers)
    }
}
