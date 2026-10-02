package com.peaceantz.stagescope.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guard on the phone's *source*: it must not record the person or turn watch audio into text, on the phone or in a cloud
 * service. Comments are stripped first, so explaining the rule never trips the check.
 */
class NoTranscriptionRegressionTest {
    private val sources: List<File> = run {
        val root = listOf(File("src/main/kotlin"), File("phone/src/main/kotlin")).first { it.isDirectory }
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun code(file: File): String = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun offenders(pattern: Regex): List<String> = sources.filter { pattern.containsMatchIn(code(it)) }.map { it.name }

    @Test
    fun `the sources were found`() {
        assertTrue(sources.any { it.name == "PhoneMessageHandler.kt" })
    }

    @Test
    fun `no speech recognizer and no audio transcription anywhere on the phone`() {
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""SpeechRecognizer|RecognizerIntent|RecognitionListener|Transcriber|audio/transcriptions|gpt-transcribe|VoiceReceiver""")),
        )
    }

    @Test
    fun `no transcription worker is defined - only the tag of the old one is used, to cancel what it left queued`() {
        assertEquals(emptyList<String>(), offenders(Regex("""class\s+TranscribeWorker""")))
    }

    @Test
    fun `the phone never reads a watch audio channel`() {
        assertEquals(emptyList<String>(), offenders(Regex("""getInputStream|\.receive\(channel|AudioRecord|MediaRecorder""")))
    }

    @Test
    fun `the only thing done with a legacy voice channel is closing it`() {
        val service = sources.first { it.name == "WatchListenerService.kt" }.let(::code)
        assertTrue(service.contains("close(channel)"))
    }
}
