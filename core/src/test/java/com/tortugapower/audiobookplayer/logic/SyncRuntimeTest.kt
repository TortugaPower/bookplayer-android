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
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
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
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** The process's one sync engine: built once, off the main thread; a failed build is reported and tried again */
@RunWith(RobolectricTestRunner::class)
class SyncRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engines = Collections.synchronizedList(mutableListOf<TaskConcurrencyManager>())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() {
        scope.cancel()
        engines.forEach { it.stopProcessing() }
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

    private fun runtime(processor: TaskProcessor = Pushes()) =
        SyncRuntime(context, scope, buildEngine = {
            builds.incrementAndGet()
            builtOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            TaskConcurrencyManager(context, repository, ProAccounts(), listOf(processor), onFailure = it).also { engines += it }
        })

    private suspend fun queuePush(id: String) = repository.saveTask(
        SyncTaskEntity(id = id, taskID = id, queueKey = "jellyfin", jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE, position = 0, payload = "{}")
    )

    private suspend fun until(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(20) }

    /** Every new task, the launch and storage recovery all ask for it: one engine, however many ask at once */
    @Test fun startingAgain_andFromManyThreadsAtOnce_buildsOneEngine() = runBlocking {
        val runtime = runtime()

        (1..20).map { launch(Dispatchers.Default) { runtime.start() } }.forEach { it.join() }
        runtime.start()

        until { runtime.engine.value != null }
        delay(200)
        assertEquals(1, builds.get())
        assertSame("the engine stops a lapse's lanes from outside", runtime.engine.value, SyncEngine.current)
    }

    /** Building the network clients sets up TLS: on the main thread that was the background ANR (ANDROID-BOOKPLAYER-2E) */
    @Test fun theEngineIsBuilt_offTheMainThread() = runBlocking {
        val runtime = runtime()
        runtime.start()

        until { runtime.engine.value != null }
        assertEquals(false, builtOnMainThread)
    }

    /**
     * A build that throws is reported, never fatal (it runs at every process start, playback-only ones included), and
     * leaves no engine. A new task doesn't build again (a failure that happens every time would loop); the next app
     * open does
     */
    @Test fun aFailedBuild_isReported_andTriedAgain_whenTheAppIsOpened() = runBlocking {
        val reported = Collections.synchronizedList(mutableListOf<Throwable>())
        val crashed = Collections.synchronizedList(mutableListOf<Throwable>())
        val watched = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashed += e })
        var attempts = 0
        val runtime = SyncRuntime(
            context, watched,
            buildEngine = { onFailure ->
                if (++attempts == 1) error("first build fails")
                TaskConcurrencyManager(context, repository, ProAccounts(), listOf(Pushes()), onFailure = onFailure)
                    .also { engines += it }
            },
            onFailed = { reported += it },
        )
        try {
            runtime.start()
            until { reported.isNotEmpty() }
            assertEquals("first build fails", reported.single().message)
            assertTrue("not rethrown", crashed.isEmpty())
            assertNull(runtime.engine.value)

            runtime.start()
            delay(300)
            assertEquals("a new task doesn't build again", 1, attempts)

            runtime.onAppOpened()
            until { runtime.engine.value != null }
            assertEquals(2, attempts)
        } finally {
            watched.cancel()
        }
    }

    /**
     * An engine that fails in its own work once running (here its start-up steps) is reported, never fatal, and
     * retired, its network callback with it: the next app open builds a new one
     */
    @Test fun anEngineThatFails_isReported_retired_andRebuiltWhenTheAppIsOpened() = runBlocking {
        val reported = Collections.synchronizedList(mutableListOf<Throwable>())
        val crashed = Collections.synchronizedList(mutableListOf<Throwable>())
        val watched = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> crashed += e })
        val breaksOnce = object : SyncTaskRepository by repository {
            var broke = false
            override suspend fun resetRunningTasks() {
                if (!broke) {
                    broke = true
                    error("bad row")
                }
                repository.resetRunningTasks()
            }
        }
        val pushes = Pushes()
        var builds = 0
        val runtime = SyncRuntime(
            context, watched,
            buildEngine = { onFailure ->
                builds++
                TaskConcurrencyManager(context, breaksOnce, ProAccounts(), listOf(pushes), onFailure = onFailure)
                    .also { engines += it }
            },
            onFailed = { reported += it },
        )
        try {
            runtime.start()
            until { reported.isNotEmpty() }
            assertEquals("bad row", reported.single().message)
            assertTrue("not fatal", crashed.isEmpty())
            until { runtime.engine.value == null }
            assertNull("retired", SyncEngine.current)
            until { networkCallbacks().isEmpty() }

            runtime.onAppOpened()
            until { runtime.engine.value != null }
            assertEquals(2, builds)
            assertEquals("the new engine's", 1, networkCallbacks().size)
            queuePush("p")
            until { pushes.ran.contains("p") }
        } finally {
            watched.cancel()
        }
    }

    /**
     * The same failure every time: reported once per process, however often the app is opened, even when its message
     * changes (it may name a row or a path)
     */
    @Test fun theSameFailure_isReportedOnce() = runBlocking {
        val reported = Collections.synchronizedList(mutableListOf<Throwable>())
        var attempts = 0
        val runtime = SyncRuntime(context, scope, buildEngine = { error("bad row ${++attempts}") }, onFailed = { reported += it })

        runtime.start()
        until { attempts == 1 }
        runtime.onAppOpened()
        until { attempts == 2 }
        runtime.onAppOpened()
        until { attempts == 3 }
        delay(200)

        assertEquals(1, reported.size)
    }

    /** A late failure report from an engine already replaced retires nothing */
    @Test fun aReplacedEnginesFailure_leavesTheCurrentOneRunning() = runBlocking {
        val failureHooks = Collections.synchronizedList(mutableListOf<(Throwable) -> Unit>())
        var builds = 0
        val runtime = SyncRuntime(context, scope, buildEngine = { onFailure ->
            failureHooks += onFailure
            if (++builds == 1) error("first build fails")
            TaskConcurrencyManager(context, repository, ProAccounts(), listOf(Pushes()), onFailure = onFailure).also { engines += it }
        })
        runtime.start()
        until { builds == 1 }
        runtime.onAppOpened()
        until { runtime.engine.value != null }
        val current = runtime.engine.value

        failureHooks.first()(IllegalStateException("late"))
        delay(300)

        assertSame(current, runtime.engine.value)
    }

    private fun networkCallbacks() =
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).networkCallbacks

    @Test fun itsLanes_areTheEngines() = runBlocking {
        val pushes = Pushes(hold = true)
        val runtime = runtime(pushes)
        assertEquals(emptySet<String>(), runtime.activeQueues.value)

        runtime.start()
        until { runtime.engine.value != null }
        queuePush("p")
        pushes.started.await()

        until { runtime.activeQueues.value == setOf("jellyfin") }
        assertFalse(runtime.activeQueues.value.isEmpty())
    }
}
