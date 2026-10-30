package com.tortugapower.audiobookplayer.logic

import android.app.Application
import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WorkManager's bookkeeping on a full disk: reported, never a crash (StorageGuardedExecutor) */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StorageGuardedExecutorTest {

    private val reported = mutableListOf<Throwable>()
    private val executor = StorageGuardedExecutor({ it.run() }) { e -> reported += e; StorageMonitor.isStorageFailure(e) }

    /** A pending drain Android starts after the disk filled up: WorkManager's own write fails first */
    @Test fun aFullDisk_isReported_notThrown() {
        executor.execute { throw SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)") }

        assertEquals(1, reported.size)
    }

    @Test fun anythingElse_stillCrashes() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            executor.execute { throw IllegalStateException("a bug") }
        }

        assertEquals("a bug", thrown.message)
    }

    @Test fun itRunsTheTask() {
        var ran = 0
        executor.execute { ran++ }

        assertEquals(1, ran)
        assertEquals(0, reported.size)
    }
}
