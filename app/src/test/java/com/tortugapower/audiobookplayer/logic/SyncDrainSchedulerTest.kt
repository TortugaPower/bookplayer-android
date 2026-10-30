package com.tortugapower.audiobookplayer.logic

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The drains the stored queue needs, as WorkManager unique works (SyncDrainScheduler) */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncDrainSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_000_000L
    private val later = now + 600_000

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setClock(Clock { now }).build(),
        )
    }

    private fun scheduler(needs: SyncDrainPolicy.Needs) = SyncDrainScheduler(
        needs = { needs }, workManager = { WorkManager.getInstance(context) }, clock = { now }, isStorageCritical = { false },
        isWorkManagerStarted = { true },
    )

    private fun drains(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test fun `work due now gets a drain on any network`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null)).schedule()

        val drain = drains(SyncDrainScheduler.ANY_NETWORK).single()
        assertEquals(WorkInfo.State.ENQUEUED, drain.state)
        assertEquals(NetworkType.CONNECTED, drain.constraints.requiredNetworkType)
        assertEquals(0L, drain.initialDelayMillis)
        assertTrue(drains(SyncDrainScheduler.UNMETERED).isEmpty())
    }

    @Test fun `uploads held for Wi-Fi get a drain that waits for an unmetered network`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = null, unmeteredOnly = now)).schedule()

        assertEquals(NetworkType.UNMETERED, drains(SyncDrainScheduler.UNMETERED).single().constraints.requiredNetworkType)
        assertTrue(drains(SyncDrainScheduler.ANY_NETWORK).isEmpty())
    }

    /** A retry's time: the drain wakes the lane then, since its own wait stops while the device sleeps */
    @Test fun `a wait gets a drain at its end`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = later, unmeteredOnly = null)).schedule()

        assertEquals(600_000L, drains(SyncDrainScheduler.ANY_NETWORK).single().initialDelayMillis)
    }

    /** It runs no later than needed: an early run finds nothing due, and schedules the next */
    @Test fun `a pending drain due earlier is kept`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null)).schedule()
        val first = drains(SyncDrainScheduler.ANY_NETWORK).single().id

        scheduler(SyncDrainPolicy.Needs(anyNetwork = later, unmeteredOnly = null)).schedule()

        assertEquals(first, drains(SyncDrainScheduler.ANY_NETWORK).single().id)
    }

    /** WorkManager stamps the enqueue a moment after the delay was worked out: that's still the same drain */
    @Test fun `a pending drain a moment later than needed is kept`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = later, unmeteredOnly = null)).schedule()
        val first = drains(SyncDrainScheduler.ANY_NETWORK).single().id

        scheduler(SyncDrainPolicy.Needs(anyNetwork = later - 50, unmeteredOnly = null)).schedule()

        assertEquals(first, drains(SyncDrainScheduler.ANY_NETWORK).single().id)
    }

    @Test fun `a pending drain due too late is replaced`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = later, unmeteredOnly = null)).schedule()
        val first = drains(SyncDrainScheduler.ANY_NETWORK).single().id

        scheduler(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null)).schedule()

        val drain = drains(SyncDrainScheduler.ANY_NETWORK).single()
        assertNotEquals(first, drain.id)
        assertEquals(0L, drain.initialDelayMillis)
    }

    @Test fun `a pending drain nothing needs any more is cancelled`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = later, unmeteredOnly = later)).schedule()

        scheduler(SyncDrainPolicy.Needs.NONE).schedule()

        assertTrue(drains(SyncDrainScheduler.ANY_NETWORK).isEmpty())
        assertTrue(drains(SyncDrainScheduler.UNMETERED).isEmpty())
    }

    /**
     * A process a drain just started runs this before its engine has looked at the queue: nothing is known yet, and
     * answering "nothing needed" would cancel the drain being started
     */
    @Test fun `before the engine has looked at the queue, nothing changes`() = runBlocking {
        scheduler(SyncDrainPolicy.Needs(anyNetwork = now, unmeteredOnly = null)).schedule()
        val pending = drains(SyncDrainScheduler.ANY_NETWORK).single().id

        SyncDrainScheduler(
            needs = { null }, workManager = { WorkManager.getInstance(context) }, clock = { now },
            isStorageCritical = { false }, isWorkManagerStarted = { true },
        ).schedule()

        assertEquals(pending, drains(SyncDrainScheduler.ANY_NETWORK).single().id)
    }

    /** WorkManager's own database would be the first write to fail */
    @Test fun `a critically full disk never touches WorkManager`() = runBlocking {
        var touched = 0
        SyncDrainScheduler(
            needs = { SyncDrainPolicy.Needs(now, null) },
            workManager = { touched++; WorkManager.getInstance(context) },
            isStorageCritical = { true },
        ).schedule()

        assertEquals(0, touched)
    }

    /** A free account never needs one: WorkManager isn't started (its database created) just to look */
    @Test fun `nothing needed never starts WorkManager`() = runBlocking {
        var touched = 0
        SyncDrainScheduler(
            needs = { SyncDrainPolicy.Needs.NONE },
            workManager = { touched++; WorkManager.getInstance(context) },
            isStorageCritical = { false },
            isWorkManagerStarted = { false },
        ).schedule()

        assertEquals(0, touched)
    }

    /** Best effort: the next app open runs the queue anyway */
    @Test fun `a failure to schedule is reported, never thrown`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        SyncDrainScheduler(
            needs = { SyncDrainPolicy.Needs(now, null) },
            workManager = { throw IllegalStateException("WorkManager is not initialized") },
            isStorageCritical = { false },
            onFailed = { failures += it },
        ).schedule()

        assertEquals(1, failures.size)
    }
}
