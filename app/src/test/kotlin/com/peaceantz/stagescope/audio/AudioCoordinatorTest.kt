package com.peaceantz.stagescope.audio

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioCoordinatorTest {

    private class FakeControl(var running: Boolean = true) : MeasurementControl {
        var paused = false
        val events = mutableListOf<String>()

        override fun pauseForAssistant(reason: PauseReason): Boolean {
            if (!running) return false
            running = false
            paused = true
            events += "pause:${reason.name}"
            return true
        }

        override val isPausedForAssistant: Boolean get() = paused
        override suspend fun awaitMicReleased() { events += "awaitReleased" }
        override fun resumeAfterAssistant() { paused = false; running = true; events += "resume" }
        override fun endPausedSession() { paused = false; running = false; events += "end" }

        /** The person pressed Stop, or the keep-awake countdown ended, while paused. */
        fun sessionEndedByUser() { paused = false; running = false }
    }

    private var now = 1_000L
    private var foreground = true
    private var micPermission = true
    private lateinit var coordinator: AudioCoordinator
    private lateinit var control: FakeControl

    @Before
    fun setUp() {
        coordinator = AudioCoordinator({ now }, { foreground }, { micPermission })
        control = FakeControl()
        coordinator.attach(control)
    }

    @Test
    fun `listening pauses a running measurement, waits for the microphone, and resumes afterwards`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        assertEquals(listOf("pause:ASSISTANT_LISTENING", "awaitReleased"), control.events)
        assertTrue(control.paused)
        assertEquals(AudioMode.LISTENING, coordinator.mode.value)

        coordinator.release(lease)
        assertEquals(listOf("pause:ASSISTANT_LISTENING", "awaitReleased", "resume"), control.events)
        assertFalse(control.paused)
        assertEquals(AudioMode.IDLE, coordinator.mode.value)
    }

    @Test
    fun `a stopped session is never started by a voice interaction`() = runBlocking {
        control.running = false
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(lease)
        assertEquals("nothing was paused, so nothing resumes", emptyList<String>(), control.events)
        assertFalse(control.running)
    }

    @Test
    fun `overlapping interactions keep measurement paused until the last one ends`() = runBlocking {
        val listening = coordinator.acquire(AudioLeaseKind.LISTENING)
        val phone = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        assertEquals("only one pause for two leases", 1, control.events.count { it.startsWith("pause") })

        coordinator.release(listening)
        assertTrue("the phone is still speaking", control.paused)
        assertEquals(AudioMode.PHONE_PLAYBACK, coordinator.mode.value)

        coordinator.release(phone)
        assertFalse(control.paused)
        assertEquals(1, control.events.count { it == "resume" })
    }

    @Test
    fun `pressing Stop while paused cancels the resume`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        control.sessionEndedByUser()
        coordinator.release(lease)
        assertFalse("a session the person stopped is not restarted", control.events.contains("resume"))
        assertFalse(control.running)
    }

    @Test
    fun `the keep-awake countdown ending during the pause also cancels the resume`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.SPEAKING)
        control.sessionEndedByUser() // the countdown calls stop() on the session
        coordinator.release(lease)
        assertFalse(control.events.contains("resume"))
    }

    @Test
    fun `a backgrounded app is not resumed and the paused session is ended cleanly`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        foreground = false
        coordinator.release(lease)
        assertFalse(control.events.contains("resume"))
        assertTrue(control.events.contains("end"))
        assertFalse("never left half-alive", control.paused)
    }

    @Test
    fun `a revoked microphone permission is not resumed either`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        micPermission = false
        coordinator.release(lease)
        assertFalse(control.events.contains("resume"))
        assertTrue(control.events.contains("end"))
    }

    @Test
    fun `a lost stopped-speaking message cannot leave measurement paused forever`() = runBlocking {
        coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        assertTrue(control.paused)
        now += 29_000
        coordinator.tick()
        assertTrue("not yet", control.paused)
        now += 2_000
        coordinator.tick()
        assertFalse(control.paused)
        assertTrue(control.events.contains("resume"))
        assertEquals(AudioMode.IDLE, coordinator.mode.value)
    }

    @Test
    fun `a heartbeat from the phone keeps the pause alive`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        repeat(4) {
            now += 20_000
            coordinator.refresh(lease, 30_000)
            coordinator.tick()
        }
        assertTrue("80 s of speech, still paused", control.paused)
        coordinator.release(lease)
        assertFalse(control.paused)
    }

    @Test
    fun `refreshing a lease with no time limit or an unknown id changes nothing`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.refresh(lease, 1)
        coordinator.refresh(999, 1)
        now += 10_000_000
        coordinator.tick()
        assertTrue("a lease without a ttl never expires on its own", control.paused)
    }

    @Test
    fun `starting measurement while the phone is speaking pauses it at once and resumes after`() = runBlocking {
        control.running = false
        val lease = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        assertTrue(control.events.isEmpty())

        control.running = true // the person pressed Start while the phone speaks
        coordinator.onMeasurementStarted()
        assertTrue(control.paused)
        assertEquals("pause:PHONE_PLAYBACK", control.events.last())

        coordinator.release(lease)
        assertFalse(control.paused)
        assertTrue(control.events.contains("resume"))
    }

    @Test
    fun `starting measurement when nothing is speaking is left alone`() = runBlocking {
        coordinator.onMeasurementStarted()
        assertTrue(control.events.isEmpty())
    }

    @Test
    fun `a session that went away is forgotten and never resumed`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.detach(control)
        control.events.clear()
        coordinator.release(lease)
        assertTrue(control.events.isEmpty())
    }

    @Test
    fun `a lease with no measurement attached is harmless`() = runBlocking {
        val bare = AudioCoordinator({ now }, { foreground }, { micPermission })
        val lease = bare.acquire(AudioLeaseKind.SPEAKING, ttlMs = 1_000)
        assertEquals(AudioMode.SPEAKING, bare.mode.value)
        bare.release(lease)
        assertEquals(AudioMode.IDLE, bare.mode.value)
        bare.release(lease) // releasing twice is fine
    }

    @Test
    fun `the mode label prefers listening over speaking over phone playback`() = runBlocking {
        val phone = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, 30_000)
        assertEquals(AudioMode.PHONE_PLAYBACK, coordinator.mode.value)
        val speaking = coordinator.acquire(AudioLeaseKind.SPEAKING, 30_000)
        assertEquals(AudioMode.SPEAKING, coordinator.mode.value)
        val listening = coordinator.acquire(AudioLeaseKind.LISTENING, 30_000)
        assertEquals(AudioMode.LISTENING, coordinator.mode.value)
        coordinator.release(listening)
        assertEquals(AudioMode.SPEAKING, coordinator.mode.value)
        coordinator.release(speaking)
        assertEquals(AudioMode.PHONE_PLAYBACK, coordinator.mode.value)
        coordinator.release(phone)
        assertEquals(AudioMode.IDLE, coordinator.mode.value)
    }

    // ------------------------------------------------------------- a screen another process runs (dictation)

    @Test
    fun `a lease given up with allowResume false ends the paused session - the microphone may still be in use`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(lease, allowResume = false)
        assertFalse("never reopened", control.events.contains("resume"))
        assertTrue(control.events.contains("end"))
        assertFalse(control.paused)
        assertEquals(AudioMode.IDLE, coordinator.mode.value)
    }

    @Test
    fun `the veto waits for the last lease, and then ends the session once`() = runBlocking {
        val listening = coordinator.acquire(AudioLeaseKind.LISTENING)
        val phone = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        coordinator.release(listening, allowResume = false)
        assertTrue("the phone is still speaking, so nothing is decided yet", control.paused)
        coordinator.release(phone)
        assertFalse(control.events.contains("resume"))
        assertEquals(1, control.events.count { it == "end" })
    }

    @Test
    fun `a veto with nothing paused does not poison a later, ordinary cycle`() = runBlocking {
        control.running = false
        val first = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(first, allowResume = false) // nothing was paused, so there is nothing to veto
        control.running = true
        val second = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(second)
        assertTrue("an ordinary release still resumes", control.events.contains("resume"))
    }

    @Test
    fun `a lease with no time limit never resumes measurement on its own, however long the screen stays open`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING, ttlMs = null)
        repeat(100) { now += 60_000; coordinator.tick() }
        assertTrue(control.paused)
        assertTrue(coordinator.isHeld(AudioLeaseKind.LISTENING))
        coordinator.release(lease)
        assertFalse(coordinator.isHeld(AudioLeaseKind.LISTENING))
    }

    @Test
    fun `isHeld reports a kind only while it is held`() = runBlocking {
        assertFalse(coordinator.isHeld(AudioLeaseKind.PHONE_PLAYBACK))
        val lease = coordinator.acquire(AudioLeaseKind.PHONE_PLAYBACK, ttlMs = 30_000)
        assertTrue(coordinator.isHeld(AudioLeaseKind.PHONE_PLAYBACK))
        assertFalse(coordinator.isHeld(AudioLeaseKind.LISTENING))
        now += 31_000
        assertFalse("an expired lease is not held", coordinator.isHeld(AudioLeaseKind.PHONE_PLAYBACK))
        coordinator.release(lease)
    }

    @Test
    fun `a caller cancelled while the microphone is being freed gives its lease back - a lease with no timer cannot be stranded`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fake = FakeMeasurementControl().also { it.micGate = gate }
        val c = AudioCoordinator({ now }, { foreground }, { micPermission }).also { it.attach(fake) }
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { c.acquire(AudioLeaseKind.LISTENING) }
        assertTrue("paused and waiting for the microphone", fake.paused && c.isHeld(AudioLeaseKind.LISTENING))
        job.cancel()
        job.join()
        assertFalse("the lease did not outlive its caller", c.isHeld(AudioLeaseKind.LISTENING))
        assertFalse("and measurement was not left paused", fake.paused)
        assertEquals(1, fake.count("resume"))
    }

    @Test
    fun `Freeze pins and meters live in the session and are untouched - the coordinator only drives pause and resume`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(lease)
        // The only calls the coordinator ever makes on a session are these four.
        assertEquals(setOf("pause:ASSISTANT_LISTENING", "awaitReleased", "resume"), control.events.toSet())
    }
}
