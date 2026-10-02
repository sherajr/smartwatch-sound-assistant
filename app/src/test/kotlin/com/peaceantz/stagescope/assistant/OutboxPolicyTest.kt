package com.peaceantz.stagescope.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxPolicyTest {
    private val t0 = 1_000_000L

    private fun entry(
        state: OutboxState, createdAt: Long = t0, lastAttempt: Long? = null, attempts: Int = 0, ackedAt: Long? = null,
    ) = OutboxEntry(TestRequests.request(), state, createdAt, lastAttempt, attempts, ackedAt)

    private fun decide(e: OutboxEntry, now: Long, reachable: Boolean = true) = OutboxPolicy.decide(e, now, reachable)

    @Test
    fun `a fresh queued question is sent as soon as the phone is reachable and not before`() {
        assertEquals(OutboxAction.Send, decide(entry(OutboxState.QUEUED_OFFLINE), t0 + 10_000))
        assertEquals(OutboxAction.None, decide(entry(OutboxState.QUEUED_OFFLINE), t0 + 10_000, reachable = false))
    }

    @Test
    fun `the freshness window is three minutes`() {
        assertEquals(OutboxAction.Send, decide(entry(OutboxState.QUEUED_OFFLINE), t0 + OutboxPolicy.AUTO_SEND_MAX_AGE_MS))
        assertTrue(decide(entry(OutboxState.QUEUED_OFFLINE), t0 + OutboxPolicy.AUTO_SEND_MAX_AGE_MS + 1) is OutboxAction.MarkStale)
    }

    @Test
    fun `stale means never sent automatically, even when the phone is right there`() {
        val action = decide(entry(OutboxState.QUEUED_OFFLINE), t0 + 10 * 60_000, reachable = true)
        assertTrue(action is OutboxAction.MarkStale)
        assertTrue((action as OutboxAction.MarkStale).reason.contains("10 min"))
        assertEquals("a stale entry just sits there until the person acts", OutboxAction.None, decide(entry(OutboxState.STALE), t0 + 12 * 60_000))
    }

    @Test
    fun `everything expires after thirty minutes`() {
        assertTrue(decide(entry(OutboxState.QUEUED_OFFLINE), t0 + OutboxPolicy.EXPIRE_AFTER_MS) is OutboxAction.MarkExpired)
        assertTrue(decide(entry(OutboxState.STALE), t0 + OutboxPolicy.EXPIRE_AFTER_MS) is OutboxAction.MarkExpired)
    }

    @Test
    fun `an unacknowledged send is retried after the ack timeout, with the same entry`() {
        val e = entry(OutboxState.SENDING, lastAttempt = t0, attempts = 1)
        assertEquals(OutboxAction.None, decide(e, t0 + OutboxPolicy.ACK_TIMEOUT_MS - 1))
        assertEquals(OutboxAction.Send, decide(e, t0 + OutboxPolicy.ACK_TIMEOUT_MS))
        assertEquals("no phone, nothing to retry yet", OutboxAction.None, decide(e, t0 + OutboxPolicy.ACK_TIMEOUT_MS, reachable = false))
    }

    @Test
    fun `retrying stops after a bounded number of attempts or a bounded time`() {
        assertTrue(decide(entry(OutboxState.SENDING, lastAttempt = t0, attempts = OutboxPolicy.MAX_ATTEMPTS), t0 + 20_000) is OutboxAction.MarkFailed)
        assertTrue(decide(entry(OutboxState.SENDING, lastAttempt = t0, attempts = 1), t0 + OutboxPolicy.GIVE_UP_AFTER_MS) is OutboxAction.MarkFailed)
    }

    @Test
    fun `an acknowledged or finished question is never re-sent`() {
        for (s in listOf(OutboxState.ACKED, OutboxState.RUNNING, OutboxState.RESULT_READY, OutboxState.FAILED, OutboxState.CANCELLED, OutboxState.EXPIRED)) {
            assertEquals(s.name, OutboxAction.None, decide(entry(s, ackedAt = t0), t0 + 60 * 60_000))
        }
    }

    @Test
    fun `an acknowledged request silent for a while prompts a status check, a finished one never does`() {
        assertFalse(OutboxPolicy.shouldCheckStatus(entry(OutboxState.ACKED, ackedAt = t0), t0 + 10_000))
        assertTrue(OutboxPolicy.shouldCheckStatus(entry(OutboxState.ACKED, ackedAt = t0), t0 + OutboxPolicy.CHECK_STATUS_AFTER_MS))
        assertTrue(OutboxPolicy.shouldCheckStatus(entry(OutboxState.RUNNING, ackedAt = t0), t0 + OutboxPolicy.CHECK_STATUS_AFTER_MS))
        assertFalse(OutboxPolicy.shouldCheckStatus(entry(OutboxState.RESULT_READY, ackedAt = t0), t0 + 10 * 60_000))
    }

    @Test
    fun `state helpers classify correctly`() {
        assertTrue(OutboxState.RESULT_READY.isFinal && OutboxState.FAILED.isFinal && OutboxState.CANCELLED.isFinal && OutboxState.EXPIRED.isFinal)
        assertFalse(OutboxState.STALE.isFinal || OutboxState.QUEUED_OFFLINE.isFinal)
        assertTrue(OutboxState.SENDING.isWaitingOnPhone && OutboxState.ACKED.isWaitingOnPhone && OutboxState.RUNNING.isWaitingOnPhone)
        assertFalse(OutboxState.QUEUED_OFFLINE.isWaitingOnPhone)
    }
}
