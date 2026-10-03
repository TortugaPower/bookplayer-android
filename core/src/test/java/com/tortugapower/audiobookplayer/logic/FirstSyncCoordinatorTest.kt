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
import java.io.IOException

/** iOS `syncLibraryContents`: no listing deletes until this device's items are registered */
@RunWith(RobolectricTestRunner::class)
class FirstSyncCoordinatorTest {

    private class FakeStore(var done: Boolean = false) : SyncStateStore {
        var cleared = 0
        var lastRun = 0L
        var pending = false
        var lastKnownPro: Boolean? = null
        override suspend fun hasRunFirstSync() = done
        override suspend fun setHasRunFirstSync(done: Boolean) { this.done = done }
        override suspend fun passLastRun() = lastRun
        override suspend fun setPassLastRun(at: Long) { lastRun = at }
        override suspend fun isPassPending() = pending
        override suspend fun setPassPending(pending: Boolean) { this.pending = pending }
        override suspend fun lastKnownProAccess() = lastKnownPro
        override suspend fun setLastKnownProAccess(pro: Boolean) { lastKnownPro = pro }
        override suspend fun clear() { done = false; lastRun = 0; pending = false; lastKnownPro = null; cleared++ }
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
    private var canUpload = true
    private var now = 10 * DAY

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
        runBlocking { db.libraryDao().insertItem(LibraryItemEntity(uuid = "local-1", title = "Book", relativePath = "Book.m4b", type = ItemType.BOOK)) }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun coordinator(store: SyncStateStore = this.store) = FirstSyncCoordinator(
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
            canUploadFiles = { canUpload },
        ),
        syncTasks = repository,
        isSyncActive = { active },
        fetchRoot = { rootAnswer },
        applyRootListing = {
            if (applyThrows) error("listing failed")
            applied += it
        },
        scope = scope,
        clock = { now },
    )

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }

    /** Waits for a scheduled pass, which runs in the background, to have left its mark */
    private suspend fun awaitPasses(count: Int) = withTimeout(5_000) {
        while (statusCalls < count) kotlinx.coroutines.delay(10)
        kotlinx.coroutines.delay(100)
    }

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

    /** A lapse ends the run like a sign-out, and the return is a first sync; the pass's schedule stays */
    @Test fun aLapse_endsTheRun_andResetsOnlyTheFirstSync() = runBlocking {
        store.lastRun = 5L
        store.pending = true
        store.lastKnownPro = true
        statusGate = CompletableDeferred()
        val coordinator = coordinator()

        val run = async { coordinator.run() }
        withTimeout(5_000) { while (statusCalls == 0) kotlinx.coroutines.delay(10) }
        coordinator.endSession()
        statusGate!!.complete(Unit)

        assertEquals(FirstSyncResult.SessionEnded, run.await())
        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
        assertEquals(0, store.cleared)
        assertEquals(5L, store.lastRun)
        assertTrue(store.pending)
        assertEquals(true, store.lastKnownPro)

        store.done = true
        coordinator.endSession()
        assertFalse(store.done)
    }

    /** A store that can't be written (a full disk) doesn't stop a sign-out or a lapse: the session still ends */
    @Test fun aFailingStoreWrite_stillEndsTheSession() = runBlocking {
        val failing = object : SyncStateStore by store {
            override suspend fun setHasRunFirstSync(done: Boolean) {
                if (!done) throw IOException("disk full")
                store.setHasRunFirstSync(true)
            }
            override suspend fun clear() {
                throw IOException("disk full")
            }
        }
        statusGate = CompletableDeferred()
        val coordinator = coordinator(failing)

        val run = async { coordinator.run() }
        withTimeout(5_000) { while (statusCalls == 0) kotlinx.coroutines.delay(10) }
        coordinator.endSession()
        coordinator.signOut()
        statusGate!!.complete(Unit)

        assertEquals(FirstSyncResult.SessionEnded, run.await())
        assertTrue(db.syncTaskDao().getAllTasksSync().isEmpty())
    }

    /** A refresh awaits the first sync: a read that fails before the pass (a broken database) is a Failed, not a throw */
    @Test fun aFailingRead_beforeThePass_isAFailure() = runBlocking {
        val broken = object : SyncStateStore by store {
            override suspend fun hasRunFirstSync(): Boolean = throw IllegalStateException("database is corrupt")
        }

        assertEquals(FirstSyncResult.Failed, coordinator(broken).run())
        assertEquals(0, statusCalls)
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

    // ---- The recurring pass (iOS scheduleMissingItemsIfNeeded) ----

    @Test fun theFirstSyncsOwnPass_countsAsARun_andSettlesAPassOwed() = runBlocking {
        store.pending = true

        assertEquals(FirstSyncResult.Done, coordinator().run())

        assertEquals(now, store.lastRun)
        assertFalse(store.pending)
    }

    @Test fun aWeekAfterTheLastRun_thePassRunsAgain_andNotBefore() = runBlocking {
        store.done = true
        store.lastRun = now - 6 * DAY
        val coordinator = coordinator()

        coordinator.schedulePassIfNeeded()
        kotlinx.coroutines.delay(300)
        assertEquals(0, statusCalls)

        store.lastRun = now - 7 * DAY
        coordinator.schedulePassIfNeeded()
        awaitPasses(1)
        assertEquals(now, store.lastRun)
        assertEquals(1, statusCalls)
    }

    /** Not before the first sync, nor while the sync lane holds anything */
    @Test fun aDuePass_waitsForTheFirstSyncAndAnEmptyLane() = runBlocking {
        store.lastRun = 0
        val coordinator = coordinator()
        coordinator.schedulePassIfNeeded()
        kotlinx.coroutines.delay(300)

        store.done = true
        repository.saveTask(task("queued"))
        coordinator.schedulePassIfNeeded()
        kotlinx.coroutines.delay(300)

        assertEquals(0, statusCalls)
        assertEquals(0L, store.lastRun)
    }

    /** Gaining PRO owes a pass; one that couldn't queue files (the tier hadn't reached the queue) leaves it owed */
    @Test fun aPassOwedSinceGainingPro_isSettledOnlyByOneThatCouldQueueFiles() = runBlocking {
        store.done = true
        store.lastRun = now
        // Nothing to register, so each pass leaves the sync lane empty for the next
        statusAnswer = Response.success(ItemsStatusResponse(emptyList(), emptyList()))
        val coordinator = coordinator()
        coordinator.noteProAccess(false)
        coordinator.noteProAccess(true)
        assertTrue(store.pending)

        canUpload = false
        coordinator.schedulePassIfNeeded()
        awaitPasses(1)
        assertTrue(store.pending)

        canUpload = true
        coordinator.schedulePassIfNeeded()
        awaitPasses(2)
        assertFalse(store.pending)
    }

    @Test fun noteProAccess_aFirstReadingIsNoChange_andLosingProCancelsAnOwedPass() = runBlocking {
        val coordinator = coordinator()

        coordinator.noteProAccess(true)
        assertFalse(store.pending)
        assertEquals(true, store.lastKnownPro)

        coordinator.noteProAccess(false)
        assertFalse(store.pending)
        coordinator.noteProAccess(true)
        assertTrue(store.pending)
        coordinator.noteProAccess(false)
        assertFalse(store.pending)
    }

    /** The sync lane emptied: a first sync waiting for it runs then */
    @Test fun aDrainedLane_startsAFirstSyncThatWasWaiting() = runBlocking {
        val coordinator = coordinator()

        coordinator.onSyncLaneDrained()

        withTimeout(5_000) { while (!store.done) kotlinx.coroutines.delay(10) }
        assertEquals(1, statusCalls)
    }
}
