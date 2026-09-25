package com.peaceantz.stagescope.ui.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class RingSlotAssignerTest {

    private val empty = listOf<Long?>(null, null, null, null, null)

    @Test
    fun `fresh captures fill empty slots in id order`() {
        val result = RingSlotAssigner.update(empty, liveIds = listOf(3L, 1L, 2L))
        assertEquals(listOf<Long?>(1L, 2L, 3L, null, null), result)
    }

    @Test
    fun `an existing capture keeps its own slot as prominence-driven recency changes`() {
        val initial = RingSlotAssigner.update(empty, liveIds = listOf(1L, 2L, 3L))
        // Same live set, different order (as a naive recency-sort would produce) must not move anything.
        val restated = RingSlotAssigner.update(initial, liveIds = listOf(3L, 1L, 2L))
        assertEquals(initial, restated)
    }

    @Test
    fun `removing a capture frees its slot without shifting the others`() {
        val initial = RingSlotAssigner.update(empty, liveIds = listOf(1L, 2L, 3L))
        val afterRemoval = RingSlotAssigner.update(initial, liveIds = listOf(1L, 3L))
        val slotOfTwo = initial.indexOf(2L)
        assertEquals(null, afterRemoval[slotOfTwo])
        assertEquals(initial.indexOf(1L), afterRemoval.indexOf(1L))
        assertEquals(initial.indexOf(3L), afterRemoval.indexOf(3L))
    }

    @Test
    fun `a new capture claims the first empty slot left by an earlier removal`() {
        val initial = RingSlotAssigner.update(empty, liveIds = listOf(1L, 2L, 3L))
        val afterRemoval = RingSlotAssigner.update(initial, liveIds = listOf(1L, 3L))
        val withNewcomer = RingSlotAssigner.update(afterRemoval, liveIds = listOf(1L, 3L, 4L))
        val slotOfTwo = initial.indexOf(2L)
        assertEquals(4L, withNewcomer[slotOfTwo])
    }

    @Test
    fun `all five slots fill without collision when the bank is full`() {
        val result = RingSlotAssigner.update(empty, liveIds = listOf(5L, 4L, 3L, 2L, 1L))
        assertEquals(listOf<Long?>(1L, 2L, 3L, 4L, 5L), result)
        assertEquals(5, result.filterNotNull().distinct().size)
    }

    @Test
    fun `clearing everything empties every slot`() {
        val initial = RingSlotAssigner.update(empty, liveIds = listOf(1L, 2L, 3L))
        val cleared = RingSlotAssigner.update(initial, liveIds = emptyList())
        assertEquals(empty, cleared)
    }

    @Test
    fun `clearing unpinned captures out of a full bank leaves the pinned ones in their original slots`() {
        // Mirrors Ring's "Clear unpinned" action: 5 slots full, then only the pinned ids (2 and 4)
        // remain live -- their slots must not move even though 3 slots emptied at once.
        val initial = RingSlotAssigner.update(empty, liveIds = listOf(1L, 2L, 3L, 4L, 5L))
        val afterClearUnpinned = RingSlotAssigner.update(initial, liveIds = listOf(2L, 4L))
        assertEquals(initial.indexOf(2L), afterClearUnpinned.indexOf(2L))
        assertEquals(initial.indexOf(4L), afterClearUnpinned.indexOf(4L))
        assertEquals(2, afterClearUnpinned.count { it != null })
    }
}
