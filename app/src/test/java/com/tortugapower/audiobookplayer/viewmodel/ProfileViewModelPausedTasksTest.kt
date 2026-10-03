package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.SyncEngineWaker
import com.tortugapower.audiobookplayer.logic.SyncFailurePolicy
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.UploadHandBack
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The parked-row actions: Retry resumes and wakes the engine, Dismiss is only for a book over the limit */
@RunWith(RobolectricTestRunner::class)
class ProfileViewModelPausedTasksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var viewModel: ProfileViewModel
    private var woken = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        viewModel = ProfileViewModel(
            RoomAccountRepository(db.accountDao()), RoomSyncTaskRepository(db.syncTaskDao()),
            db.statisticsDao(), db.libraryDao(), endSyncSession = {},
        )
        SyncEngineWaker.onWorkEnqueued = { woken++ }
    }

    @After fun tearDown() {
        SyncEngineWaker.onWorkEnqueued = null
        db.close()
        Dispatchers.resetMain()
    }

    private fun parked(id: String, code: String, jobType: String = SyncTaskFactory.JOB_MOVE) = SyncTaskEntity(
        id = id, taskID = "book", queueKey = SyncTaskFactory.QUEUE_SYNC, jobType = jobType, position = 0,
        payload = "{}", status = SyncTaskStatus.FAILED, pauseScope = "LANE", errorCode = code,
        errorMessage = "message", httpStatus = 404, pausedAt = 1L, sentryEventId = "evt",
    )

    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(20) }

    @Test fun retry_resumesTheTask_andWakesTheEngine() = runBlocking {
        db.syncTaskDao().insertTask(parked("t", "item_not_found"))

        viewModel.retryPausedTask(requireNotNull(db.syncTaskDao().getTaskById("t")))

        until { db.syncTaskDao().getTaskById("t")?.status == SyncTaskStatus.PENDING }
        val resumed = requireNotNull(db.syncTaskDao().getTaskById("t"))
        assertNull(resumed.pauseScope)
        assertEquals("the report stays recorded", "evt", resumed.sentryEventId)
        until { woken == 1 }
    }

    @Test fun dismiss_removesATooLargeBook_butNothingElse() = runBlocking {
        db.syncTaskDao().insertTask(parked("big", SyncFailurePolicy.FILE_TOO_LARGE, SyncTaskFactory.JOB_UPLOAD_FILE))
        db.syncTaskDao().insertTask(parked("other", "item_not_found"))

        viewModel.dismissPausedTask(requireNotNull(db.syncTaskDao().getTaskById("other")))
        viewModel.dismissPausedTask(requireNotNull(db.syncTaskDao().getTaskById("big")))

        until { db.syncTaskDao().getTaskById("big") == null }
        assertNotNull(db.syncTaskDao().getTaskById("other"))
    }

    @Test fun theReport_readsTheQueueAndTheLibrary() = runBlocking {
        val task = parked("t", "item_not_found")
        db.syncTaskDao().insertTask(task)

        val report = viewModel.pauseReport(task)

        assertEquals(listOf("t"), report.queuedTasks.map { it.id })
        assertTrue(report.library.isEmpty())
    }

    /** An upload's Retry asks for the book again: it may be registered again this session */
    @Test fun retryingAnUpload_allowsAnotherRegistration() = runBlocking {
        val book = "book-for-retry"
        val upload = parked("u", "item_not_found", SyncTaskFactory.JOB_UPLOAD_FILE).copy(taskID = book, payload = """{"uuid":"$book"}""")
        db.syncTaskDao().insertTask(upload)
        UploadHandBack.claim(book)

        viewModel.retryPausedTask(upload)

        assertTrue("released", UploadHandBack.claim(book))
        UploadHandBack.release(book)
    }
}
