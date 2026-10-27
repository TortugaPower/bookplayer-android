package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** The process's one sync engine: built once, off the main thread, once storage allows */
@RunWith(RobolectricTestRunner::class)
class SyncRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engines = Collections.synchronizedList(mutableListOf<TaskConcurrencyManager>())
    private val defaultBytes = StorageMonitor.availableBytesProvider

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        scope.cancel()
        engines.forEach { it.stopProcessing() }
        StorageMonitor.availableBytesProvider = defaultBytes
        StorageMonitor.resetForTest()
        db.close()
    }

    private class ProAccounts : AccountRepository {
        private val account = MutableStateFlow<AccountEntity?>(AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = AccountTier.PRO))
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
    }

    /** Runs media-server pushes (no account needed): records them, or holds each until cancelled */
    private class Pushes(private val hold: Boolean = false) : TaskProcessor {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val started = CompletableDeferred<Unit>()
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran += task.id
            started.complete(Unit)
            if (hold) awaitCancellation()
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
    }

    private val builds = AtomicInteger()
    private var builtOnMainThread: Boolean? = null

    private fun runtime(storageCritical: Flow<Boolean>, processor: TaskProcessor = Pushes()) =
        SyncRuntime(context, scope, storageCritical, buildEngine = {
            builds.incrementAndGet()
            builtOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            TaskConcurrencyManager(context, repository, ProAccounts(), listOf(processor)).also { engines += it }
        })

    private suspend fun queuePush(id: String) = repository.saveTask(
        SyncTaskEntity(id = id, taskID = id, queueKey = "jellyfin", jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE, position = 0, payload = "{}")
    )

    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(20) }

    /** Every new task, the launch and storage recovery all ask for it: one engine, however many ask at once */
    @Test fun startingAgain_andFromManyThreadsAtOnce_buildsOneEngine() = runBlocking {
        val runtime = runtime(MutableStateFlow(false))

        (1..20).map { launch(Dispatchers.Default) { runtime.start() } }.forEach { it.join() }
        runtime.start()

        until { runtime.engine.value != null }
        delay(200)
        assertEquals(1, builds.get())
        assertSame("the engine stops a lapse's lanes from outside", runtime.engine.value, SyncEngine.current)
    }

    /** Building the network clients sets up TLS: on the main thread that was the background ANR (ANDROID-BOOKPLAYER-2E) */
    @Test fun theEngineIsBuilt_offTheMainThread() = runBlocking {
        val runtime = runtime(MutableStateFlow(false))
        runtime.start()

        until { runtime.engine.value != null }
        assertEquals(false, builtOnMainThread)
    }

    @Test fun nothingIsBuilt_whileStorageIsCritical() = runBlocking {
        val critical = MutableStateFlow(true)
        val runtime = runtime(critical)
        runtime.start()

        delay(300)
        assertEquals(0, builds.get())
        assertNull(runtime.engine.value)

        critical.value = false
        until { runtime.engine.value != null }
        assertEquals(1, builds.get())
    }

    /** A full disk holds every lane; nothing else makes them look again once space is back */
    @Test fun spaceComingBack_letsTheLanesRunWhatTheyHeld() = runBlocking {
        val pushes = Pushes()
        val runtime = runtime(StorageMonitor.state.map { it.isCritical }, pushes)
        runtime.start()
        until { runtime.engine.value != null }

        StorageMonitor.availableBytesProvider = { 0L }
        StorageMonitor.refresh(context)
        queuePush("held")
        delay(500)
        assertTrue("held while the disk is full", pushes.ran.isEmpty())

        StorageMonitor.availableBytesProvider = { Long.MAX_VALUE }
        StorageMonitor.refresh(context)

        until { pushes.ran.contains("held") }
    }

    /**
     * A build that throws is reported, never fatal (it runs at every process start, playback-only ones included),
     * and leaves no engine: the next start (a new task, an app open) tries again
     */
    @Test fun aFailedBuild_isReported_andTriedAgain_byTheNextStart() = runBlocking {
        val reported = Collections.synchronizedList(mutableListOf<Throwable>())
        val crashed = Collections.synchronizedList(mutableListOf<Throwable>())
        val watched = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashed += e })
        var attempts = 0
        val runtime = SyncRuntime(
            context, watched, MutableStateFlow(false),
            buildEngine = {
                if (++attempts == 1) error("first build fails")
                TaskConcurrencyManager(context, repository, ProAccounts(), listOf(Pushes())).also { engines += it }
            },
            onBuildFailed = { reported += it },
        )
        try {
            runtime.start()
            until { reported.isNotEmpty() }
            assertEquals("first build fails", reported.single().message)
            assertTrue("not rethrown", crashed.isEmpty())
            assertNull(runtime.engine.value)

            runtime.start()
            until { runtime.engine.value != null }
            assertEquals(2, attempts)
        } finally {
            watched.cancel()
        }
    }

    @Test fun itsLanes_areTheEngines() = runBlocking {
        val pushes = Pushes(hold = true)
        val runtime = runtime(MutableStateFlow(false), pushes)
        assertEquals(emptySet<String>(), runtime.activeQueues.value)

        runtime.start()
        until { runtime.engine.value != null }
        queuePush("p")
        pushes.started.await()

        until { runtime.activeQueues.value == setOf("jellyfin") }
        assertFalse(runtime.activeQueues.value.isEmpty())
    }
}
