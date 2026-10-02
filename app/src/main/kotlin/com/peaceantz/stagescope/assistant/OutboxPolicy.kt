package com.peaceantz.stagescope.assistant

/** What to do next with one outbox entry. Pure, so the rules that protect against stale sends are unit-tested. */
sealed interface OutboxAction {
    data object None : OutboxAction
    data object Send : OutboxAction
    data class MarkStale(val reason: String) : OutboxAction
    data class MarkExpired(val reason: String) : OutboxAction
    data class MarkFailed(val reason: String) : OutboxAction
}

/**
 * The rules for delivering a question to the phone:
 *
 *  - A request queued while the phone was away is sent on reconnect **only while it is still fresh**
 *    ([AUTO_SEND_MAX_AGE_MS]). An older one is *stale*: it asked about a moment that has passed (and carries
 *    that moment's measurement), so it waits for the person to say "send it anyway", and after
 *    [EXPIRE_AFTER_MS] it expires. Nothing is ever auto-sent hours later.
 *  - A request with no acknowledgement is re-sent with the **same id** (the phone recognises it and never
 *    runs it twice), a bounded number of times, then reported as failed.
 *  - Once the phone has acknowledged, the watch does not resend: the phone owns the work.
 */
object OutboxPolicy {
    const val AUTO_SEND_MAX_AGE_MS = 3 * 60_000L
    const val EXPIRE_AFTER_MS = 30 * 60_000L
    const val ACK_TIMEOUT_MS = 8_000L
    const val MAX_ATTEMPTS = 5
    const val GIVE_UP_AFTER_MS = 2 * 60_000L

    /** How long an acknowledged request may stay quiet before the watch asks the phone what happened to it. */
    const val CHECK_STATUS_AFTER_MS = 90_000L

    fun decide(entry: OutboxEntry, nowEpochMs: Long, phoneReachable: Boolean): OutboxAction {
        val age = (nowEpochMs - entry.createdAtEpochMs).coerceAtLeast(0L)
        return when (entry.state) {
            OutboxState.QUEUED_OFFLINE -> when {
                age >= EXPIRE_AFTER_MS -> OutboxAction.MarkExpired("This question waited ${age / 60_000} min for your phone, so it was dropped. Ask again.")
                age > AUTO_SEND_MAX_AGE_MS -> OutboxAction.MarkStale("This question is ${age / 60_000} min old and was about a moment that has passed. Send it anyway, or discard it.")
                phoneReachable -> OutboxAction.Send
                else -> OutboxAction.None
            }
            OutboxState.SENDING -> {
                val last = entry.lastAttemptAtEpochMs ?: entry.createdAtEpochMs
                when {
                    age >= GIVE_UP_AFTER_MS || entry.attempts >= MAX_ATTEMPTS ->
                        OutboxAction.MarkFailed("Your phone didn't confirm it got this. Check the phone is nearby and StageScope is installed, then retry.")
                    !phoneReachable -> OutboxAction.None
                    nowEpochMs - last >= ACK_TIMEOUT_MS -> OutboxAction.Send
                    else -> OutboxAction.None
                }
            }
            // Stale entries never move on their own; the person decides.
            OutboxState.STALE -> if (age >= EXPIRE_AFTER_MS) OutboxAction.MarkExpired("This question was never sent and has expired.") else OutboxAction.None
            else -> OutboxAction.None
        }
    }

    /** True when an acknowledged request has been silent long enough to ask the phone for its status. */
    fun shouldCheckStatus(entry: OutboxEntry, nowEpochMs: Long): Boolean =
        (entry.state == OutboxState.ACKED || entry.state == OutboxState.RUNNING) &&
            nowEpochMs - (entry.ackedAtEpochMs ?: entry.createdAtEpochMs) >= CHECK_STATUS_AFTER_MS
}
