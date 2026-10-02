package com.peaceantz.stagescope.assistant

import com.peaceantz.stagescope.shared.issues.Change
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.issues.IssuePatch
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.issues.IssueStatus
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatchIssueRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val published = mutableListOf<Pair<String, IssueState>>()
    private lateinit var file: File
    private lateinit var repo: WatchIssueRepository
    private var now = 1_800_000_000_000L
    private var ids = 0

    @Before
    fun setUp() {
        file = File(tmp.newFolder(), "issues.json")
        repo = newRepo()
    }

    private fun newRepo() = WatchIssueRepository(file, { replica, issue -> published += replica to issue }, clock = { now }, newId = { "id-" + (++ids) })

    private fun phoneItem(issue: IssueState, replica: String = "p-1") =
        "/stagescope/v1/issues/$replica/${issue.id}" to StageScopeJson.encodeToString(IssueState.serializer(), issue).toByteArray()

    @Test
    fun `logging needs no phone, keeps the person's exact words, and publishes the issue for later sync`() = runBlocking {
        val text = "Log an issue: mic 12 crackled during the opening number. Swapped the cable at intermission; seems resolved."
        val logged = repo.log(text, productionId = "prod-1", performanceId = "perf-1")

        val v = repo.view(logged.issueId)!!
        assertEquals("verbatim, never rewritten", text, v.originalObservation)
        assertEquals("prod-1", v.productionId)
        assertEquals("perf-1", v.performanceId)
        assertEquals(1, published.size)
        assertEquals(repo.replicaId, published.single().first)
        assertEquals(logged.issueId, published.single().second.id)
    }

    @Test
    fun `obvious fields are split out conservatively and a hedged resolution stays tentative`() = runBlocking {
        val logged = repo.log("Log an issue: mic 12 crackled during the opening number. Swapped the cable at intermission; seems resolved.", null, null)
        val v = repo.view(logged.issueId)!!
        assertTrue(v.equipment.orEmpty().contains("12"))
        assertTrue(v.attemptedFix.orEmpty().contains("Swapped the cable"))
        assertEquals(IssueStatus.RESOLVED, v.status)
        assertEquals(ResolutionCertainty.TENTATIVE, v.certainty)
        assertTrue(logged.tentativelyResolved)
        assertEquals("Seems resolved", v.statusLabel())
    }

    @Test
    fun `an unclear sentence is kept as typed instead of being guessed into a field`() = runBlocking {
        val logged = repo.log("The vocals felt thin in the second act", null, null)
        val v = repo.view(logged.issueId)!!
        assertEquals("The vocals felt thin in the second act", v.description)
        assertNull(v.equipment)
        assertNull(v.attemptedFix)
        assertEquals(IssueStatus.OPEN, v.status)
        assertFalse(logged.tentativelyResolved)
    }

    @Test
    fun `undo is a tombstone that is published, so the delete syncs and nothing brings it back`() = runBlocking {
        val id = repo.log("Mic 4 dropped out", null, null).issueId
        // What the phone already has: the issue as it was *before* the undo.
        val liveCopyOnPhone = repo.state.value.issues.getValue(id)
        published.clear()
        repo.undo(id)
        assertNull("hidden from the list", repo.view(id))
        assertTrue(repo.state.value.issues.getValue(id).view().deleted)
        assertTrue("the deletion itself was published", published.single().second.view().deleted)
        // That older, live copy arriving later (a delayed or re-delivered item) cannot resurrect it.
        repo.mergeFromPhone(listOf(phoneItem(liveCopyOnPhone)))
        assertNull(repo.view(id))
    }

    @Test
    fun `resolve and reopen change the status and publish each change`() = runBlocking {
        val id = repo.log("Mic 4 dropped out", null, null).issueId
        published.clear()
        repo.resolve(id, ResolutionCertainty.CONFIRMED)
        assertEquals("Resolved", repo.view(id)!!.statusLabel())
        repo.reopen(id)
        assertEquals("Reopened", repo.view(id)!!.statusLabel())
        assertEquals(2, published.size)
    }

    @Test
    fun `an issue logged on the phone appears here, and what we publish back is the merged copy`() = runBlocking {
        val fromPhone = IssueOps.create("phone-issue", "p-1", now, "Lav on mic 2 rustling")
        val changed = repo.mergeFromPhone(listOf(phoneItem(fromPhone)))
        assertTrue(changed)
        assertEquals("Lav on mic 2 rustling", repo.view("phone-issue")!!.originalObservation)
        assertEquals(listOf("phone-issue"), published.map { it.second.id })
        assertEquals(repo.replicaId, published.single().first)
    }

    @Test
    fun `merging the same delivery again changes nothing and publishes nothing`() = runBlocking {
        val item = phoneItem(IssueOps.create("phone-issue", "p-1", now, "Lav on mic 2 rustling"))
        repo.mergeFromPhone(listOf(item))
        published.clear()
        assertFalse(repo.mergeFromPhone(listOf(item)))
        assertTrue(published.isEmpty())
    }

    @Test
    fun `our own replica's items, malformed items, deleted data items and foreign paths are ignored`() = runBlocking {
        val own = IssueOps.create("mine", repo.replicaId, now, "Own copy")
        val changed = repo.mergeFromPhone(
            listOf(
                phoneItem(own, replica = repo.replicaId),
                "/stagescope/v1/issues/p-1/bad" to "{ nope".toByteArray(),
                "/stagescope/v1/issues/p-1/gone" to null,
                "/stagescope/v1/thread/abc" to "{}".toByteArray(),
            ),
        )
        assertFalse(changed)
        assertTrue(repo.state.value.issues.isEmpty())
    }

    @Test
    fun `edits made on both devices while apart are both kept as a conflict`() = runBlocking {
        val id = repo.log("Mic 4 dropped out", null, null).issueId
        val ours = repo.state.value.issues.getValue(id)
        // The phone edited the same field from the same starting point while the watch was offline.
        val theirs = IssueOps.edit(ours, "p-1", IssuePatch(attemptedFix = Change("Replaced the pack")))
        // ... and the watch edited its own copy too.
        repo.reopen(id) // an unrelated change on the watch
        val oursEdited = IssueLedger.edit(repo.state.value, "op-x", id, IssuePatch(attemptedFix = Change("Swapped the cable"))).issues.getValue(id)
        // Deliver the phone's edit to a ledger that has seen the watch's edit.
        val merged = IssueOps.merge(oursEdited, theirs)
        assertEquals(setOf("Swapped the cable", "Replaced the pack"), merged.attemptedFix.siblings.map { it.value }.toSet())
        assertTrue(merged.view().conflicts.isNotEmpty())
    }

    @Test
    fun `publishAll re-sends every issue, deleted ones included, under our own replica id`() = runBlocking {
        val a = repo.log("Mic 4 dropped out", null, null).issueId
        repo.log("Lav rustle", null, null)
        repo.undo(a)
        published.clear()
        repo.publishAll()
        assertEquals(2, published.size)
        assertTrue(published.all { it.first == repo.replicaId })
        assertTrue(published.any { it.second.view().deleted })
    }

    @Test
    fun `the log and its replica identity survive a restart`() = runBlocking {
        val id = repo.log("Mic 4 dropped out", "prod-1", "perf-1").issueId
        val replica = repo.replicaId
        val reborn = newRepo()
        assertEquals(replica, reborn.replicaId)
        assertNotNull(reborn.view(id))
        assertEquals("prod-1", reborn.view(id)!!.productionId)
    }

    @Test
    fun `a corrupt file is set aside rather than overwritten, and the watch starts a fresh log`() = runBlocking {
        repo.log("Mic 4 dropped out", null, null)
        file.writeText("{ this is not json")
        val reborn = newRepo()
        assertTrue(reborn.state.value.issues.isEmpty())
        assertTrue("the damaged file was quarantined, not destroyed", file.parentFile!!.listFiles()!!.any { it.name.contains("corrupt") })
    }
}
