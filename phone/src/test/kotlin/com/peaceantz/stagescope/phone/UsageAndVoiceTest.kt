package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.data.UsageEntry
import com.peaceantz.stagescope.phone.data.UsageGuard
import com.peaceantz.stagescope.phone.data.UsageLimits
import com.peaceantz.stagescope.phone.data.UsageVerdict
import com.peaceantz.stagescope.phone.voice.TranscribeResult
import com.peaceantz.stagescope.phone.voice.Transcriber
import com.peaceantz.stagescope.phone.voice.TranscriberChain
import com.peaceantz.stagescope.phone.voice.Wav
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageGuardTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 15, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12) = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun chat(atMs: Long, estimatedMicros: Long? = null, reportedMicros: Long? = null, kind: String = "chat") = UsageEntry(
        "r-$atMs", atMs, ProviderId.OPENAI, "gpt-6.1-sol",
        UsageSummary(inputTokens = 100, outputTokens = 10, estimatedCostMicros = estimatedMicros, reportedCostMicros = reportedMicros), kind,
    )

    @Test
    fun `under every limit the verdict is ok`() {
        assertEquals(UsageVerdict.Ok, UsageGuard.check(UsageLimits(maxRequestsPerDay = 5), List(4) { chat(at(2026, 10, 15, 9)) }, now, zone))
    }

    @Test
    fun `the daily request limit blocks and says how to continue`() {
        val v = UsageGuard.check(UsageLimits(maxRequestsPerDay = 3), List(3) { chat(at(2026, 10, 15, 9)) }, now, zone)
        assertTrue(v is UsageVerdict.Blocked)
        assertTrue((v as UsageVerdict.Blocked).message.contains("Raise it"))
    }

    @Test
    fun `yesterday's requests and non-chat entries don't count toward today's limit`() {
        val entries = List(5) { chat(at(2026, 10, 14, 23)) } + List(5) { chat(at(2026, 10, 15, 9), kind = "speech") }
        assertEquals(UsageVerdict.Ok, UsageGuard.check(UsageLimits(maxRequestsPerDay = 1), entries, now, zone))
    }

    @Test
    fun `the day boundary follows the person's time zone, not UTC`() {
        // 22:30 local on the 14th is already the 15th in UTC; it must still count as the 14th here.
        val lateYesterday = ZonedDateTime.of(2026, 10, 14, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(UsageVerdict.Ok, UsageGuard.check(UsageLimits(maxRequestsPerDay = 1), listOf(chat(lateYesterday)), now, zone))
    }

    @Test
    fun `a monthly budget blocks once recorded spend reaches it`() {
        val limits = UsageLimits(maxRequestsPerDay = 100, monthlyBudgetUsd = 1.00)
        val v = UsageGuard.check(limits, listOf(chat(at(2026, 10, 3), estimatedMicros = 1_000_000)), now, zone)
        assertTrue(v is UsageVerdict.Blocked)
        assertTrue((v as UsageVerdict.Blocked).message.contains("as recorded by StageScope"))
    }

    @Test
    fun `approaching the budget warns with the honest caveat but still allows the request`() {
        val limits = UsageLimits(maxRequestsPerDay = 100, monthlyBudgetUsd = 10.00, warnAtPercent = 80)
        val v = UsageGuard.check(limits, listOf(chat(at(2026, 10, 3), estimatedMicros = 8_500_000)), now, zone)
        assertTrue(v is UsageVerdict.Warn)
        assertTrue((v as UsageVerdict.Warn).message.contains("not the provider's invoice"))
    }

    @Test
    fun `last month's spend never counts against this month`() {
        val limits = UsageLimits(maxRequestsPerDay = 100, monthlyBudgetUsd = 1.00)
        assertEquals(UsageVerdict.Ok, UsageGuard.check(limits, listOf(chat(at(2026, 9, 30), estimatedMicros = 50_000_000)), now, zone))
    }

    @Test
    fun `a provider-reported cost wins over the estimate, and an unknown cost adds nothing`() {
        val entries = listOf(
            chat(at(2026, 10, 2), estimatedMicros = 9_000_000, reportedMicros = 1_000_000),
            chat(at(2026, 10, 3), estimatedMicros = null, reportedMicros = null),
            chat(at(2026, 10, 4), estimatedMicros = 2_000_000),
        )
        assertEquals(3.0, UsageGuard.monthSpendUsd(entries, now, zone), 1e-9)
    }

    @Test
    fun `no budget means spend never blocks`() {
        val v = UsageGuard.check(UsageLimits(maxRequestsPerDay = 100, monthlyBudgetUsd = null), listOf(chat(at(2026, 10, 3), estimatedMicros = 999_000_000)), now, zone)
        assertEquals(UsageVerdict.Ok, v)
    }
}

class VoiceTest {
    @Test
    fun `the WAV container has a correct PCM header`() {
        val pcm = ByteArray(32_000) { (it % 7).toByte() } // 1 s of 16 kHz mono 16-bit
        val wav = Wav.fromPcm16Mono(pcm, 16_000)
        assertEquals(44 + pcm.size, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(pcm.toList(), wav.drop(44))
    }

    @Test
    fun `duration is computed from bytes and sample rate`() {
        assertEquals(1_000L, Wav.durationMs(32_000, 16_000))
        assertEquals(2_500L, Wav.durationMs(80_000, 16_000))
    }

    private class Fixed(val result: TranscribeResult) : Transcriber {
        var calls = 0
        override suspend fun transcribe(pcm16: ByteArray, sampleRateHz: Int): TranscribeResult { calls++; return result }
    }

    private val pcm = ByteArray(10)

    @Test
    fun `on-device success is used and the cloud is never contacted`() = runBlocking {
        val cloud = Fixed(TranscribeResult.Text("cloud", "Cloud", 1))
        val r = TranscriberChain(Fixed(TranscribeResult.Text("hello", "On-device")), cloud).transcribe(pcm, 16_000)
        assertEquals("hello", (r as TranscribeResult.Text).text)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `the cloud is a fallback only when it is enabled and could plausibly help`() = runBlocking {
        val cloud = Fixed(TranscribeResult.Text("from cloud", "Cloud (x)", 4_500))
        // Not enabled -> the on-device failure is reported as is.
        val off = TranscriberChain(Fixed(TranscribeResult.Failure("no recognizer", cloudMayHelp = true)), null).transcribe(pcm, 16_000)
        assertTrue(off is TranscribeResult.Failure)
        // Enabled, but the failure isn't something the cloud would fix (no speech in the audio) -> no upload.
        val silent = TranscriberChain(Fixed(TranscribeResult.Failure("no speech", cloudMayHelp = false)), cloud).transcribe(pcm, 16_000)
        assertTrue(silent is TranscribeResult.Failure)
        assertEquals(0, cloud.calls)
        // Enabled and plausible -> used, and the cost travels with the result.
        val used = TranscriberChain(Fixed(TranscribeResult.Failure("no recognizer", cloudMayHelp = true)), cloud).transcribe(pcm, 16_000) as TranscribeResult.Text
        assertEquals("from cloud", used.text)
        assertEquals(4_500L, used.costMicros)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun `when both fail the message explains both`() = runBlocking {
        val r = TranscriberChain(
            Fixed(TranscribeResult.Failure("No on-device recognition.", cloudMayHelp = true)),
            Fixed(TranscribeResult.Failure("Your OpenAI key was rejected.")),
        ).transcribe(pcm, 16_000) as TranscribeResult.Failure
        assertTrue(r.message.contains("No on-device recognition.") && r.message.contains("Your OpenAI key was rejected."))
    }
}
