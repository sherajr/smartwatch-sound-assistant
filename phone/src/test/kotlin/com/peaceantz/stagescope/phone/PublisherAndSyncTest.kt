package com.peaceantz.stagescope.phone

import com.peaceantz.stagescope.phone.data.InboxState
import com.peaceantz.stagescope.phone.data.PhoneData
import com.peaceantz.stagescope.phone.link.IssueSync
import com.peaceantz.stagescope.phone.link.ThreadPublisher
import com.peaceantz.stagescope.shared.actions.ActionMachine
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.EmailPurpose
import com.peaceantz.stagescope.shared.assistant.ChatTurn
import com.peaceantz.stagescope.shared.assistant.ProviderId
import com.peaceantz.stagescope.shared.assistant.TurnRole
import com.peaceantz.stagescope.shared.issues.Change
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.issues.IssuePatch
import com.peaceantz.stagescope.shared.issues.IssueState
import com.peaceantz.stagescope.shared.protocol.Progress
import com.peaceantz.stagescope.shared.protocol.ProgressStage
import com.peaceantz.stagescope.shared.protocol.RequestState
import com.peaceantz.stagescope.shared.protocol.ResultReady
import com.peaceantz.stagescope.shared.show.EmailAddress
import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadPublisherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var data: PhoneData
    private val link = FakeWatchLink()
    private lateinit var publisher: ThreadPublisher
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        data = PhoneData(tmp.newFolder())
        publisher = ThreadPublisher(data, link, clock = { now }, notificationsEnabled = { true }, appVersion = "0.2.0")
    }

    private suspend fun conversation(id: String = "conv-1", actionIds: List<String> = emptyList()) {
        data.conversations.createIfMissing(id, ProviderId.OPENAI, "gpt-6.1-sol", "Mic 12 crackle", null, now)
        data.conversations.appendTurn(
            id,
            ChatTurn(
                "t-1", TurnRole.ASSISTANT, "Full answer text", seq = 1, atEpochMs = now, providerId = ProviderId.OPENAI, modelId = "gpt-6.1-sol",
                watchSummary = "Short summary", actionIds = actionIds,
            ),
            now,
        )
    }

    @Test
    fun `a thread is published as a durable view and the nudge names the revision to read`() = runBlocking {
        conversation()
        publisher.publishThread("conv-1", "req-1", RequestState.COMPLETED)
        val view = link.threads.single()
        assertEquals("conv-1", view.conversationId)
        assertEquals("gpt-6.1-sol", view.modelId)
        assertEquals("Short summary", view.summary)
        val nudge = link.sentOf<ResultReady>().single()
        assertEquals(view.revision, nudge.revision)
        assertEquals("req-1", nudge.requestId)
    }

    @Test
    fun `republishing without a request id updates the view but sends no message`() = runBlocking {
        conversation()
        publisher.publishThread("conv-1")
        assertEquals(1, link.threads.size)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `an unknown conversation publishes nothing`() = runBlocking {
        publisher.publishThread("ghost", "req-1")
        assertTrue(link.threads.isEmpty() && link.sent.isEmpty())
    }

    @Test
    fun `a changed action card bumps the thread revision and republishes it`() = runBlocking {
        conversation(actionIds = listOf("a-1"))
        publisher.publishThread("conv-1")
        val before = link.threads.last().revision
        val record = ActionMachine.create("a-1", EmailDraft(EmailPurpose.ISSUE_HELP, to = listOf(EmailAddress("a@b.example")), subject = "s", body = "b"), now, "conv-1")
        data.actions.put(record)
        publisher.actionChanged(record)
        assertTrue(link.threads.last().revision > before)
        assertEquals(listOf("a-1"), link.threads.last().actions.map { it.actionId })
    }

    @Test
    fun `progress is a small message`() = runBlocking {
        publisher.progress("req-1", ProgressStage.WRITING, null)
        assertEquals(Progress("req-1", ProgressStage.WRITING, null), link.sentOf<Progress>().single())
    }

    @Test
    fun `providers revision comes from the published content only`() = runBlocking {
        val v1 = publisher.providersView({ it == ProviderId.OPENAI }, googleConnected = false)
        // Settings that the watch is never shown must not make the item look new.
        data.settings.update { it.copy(lastWatchContactEpochMs = 99L, watchAppVersion = "9.9") }
        val v2 = publisher.providersView({ it == ProviderId.OPENAI }, googleConnected = false)
        assertEquals(v1.revision, v2.revision)
        assertEquals(v1, v2)

        val withKey = publisher.providersView({ true }, googleConnected = false)
        assertNotEquals(v1.revision, withKey.revision)
        val google = publisher.providersView({ it == ProviderId.OPENAI }, googleConnected = true)
        assertNotEquals(v1.revision, google.revision)
        assertTrue(v1.revision >= 0)
    }

    @Test
    fun `the providers view never carries a key, only whether one exists`() = runBlocking {
        data.settings.update { it.copy(selectedProvider = ProviderId.XAI) }
        val json = StageScopeJson.encodeToString(com.peaceantz.stagescope.shared.protocol.ProvidersView.serializer(), publisher.providersView({ true }, true))
        assertFalse(json.contains("sk-"))
        assertTrue(json.contains("\"hasKey\":true"))
    }

    @Test
    fun `inbox states map to the states the watch understands`() = runBlocking {
        val states = mapOf(
            InboxState.RECEIVED to RequestState.QUEUED, InboxState.RUNNING to RequestState.RUNNING, InboxState.COMPLETED to RequestState.COMPLETED,
            InboxState.FAILED to RequestState.FAILED, InboxState.INTERRUPTED to RequestState.FAILED, InboxState.CANCELLED to RequestState.CANCELLED,
        )
        for ((inbox, expected) in states) {
            val id = "r-$inbox"
            data.inbox.accept(Samples.request(id = id), "watch", now)
            if (inbox != InboxState.RECEIVED) data.inbox.finish(id, inbox, now)
            assertEquals(inbox.name, expected, publisher.requestStateFor(id))
        }
        assertEquals(RequestState.UNKNOWN, publisher.requestStateFor("nope"))
    }

    @Test
    fun `issues are published under the phone's own replica id`() = runBlocking {
        val replica = data.issues.replicaId
        data.issues.update { IssueLedger.create(it, "op-1", IssueOps.create("i-1", replica, now, "Mic 12 crackled")) }
        publisher.publishIssues()
        assertEquals(listOf(replica to "i-1"), link.issues.map { it.first to it.second.id })
    }
}

class PhoneIssueSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var data: PhoneData
    private val link = FakeWatchLink()
    private lateinit var sync: IssueSync
    private val now = 1_800_000_000_000L
    private val watch = "w-1"

    @Before
    fun setUp() {
        data = PhoneData(tmp.newFolder())
        sync = IssueSync(data, ThreadPublisher(data, link, clock = { now }))
    }

    private fun item(issue: IssueState, replica: String = watch) =
        "/stagescope/v1/issues/$replica/${issue.id}" to StageScopeJson.encodeToString(IssueState.serializer(), issue).toByteArray(Charsets.UTF_8)

    private fun watchIssue() = IssueOps.create("i-1", watch, now, "Mic 12 crackled during the opening number")

    @Test
    fun `an issue logged on the watch appears on the phone and the converged copy is published back`() = runBlocking {
        sync.mergeFromWatch(listOf(item(watchIssue())))
        val view = data.issues.state.value.issues["i-1"]!!.view()
        assertEquals("Mic 12 crackled during the opening number", view.originalObservation)
        assertEquals(1, link.issues.size)
        assertEquals(data.issues.replicaId, link.issues.single().first)
    }

    @Test
    fun `a second identical delivery changes nothing and publishes nothing`() = runBlocking {
        val payload = item(watchIssue())
        sync.mergeFromWatch(listOf(payload))
        val after = data.issues.state.value
        link.issues.clear()
        sync.mergeFromWatch(listOf(payload))
        assertEquals(after, data.issues.state.value)
        assertTrue(link.issues.isEmpty())
    }

    @Test
    fun `items from our own replica and malformed items are ignored`() = runBlocking {
        val own = item(watchIssue(), replica = data.issues.replicaId)
        sync.mergeFromWatch(listOf(own, "/stagescope/v1/issues/w-1/bad" to "{ not json".toByteArray(), "/stagescope/v1/issues/w-1/gone" to null))
        assertTrue(data.issues.state.value.issues.isEmpty())
        assertTrue(link.issues.isEmpty())
    }

    @Test
    fun `edits made on both devices while apart are both kept as a conflict, never overwritten`() = runBlocking {
        val base = watchIssue()
        sync.mergeFromWatch(listOf(item(base)))
        // The phone edits its copy...
        data.issues.update { IssueLedger.edit(it, "phone-edit", "i-1", IssuePatch(attemptedFix = Change("Replaced the pack"))) }
        // ...while the watch, offline, edited the same field from the same starting point.
        val watchEdited = IssueOps.edit(base, watch, IssuePatch(attemptedFix = Change("Swapped the cable")))
        sync.mergeFromWatch(listOf(item(watchEdited)))

        val merged = data.issues.state.value.issues["i-1"]!!
        assertTrue(merged.view().conflicts.isNotEmpty())
        val texts = merged.attemptedFix.siblings.map { it.value }.toSet()
        assertEquals(setOf("Replaced the pack", "Swapped the cable"), texts)
    }

    @Test
    fun `a delete from the watch is a tombstone that holds`() = runBlocking {
        val base = watchIssue()
        sync.mergeFromWatch(listOf(item(base)))
        sync.mergeFromWatch(listOf(item(IssueOps.delete(base, watch))))
        assertTrue(data.issues.state.value.issues["i-1"]!!.view().deleted)
        // A late redelivery of the old live copy can't resurrect it.
        sync.mergeFromWatch(listOf(item(base)))
        assertTrue(data.issues.state.value.issues["i-1"]!!.view().deleted)
    }
}
