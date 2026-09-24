package com.peaceantz.stagescope.ui.ring

/**
 * Assigns each currently-live ring capture id to one of a fixed number of screen slots, keeping a
 * capture's slot stable across updates instead of re-sorting by recency/prominence every ~100ms
 * (which is what naively rendering [com.peaceantz.stagescope.dsp.RingSnapshot.history] -- sorted by
 * `lastSeenAtMs` -- would do, since that timestamp changes on every live frame). A capture keeps
 * its slot for as long as it exists; only a genuinely new capture claims an empty slot, in id order
 * (ids are assigned in creation order, so this is deterministic when several appear in one tick).
 * Removing a capture frees its slot without shifting any other tile -- so a tap in flight always
 * lands on the capture the user is looking at.
 */
object RingSlotAssigner {
    fun update(previous: List<Long?>, liveIds: List<Long>, slotCount: Int = 5): List<Long?> {
        val liveSet = liveIds.toSet()
        val next = MutableList(slotCount) { index -> previous.getOrNull(index) }
        for (i in next.indices) {
            if (next[i] != null && next[i] !in liveSet) next[i] = null
        }
        val alreadyPlaced = next.filterNotNull().toSet()
        val newcomers = liveIds.filter { it !in alreadyPlaced }.sorted()
        var searchFrom = 0
        for (id in newcomers) {
            while (searchFrom < next.size && next[searchFrom] != null) searchFrom++
            if (searchFrom >= next.size) break // more live ids than slots should not happen (bank is bounded)
            next[searchFrom] = id
        }
        return next
    }
}
