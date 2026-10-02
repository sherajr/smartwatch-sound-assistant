package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.AudioLeaseKind
import com.peaceantz.stagescope.shared.protocol.PlaybackNotice
import com.peaceantz.stagescope.shared.protocol.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The paired phone tells the watch when it starts and stops speaking a reply, and re-announces every few
 * seconds while it does. This turns those notices into one time-limited audio lease, so measurement is
 * paused while the phone's loudspeaker is filling the room, and a lost "stopped" notice can never leave
 * it paused: without a heartbeat for [LEASE_TTL_MS] the lease simply expires.
 *
 * Notices arrive on a binder thread; they are handled one at a time on [scope] (the main thread), because the
 * coordinator drives the capture session.
 */
class PhonePlaybackBridge(
    private val coordinator: AudioCoordinator,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val leases = HashMap<String, Long>()

    fun onNotice(notice: PlaybackNotice) {
        scope.launch {
            mutex.withLock {
                when (notice.state) {
                    PlaybackState.STARTED -> {
                        val existing = leases[notice.utteranceId]
                        if (existing == null) leases[notice.utteranceId] = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, LEASE_TTL_MS)
                        else coordinator.refresh(existing, LEASE_TTL_MS)
                    }
                    PlaybackState.STOPPED -> leases.remove(notice.utteranceId)?.let(coordinator::release)
                }
            }
        }
    }

    companion object {
        /** Three missed 10-second heartbeats. */
        const val LEASE_TTL_MS = 30_000L
    }
}
