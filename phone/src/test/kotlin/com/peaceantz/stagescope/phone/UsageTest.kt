package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.data.UsageEntry
import com.peaceantz.stagescope.phone.data.UsageGuard
import com.peaceantz.stagescope.phone.data.UsageLimits
import com.peaceantz.stagescope.phone.data.UsageVerdict
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.UsageSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
