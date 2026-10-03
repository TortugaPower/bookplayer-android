package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.ItemsStatusResponse
import com.tortugapower.audiobookplayer.model.MatchUuidsResponse
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.File

/** iOS `syncLibraryContents`: no listing deletes until this device's items are registered */
@RunWith(RobolectricTestRunner::class)
class FirstSyncCoordinatorTest {

    private class FakeStore(var done: Boolean = false) : SyncStateStore {
        var cleared = 0
        override suspend fun hasRunFirstSync() = done
        override suspend fun setHasRunFirstSync(done: Boolean) { this.done = done }
        override suspend fun clear() { done = false; cleared++ }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = FakeStore()
    private var active = true
    private var statusCalls = 0
    private var statusGate: CompletableDeferred<Unit>? = null
    private var statusAnswer: Response<ItemsStatusResponse> = Response.success(ItemsStatusResponse(listOf("local-1"), emptyList()))
    private var rootAnswer: Response<ContentsResponse> = Response.success(ContentsResponse(emptyList(), null))
    private val applied = mutableListOf<ContentsResponse>()
    private var applyThrows = false

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
        runBlocking { db.libraryDao().insertItem(LibraryItemEntity(uuid = "local-1", title = "Book", relativePath = "Book.m4b", type = ItemType.BOOK)) }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun coordinator() = FirstSyncCoordinator(
        store = store,
        pass = MissingItemsPass(
            itemsStatus = {
                statusCalls++
                statusGate?.await()
                statusAnswer
            },
            matchUuids = { Response.success(MatchUuidsResponse(emptyList(), emptyList())) },
            libraryDao = { db.libraryDao() },
            repository = repository,
            bookFile = { File(context.filesDir, it) },
            canUploadFiles = { true },
        ),
        syncTasks = repository,
        isSyncActive = { active },
        fetchRoot = { rootAnswer },
        applyRootListing = {
            if (applyThrows) error("listing failed")
            applied += it
        },
        scope = scope,
    )

    private fun task(id: String, queue: String = SyncTaskFactory.QUEUE_SYNC) =
        SyncTaskEntity(id = id, taskID = id, queueKey = queue, jobType = "update", position = 0, payload = "{}")

    @Test fun registersWhatTheServerLacks_listsTheRoot_thenMarksItDone() = runBlocking {
        assertEquals(FirstSyncResult.Done, coordinator().run())

        assertTrue(store.done)
        assertEquals(1, applied.size)
        assertEquals(listOf(SyncTaskFactory.JOB_UPLOAD_METADATA), db.syncTaskDao().getAllTasksSync().map { it.jobType })
    }

    @Test fun alreadyDone_orNotSyncing_asksNothing() = runBlocking {
        store.done = true
        assertEquals(FirstSyncResult.AlreadyDone, coordinator().run())
        store.done = false
        active = false
        assertEquals(FirstSyncResult.Inactive, coordinator().run())
        assertEquals(0, statusCalls)
    }

    /** What's queued goes first, parked tasks and an account pause in any lane included */
    @Test fun waitsForTheSyncLane() = runBlocking {
        val coordinator = coordinator()
        repository.saveTask(task("queued"))
        assertEquals(FirstSyncResult.WaitingForQueue, coordinator.run())

        db.syncTaskDao().parkTask("queued", "TASK", "invalid_request", "Invalid", 422, 5L)
        assertEquals(FirstSyncResult.WaitingForQueue, coordinator.run())

        db.syncTaskDao().deleteAllTasks()
        repository.saveTask(task("upload", SyncTaskFactory.QUEUE_UPLOAD))
        db.syncTaskDao().parkTask("upload", "ACCOUNT", "not_subscribed", "Not subscribed", 400, 5L)
        assertEquals(FirstSyncResult.WaitingForQueue, coordinator.run())

        assertEquals(0, statusCalls)
        assertFalse(store.done)
    }

    @Test fun twoRequests_runOneFirstSync() = runBlocking {
        statusGate = CompletableDeferred()
        val coordinator = coordinator()

        val first = async { coordinator.run() }
        val second = async { coordinator.run() }
        withTimeout(5_000) { while (statusCalls == 0) kotlinx.coroutines.delay(10) }
        statusGate!!.complete(Unit)

        assertEquals(FirstSyncResult.Done, first.await())
        assertEquals(FirstSyncResult.Done, second.await())
        assertEquals(1, statusCalls)
    }

    /** A sign-out mid-run: nothing queued into the next account's library, nothing marked */
    @Test fun aSignOutMidRun_queuesAndMarksNothing() = runBlocking {
        statusGate = CompletableDeferred()
        val coordinator = coordinator()

        val run = async { coordinator.run() }
        withTimeout(5_000) { while (statusCalls == 0) kotlinx.coroutines.delay(10) }
        coordinator.signOut()
        statusGate!!.complete(Unit)

        assertEquals(FirstSyncResult.SessionEnded, run.await())
        assertFalse(store.done)
        assertEquals(1, store.cleared)
        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
    }

    @Test fun aFailedPassOrRootListing_leavesItNotDone() = runBlocking {
        statusAnswer = Response.error(500, """{"message":"Internal error"}""".toResponseBody())
        assertEquals(FirstSyncResult.Failed, coordinator().run())
        assertFalse(store.done)

        statusAnswer = Response.success(ItemsStatusResponse(emptyList(), emptyList()))
        rootAnswer = Response.error(500, """{"message":"Internal error"}""".toResponseBody())
        assertEquals(FirstSyncResult.Failed, coordinator().run())
        assertFalse(store.done)
        assertTrue(applied.isEmpty())
    }

    /** The session ends without a cancel (a lapse) after the pass: the first sync isn't marked done */
    @Test fun aSessionEndedBeforeTheMark_leavesItNotDone() = runBlocking {
        val coordinator = FirstSyncCoordinator(
            store = store,
            pass = MissingItemsPass(
                itemsStatus = { Response.success(ItemsStatusResponse(emptyList(), emptyList())) },
                matchUuids = { error("not asked") },
                libraryDao = { db.libraryDao() }, repository = repository,
                bookFile = { File(context.filesDir, it) }, canUploadFiles = { true },
            ),
            syncTasks = repository,
            isSyncActive = { active },
            fetchRoot = {
                active = false
                rootAnswer
            },
            applyRootListing = { applied += it },
            scope = scope,
        )

        assertEquals(FirstSyncResult.SessionEnded, coordinator.run())
        assertFalse(store.done)
        assertTrue(applied.isEmpty())
    }

    /** Marked before the listing is applied: a listing that fails afterwards deleted nothing anyway */
    @Test fun aListingThatFailsToApply_stillMarksItDone() = runBlocking {
        applyThrows = true

        assertEquals(FirstSyncResult.Done, coordinator().run())
        assertTrue(store.done)
    }
}
