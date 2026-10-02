package com.peaceantz.stagescope.audio

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

    @Test
    fun `Freeze pins and meters live in the session and are untouched - the coordinator only drives pause and resume`() = runBlocking {
        val lease = coordinator.acquire(AudioLeaseKind.LISTENING)
        coordinator.release(lease)
        // The only calls the coordinator ever makes on a session are these four.
        assertEquals(setOf("pause:ASSISTANT_LISTENING", "awaitReleased", "resume"), control.events.toSet())
    }
}
