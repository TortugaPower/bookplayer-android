package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StorageMonitorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var freeBytes = 10L * 1024 * 1024 * 1024
    private var now = 1_000L

    @Before fun setUp() {
        StorageMonitor.resetForTest()
        StorageMonitor.availableBytesProvider = { freeBytes }
        StorageMonitor.clock = { now }
    }

    @After fun tearDown() {
        StorageMonitor.resetForTest()
        StorageMonitor.availableBytesProvider = { ctx -> android.os.StatFs(ctx.filesDir.path).availableBytes }
        StorageMonitor.clock = System::currentTimeMillis
    }

    // --- classification: the shapes seen in Sentry ------------------------------------------------

    @Test fun recognizesTheFullDiskExceptionFamily() {
        assertTrue(StorageMonitor.isStorageFailure(SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")))
        assertTrue(StorageMonitor.isStorageFailure(SQLiteDiskIOException("disk I/O error (code 4874 SQLITE_IOERR_SHMSIZE): , while compiling: PRAGMA journal_mode")))
        assertTrue(StorageMonitor.isStorageFailure(SQLiteDiskIOException("disk I/O error - SQLITE_IOERR_SHMSIZE (Sqlite code 4874), (OS error - 28:No space left on device)")))
        assertTrue(StorageMonitor.isStorageFailure(FileNotFoundException("/data/user/0/app/files/datastore/x.tmp: open failed: ENOSPC (No space left on device)")))
        assertTrue(StorageMonitor.isStorageFailure(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(StorageMonitor.isStorageFailure(ErrnoException("write", OsConstants.ENOSPC)))
        // wrapped anywhere in the chain
        assertTrue(StorageMonitor.isStorageFailure(RuntimeException("import failed", IOException("write failed: ENOSPC (No space left on device)"))))
    }

    @Test fun leavesUnrelatedErrorsAlone() {
        assertFalse(StorageMonitor.isStorageFailure(SQLiteDiskIOException("disk I/O error (code 1802 SQLITE_IOERR_FSTAT)")))
        assertFalse(StorageMonitor.isStorageFailure(IOException("Connection reset")))
        assertFalse(StorageMonitor.isStorageFailure(IllegalStateException("FOREIGN KEY constraint failed")))
        assertFalse(StorageMonitor.isStorageFailure(null))
        assertFalse(StorageMonitor.reportFailure(context, IOException("Connection reset")))
        assertFalse(StorageMonitor.state.value.isCritical)
    }

    // --- measured state ---------------------------------------------------------------------------

    @Test fun refresh_isCriticalBelowTheThreshold_andRecoversAboveTwiceIt() {
        freeBytes = StorageMonitor.CRITICAL_BYTES - 1
        assertTrue(StorageMonitor.refresh(context).isCritical)

        freeBytes = StorageMonitor.CRITICAL_BYTES
        assertFalse(StorageMonitor.refresh(context).isCritical)
    }

    @Test fun hasRoomFor_keepsTheTransferReserve() {
        freeBytes = 500L * 1024 * 1024
        assertTrue(StorageMonitor.hasRoomFor(context, 400L * 1024 * 1024))
        assertFalse(StorageMonitor.hasRoomFor(context, 450L * 1024 * 1024))   // would leave less than the reserve
    }

    // --- observed state ---------------------------------------------------------------------------

    @Test fun reportedFailure_isStickyUntilSpaceIsBackAboveTheRecoveryLine() {
        freeBytes = 10L * 1024 * 1024 * 1024
        now = 42_000L
        assertTrue(StorageMonitor.reportFailure(context, SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")))
        assertTrue(StorageMonitor.state.value.isCritical)
        assertEquals(42_000L, StorageMonitor.state.value.lastFailureAt)

        // A measurement just above critical is not enough to clear a reported failure (hysteresis)...
        freeBytes = StorageMonitor.CRITICAL_BYTES + 1
        assertTrue(StorageMonitor.refresh(context).isCritical)
        // ...twice the threshold is.
        freeBytes = 2 * StorageMonitor.CRITICAL_BYTES
        assertFalse(StorageMonitor.refresh(context).isCritical)
        assertNotNull(StorageMonitor.state.value.lastFailureAt) // history is kept for the UI
    }

    // --- the coroutine handler --------------------------------------------------------------------

    @Test fun exceptionHandler_swallowsFullDiskFailures_andForwardsEverythingElse() {
        val forwarded = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> forwarded += e }
        try {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + StorageMonitor.exceptionHandler { context })

            scope.launch { throw SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)") }
            assertTrue(StorageMonitor.state.value.isCritical)
            assertTrue(forwarded.isEmpty())

            scope.launch { throw IllegalStateException("a real bug") }
            assertEquals(1, forwarded.size)
            assertEquals("a real bug", forwarded.single().message)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun initialState_isNotCritical_untilMeasuredOrReported() {
        assertFalse(StorageMonitor.state.value.isCritical)
        assertNull(StorageMonitor.state.value.lastFailureAt)
    }
}
