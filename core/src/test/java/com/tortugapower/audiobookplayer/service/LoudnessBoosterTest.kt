package com.tortugapower.audiobookplayer.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LoudnessBoosterTest {

    private class FakeEffect(val session: Int) : LoudnessBooster.Effect {
        var gain = 0
        var enabled: Boolean? = null
        var released = false
        override fun setTargetGain(millibels: Int) { gain = millibels }
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun release() { released = true }
    }

    private val created = CopyOnWriteArrayList<FakeEffect>()
    private val inline = Executor { it.run() }
    private fun recording(session: Int) = FakeEffect(session).also(created::add)

    // --- ordering and state, on an inline executor ---------------------------------------------

    @Test fun attach_createsTheEffectWithTheBoostGain_andTheLatestSessionWins() {
        val booster = LoudnessBooster(::recording, inline)
        booster.attach(7)
        booster.attach(9)

        assertEquals(listOf(7, 9), created.map { it.session })
        assertTrue(created[0].released)
        assertFalse(created[1].released)
        assertEquals(LoudnessBooster.TARGET_GAIN_MB, created[1].gain)
    }

    @Test fun attach_toAnUnsetSession_releasesWithoutCreating() {
        val booster = LoudnessBooster(::recording, inline)
        booster.attach(7)
        booster.attach(C.AUDIO_SESSION_ID_UNSET)

        assertEquals(1, created.size)
        assertTrue(created.single().released)
    }

    @Test fun enabled_isAppliedToTheCurrentEffect_andRememberedAcrossReattach() {
        val booster = LoudnessBooster(::recording, inline)
        booster.setEnabled(true)          // before any effect exists
        booster.attach(7)
        assertEquals(true, created[0].enabled)

        booster.attach(8)                 // audio re-initialised: new session, same setting
        assertEquals(true, created[1].enabled)

        booster.setEnabled(false)
        assertEquals(false, created[1].enabled)
    }

    @Test fun factoryFailure_leavesNoEffect_andALaterAttachRecovers() {
        var fail = true
        val booster = LoudnessBooster({ if (fail) throw RuntimeException("Cannot initialize effect engine") else recording(it) }, inline)
        booster.attach(7)
        assertTrue(created.isEmpty())

        fail = false
        booster.attach(8)
        assertEquals(8, created.single().session)
    }

    @Test fun release_dropsTheEffect_andIgnoresLaterCommands() {
        val booster = LoudnessBooster(::recording, inline)
        booster.attach(7)
        booster.release()
        assertTrue(created[0].released)

        booster.attach(8)
        booster.setEnabled(true)
        assertEquals(1, created.size)
    }

    // --- the property that fixes the ANR: the caller never waits on audioserver -----------------

    @Test fun aStalledEffectCreation_doesNotBlockTheCaller() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val booster = LoudnessBooster({ started.countDown(); gate.await(5, TimeUnit.SECONDS); recording(it) })

        val t0 = System.nanoTime()
        booster.attach(7)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("attach() blocked for $elapsedMs ms", elapsedMs < 200)

        assertTrue(started.await(2, TimeUnit.SECONDS))   // creation is in flight on the booster thread
        gate.countDown()
        waitUntil { created.size == 1 }
        booster.release()
    }

    @Test fun commandsRunOnTheBoosterThread_notTheCaller() {
        val threads = CopyOnWriteArrayList<String>()
        val booster = LoudnessBooster({ threads += Thread.currentThread().name; recording(it) })
        booster.attach(7)
        waitUntil { threads.size == 1 }
        assertEquals(LoudnessBooster.THREAD_NAME, threads.single())
        booster.release()
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("condition not met within $timeoutMs ms", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }
}
