package com.peaceantz.stagescope.shared.issues

import com.peaceantz.stagescope.shared.util.StageScopeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueSyncTest {
    private val watch = "w-1"
    private val phone = "p-1"

    private fun newIssue(replica: String = watch) = IssueOps.create(
        id = "issue-1", replica = replica, nowEpochMs = 1_000L,
        originalObservation = "Mic 12 crackled during the opening number",
    )

    @Test
    fun `merge is idempotent so duplicate deliveries change nothing`() {
        val a = newIssue()
        assertEquals(a, IssueOps.merge(a, a))
        val edited = IssueOps.edit(a, watch, IssuePatch(description = Change("Mic 12 crackles")))
        val once = IssueOps.merge(a, edited)
        assertEquals(once, IssueOps.merge(once, edited))
        assertEquals(once, IssueOps.merge(once, a))
    }

    @Test
    fun `merge is commutative and associative regardless of delivery order`() {
        val base = newIssue()
        val onWatch = IssueOps.edit(base, watch, IssuePatch(description = Change("Crackle on mic 12"), equipment = Change("Mic 12")))
        val onPhone = IssueOps.edit(base, phone, IssuePatch(description = Change("Mic 12 distorted"), channel = Change("12")))
        val third = IssueOps.resolve(base, phone, ResolutionCertainty.TENTATIVE, "seems resolved")

        assertEquals(IssueOps.merge(onWatch, onPhone), IssueOps.merge(onPhone, onWatch))
        assertEquals(
            IssueOps.merge(IssueOps.merge(onWatch, onPhone), third),
            IssueOps.merge(onWatch, IssueOps.merge(onPhone, third)),
        )
    }

    @Test
    fun `concurrent edits to the same field keep both texts and pick the same shown value on both devices`() {
        val base = newIssue()
        val onWatch = IssueOps.edit(base, watch, IssuePatch(attemptedFix = Change("Swapped the cable")))
        val onPhone = IssueOps.edit(base, phone, IssuePatch(attemptedFix = Change("Replaced the pack")))

        val watchSide = IssueOps.merge(onWatch, onPhone)
        val phoneSide = IssueOps.merge(onPhone, onWatch)
        assertEquals(watchSide.view().attemptedFix, phoneSide.view().attemptedFix)

        val conflicts = watchSide.view().conflicts
        assertEquals(1, conflicts.size)
        assertEquals(IssueField.ATTEMPTED_FIX, conflicts.first().field)
        val allTexts = setOf(conflicts.first().shownValue) + conflicts.first().otherValues
        assertEquals(setOf("Swapped the cable", "Replaced the pack"), allTexts)
    }

    @Test
    fun `a deliberate edit after seeing a conflict resolves it`() {
        val base = newIssue()
        val a = IssueOps.edit(base, watch, IssuePatch(description = Change("A")))
        val b = IssueOps.edit(base, phone, IssuePatch(description = Change("B")))
        val conflicted = IssueOps.merge(a, b)
        assertTrue(conflicted.view().conflicts.isNotEmpty())

        val picked = IssueOps.edit(conflicted, watch, IssuePatch(description = Change("A and B, reconciled")))
        assertTrue(picked.view().conflicts.isEmpty())
        // The resolution dominates both siblings, so the other replica converges to it too.
        assertEquals("A and B, reconciled", IssueOps.merge(picked, b).view().description)
        assertEquals("A and B, reconciled", IssueOps.merge(a, picked).view().description)
    }

    @Test
    fun `re-picking the already shown value still clears a conflict`() {
        val base = newIssue()
        val a = IssueOps.edit(base, watch, IssuePatch(description = Change("Same")))
        val b = IssueOps.edit(base, phone, IssuePatch(description = Change("Other")))
        val conflicted = IssueOps.merge(a, b)
        val shown = conflicted.view().description
        val cleared = IssueOps.edit(conflicted, phone, IssuePatch(description = Change(shown)))
        assertTrue(cleared.view().conflicts.isEmpty())
    }

    @Test
    fun `different fields edited concurrently both survive without a conflict`() {
        val base = newIssue()
        val a = IssueOps.edit(base, watch, IssuePatch(equipment = Change("Mic 12")))
        val b = IssueOps.edit(base, phone, IssuePatch(attemptedFix = Change("Swapped the cable")))
        val merged = IssueOps.merge(a, b).view()
        assertEquals("Mic 12", merged.equipment)
        assertEquals("Swapped the cable", merged.attemptedFix)
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun `wall clocks never decide who wins`() {
        // The 'later' wall-clock edit (phone, clock far in the future) does not outrank a causally later one.
        val base = newIssue()
        val phoneEdit = IssueOps.edit(base, phone, IssuePatch(description = Change("phone, clock set to 2099")))
        val watchSawIt = IssueOps.merge(base, phoneEdit)
        val watchEdit = IssueOps.edit(watchSawIt, watch, IssuePatch(description = Change("watch, saw the phone edit first")))
        assertEquals("watch, saw the phone edit first", IssueOps.merge(phoneEdit, watchEdit).view().description)
        assertTrue(IssueOps.merge(phoneEdit, watchEdit).view().conflicts.isEmpty())
    }

    @Test
    fun `a delete survives sync as a tombstone and does not resurrect`() {
        val base = newIssue()
        val deletedOnWatch = IssueOps.delete(base, watch)
        val phoneStillHasOld = base
        val merged = IssueOps.merge(phoneStillHasOld, deletedOnWatch)
        assertTrue(merged.view().deleted)
        // Re-delivering the old live copy later must not bring it back.
        assertTrue(IssueOps.merge(merged, phoneStillHasOld).view().deleted)
    }

    @Test
    fun `a delete concurrent with an edit keeps the issue visible so no text is lost`() {
        val base = newIssue()
        val deletedOnWatch = IssueOps.delete(base, watch)
        val editedOnPhone = IssueOps.edit(base, phone, IssuePatch(description = Change("Typed on the phone while the watch deleted it")))
        val merged = IssueOps.merge(deletedOnWatch, editedOnPhone)
        assertFalse("an edit the deleter never saw must not be hidden", merged.view().deleted)
        assertTrue(merged.view().conflicts.any { it.field == IssueField.DELETED })
        assertEquals("Typed on the phone while the watch deleted it", merged.view().description)
        // Either delivery order agrees.
        assertEquals(merged, IssueOps.merge(editedOnPhone, deletedOnWatch))
    }

    @Test
    fun `a restore after a delete wins over a late re-delivery of the old delete`() {
        val base = newIssue()
        val deleted = IssueOps.delete(base, watch)
        val restored = IssueOps.restore(deleted, phone)
        assertFalse(restored.view().deleted)
        assertFalse(IssueOps.merge(restored, deleted).view().deleted)
        assertFalse(IssueOps.merge(deleted, restored).view().deleted)
    }

    @Test
    fun `after a delete-versus-edit race the person can still confirm the delete and it sticks everywhere`() {
        val base = newIssue()
        val deletedOnWatch = IssueOps.delete(base, watch)
        val editedOnPhone = IssueOps.edit(base, phone, IssuePatch(description = Change("Typed while the watch deleted it")))
        val raced = IssueOps.merge(deletedOnWatch, editedOnPhone)
        assertFalse(raced.view().deleted)

        val confirmed = IssueOps.chooseSibling(raced, phone, IssueField.DELETED, index = 1)
        assertTrue("a fresh delete dominating the unseen edit hides the issue", confirmed.view().deleted)
        assertTrue(confirmed.view().conflicts.isEmpty())
        // Late re-deliveries of either older copy cannot resurrect it.
        assertTrue(IssueOps.merge(confirmed, editedOnPhone).view().deleted)
        assertTrue(IssueOps.merge(deletedOnWatch, confirmed).view().deleted)
    }

    @Test
    fun `after a delete-versus-edit race the person can keep the issue and the conflict clears`() {
        val base = newIssue()
        val deletedOnWatch = IssueOps.delete(base, watch)
        val editedOnPhone = IssueOps.edit(base, phone, IssuePatch(description = Change("Typed while the watch deleted it")))
        val raced = IssueOps.merge(deletedOnWatch, editedOnPhone)

        val kept = IssueOps.chooseSibling(raced, watch, IssueField.DELETED, index = 0)
        assertFalse(kept.view().deleted)
        assertTrue(kept.view().conflicts.isEmpty())
        assertEquals("Typed while the watch deleted it", kept.view().description)
        assertFalse(IssueOps.merge(kept, deletedOnWatch).view().deleted)
    }

    @Test
    fun `choosing a sibling resolves the conflict to exactly that value on both replicas`() {
        val base = newIssue()
        val a = IssueOps.edit(base, watch, IssuePatch(attemptedFix = Change("Swapped the cable")))
        val b = IssueOps.edit(base, phone, IssuePatch(attemptedFix = Change("Replaced the pack")))
        val conflicted = IssueOps.merge(a, b)
        val values = conflicted.attemptedFix.siblings.map { it.value }
        assertEquals(setOf("Swapped the cable", "Replaced the pack"), values.toSet())

        values.indices.forEach { i ->
            val chosen = IssueOps.chooseSibling(conflicted, watch, IssueField.ATTEMPTED_FIX, i)
            assertTrue(chosen.view().conflicts.isEmpty())
            assertEquals(values[i], chosen.view().attemptedFix)
            assertEquals(values[i], IssueOps.merge(chosen, b).view().attemptedFix)
            assertEquals(values[i], IssueOps.merge(a, chosen).view().attemptedFix)
        }
    }

    @Test
    fun `a nullable field can be resolved to cleared or to a value`() {
        val base = newIssue()
        val withEquipment = IssueOps.edit(base, watch, IssuePatch(equipment = Change("Mic 12")))
        val cleared = IssueOps.edit(base, phone, IssuePatch(equipment = Change("Mic 13")))
        val conflicted = IssueOps.merge(withEquipment, cleared)
        assertEquals(2, conflicted.equipment.siblings.size)
        val chosen = IssueOps.chooseSibling(conflicted, phone, IssueField.EQUIPMENT, 1)
        assertTrue(chosen.view().conflicts.isEmpty())
        assertEquals(conflicted.equipment.siblings[1].value, chosen.view().equipment)
    }

    @Test
    fun `choosing an index that does not exist changes nothing`() {
        val base = newIssue()
        assertEquals(base, IssueOps.chooseSibling(base, watch, IssueField.DESCRIPTION, 5))
    }

    @Test
    fun `seems resolved stays tentative until confirmed`() {
        val resolved = IssueOps.resolve(newIssue(), watch, ResolutionCertainty.TENTATIVE, "seems resolved")
        assertEquals("Seems resolved", resolved.view().statusLabel())
        assertEquals(IssueStatus.RESOLVED, resolved.view().status)
        val confirmed = IssueOps.resolve(resolved, watch, ResolutionCertainty.CONFIRMED, null)
        assertEquals("Resolved", confirmed.view().statusLabel())
        assertEquals("Reopened", IssueOps.reopen(confirmed, watch, "crackle is back").view().statusLabel())
    }

    @Test
    fun `the original observation is never rewritten by edits or AI wording`() {
        val base = newIssue()
        val edited = IssueOps.edit(base, watch, IssuePatch(description = Change("Reworded"), aiWording = Change("AI-polished wording")))
        assertEquals("Mic 12 crackled during the opening number", edited.view().originalObservation)
        assertEquals("AI-polished wording", edited.view().aiWording)
        assertEquals("Reworded", edited.view().description)
    }

    @Test
    fun `revision counts edits across both replicas`() {
        val base = newIssue()
        assertEquals(1L, base.revision)
        val e1 = IssueOps.edit(base, watch, IssuePatch(equipment = Change("Mic 12")))
        val e2 = IssueOps.edit(base, phone, IssuePatch(channel = Change("12")))
        assertEquals(2L, e1.revision)
        assertEquals(3L, IssueOps.merge(e1, e2).revision)
    }

    @Test
    fun `an edit that changes nothing does not bump the revision`() {
        val base = newIssue()
        val same = IssueOps.edit(base, watch, IssuePatch(description = Change(base.view().description)))
        assertEquals(base, same)
    }

    @Test
    fun `ledger operations are idempotent by operation id`() {
        var ledger = IssueLedgerState(replicaId = watch)
        ledger = IssueLedger.create(ledger, "op-1", newIssue())
        val again = IssueLedger.create(ledger, "op-1", newIssue())
        assertEquals(ledger, again)

        val patched = IssueLedger.edit(ledger, "op-2", "issue-1", IssuePatch(equipment = Change("Mic 12")))
        val retried = IssueLedger.edit(patched, "op-2", "issue-1", IssuePatch(equipment = Change("Mic 12")))
        assertEquals(patched, retried)
        assertEquals(2L, patched.issues.getValue("issue-1").revision)
    }

    @Test
    fun `duplicate remote deliveries and any order converge`() {
        val base = newIssue()
        val fromWatch = IssueOps.edit(base, watch, IssuePatch(equipment = Change("Mic 12")))
        val fromPhone = IssueOps.edit(base, phone, IssuePatch(severity = Change(IssueSeverity.HIGH)))
        var l1 = IssueLedgerState(replicaId = phone)
        for (s in listOf(fromWatch, fromPhone, fromWatch, base, fromPhone)) l1 = IssueLedger.mergeRemote(l1, s)
        var l2 = IssueLedgerState(replicaId = phone)
        for (s in listOf(base, fromPhone, fromWatch)) l2 = IssueLedger.mergeRemote(l2, s)
        assertEquals(l1.issues, l2.issues)
    }

    @Test
    fun `state survives serialization as it would across process death`() {
        var ledger = IssueLedgerState(replicaId = watch)
        ledger = IssueLedger.create(ledger, "op-1", newIssue())
        val conflicted = IssueOps.merge(
            IssueOps.edit(ledger.issues.getValue("issue-1"), watch, IssuePatch(description = Change("A"))),
            IssueOps.edit(ledger.issues.getValue("issue-1"), phone, IssuePatch(description = Change("B"))),
        )
        ledger = ledger.copy(issues = mapOf("issue-1" to conflicted))
        val text = StageScopeJson.encodeToString(IssueLedgerState.serializer(), ledger)
        val back = StageScopeJson.decodeFromString(IssueLedgerState.serializer(), text)
        assertEquals(ledger, back)
        assertTrue(back.issues.getValue("issue-1").view().conflicts.isNotEmpty())
    }

    @Test
    fun `views filter by performance status and text`() {
        var ledger = IssueLedgerState(replicaId = watch)
        ledger = IssueLedger.create(ledger, "o1", IssueOps.create("i1", watch, 1, "Mic 12 crackled", performanceId = "perf-1"))
        ledger = IssueLedger.create(ledger, "o2", IssueOps.create("i2", watch, 2, "Wedge feedback", performanceId = "perf-2"))
        ledger = IssueLedger.resolve(ledger, "o3", "i1", ResolutionCertainty.CONFIRMED, null)
        ledger = IssueLedger.delete(ledger, "o4", "i2")

        assertEquals(listOf("i1"), IssueLedger.views(ledger).map { it.id })
        assertEquals(listOf("i1", "i2").toSet(), IssueLedger.views(ledger, IssueFilter(includeDeleted = true)).map { it.id }.toSet())
        assertEquals(emptyList<String>(), IssueLedger.views(ledger, IssueFilter(statuses = setOf(IssueStatus.OPEN))).map { it.id })
        assertEquals(listOf("i1"), IssueLedger.views(ledger, IssueFilter(performanceId = "perf-1")).map { it.id })
        assertEquals(listOf("i1"), IssueLedger.views(ledger, IssueFilter(query = "CRACK")).map { it.id })
    }
}
