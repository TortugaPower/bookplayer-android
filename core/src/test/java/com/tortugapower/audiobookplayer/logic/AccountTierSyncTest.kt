package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** iOS `updateSyncEnabled`: a lapse mid-session wipes the server lanes, one found at launch holds them */
@RunWith(RobolectricTestRunner::class)
class AccountTierSyncTest {

    private class Accounts(tier: AccountTier?) : AccountRepository {
        val account = MutableStateFlow(tier?.let { AccountEntity(id = "1", email = "a@b.c", apiToken = "t", tier = it) })
        var tierWrites = 0
        override fun getAccountFlow(): Flow<AccountEntity?> = account
        override suspend fun getAccount(): AccountEntity? = account.value
        override suspend fun saveAccount(account: AccountEntity) { this.account.value = account }
        override suspend fun deleteAccount() { account.value = null }
        override suspend fun updateTier(tier: AccountTier) {
            tierWrites++
            account.value = account.value?.copy(tier = tier)
        }
    }

    private class Hooks : SyncSessionHooks {
        var ended = 0
        val read = mutableListOf<AccountTier>()
        override suspend fun syncEnded() { ended++ }
        override suspend fun tierRead(tier: AccountTier) { read += tier }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncTaskRepository
    private val hooks = Hooks()
    private var wakes = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomSyncTaskRepository(db.syncTaskDao())
    }

    @After fun tearDown() = db.close()

    private fun sync(accounts: Accounts) = AccountTierSync(accounts, repository, hooks, wakeEngine = { wakes++ })

    private suspend fun queue(id: String, lane: String, jobType: String) =
        repository.saveTask(SyncTaskEntity(id = id, taskID = id, queueKey = lane, jobType = jobType, position = 0, payload = "{}"))

    /** One task in every lane, a running and a parked upload among them */
    private suspend fun fillEveryLane() {
        queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)
        queue("upload", SyncTaskFactory.QUEUE_UPLOAD, SyncTaskFactory.JOB_UPLOAD_FILE)
        db.syncTaskDao().markTaskRunning("upload")
        queue("parked-upload", SyncTaskFactory.QUEUE_UPLOAD, SyncTaskFactory.JOB_UPLOAD_FILE)
        db.syncTaskDao().parkTask("parked-upload", "ACCOUNT", "account_inactive", "Inactive", 403, 5L)
        queue("preference", SyncTaskFactory.QUEUE_PREFERENCES, SyncTaskFactory.JOB_UPLOAD_PREFERENCE)
        queue("artwork", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_UPLOAD_ARTWORK)
        // An older build's, until the engine moves it
        queue("old-artwork", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_UPLOAD_ARTWORK)
        queue("download", SyncTaskFactory.QUEUE_FILE, SyncTaskFactory.JOB_DOWNLOAD_FILE)
        queue("hardcover", SyncTaskFactory.QUEUE_HARDCOVER, SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS)
        queue("jellyfin", "jellyfin", SyncTaskFactory.JOB_EXTERNAL_UPDATE)
    }

    private suspend fun queued() = db.syncTaskDao().getAllTasksSync().map { it.id }.toSet()

    @Test fun aLapseMidSession_wipesTheServerLanes_andKeepsTheUsersOwnServers() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)
        fillEveryLane()

        sync.record(AccountTier.PRO)
        sync.record(AccountTier.FREE)
        sync.applyQueued()

        assertEquals(AccountTier.FREE, accounts.account.value?.tier)
        assertEquals(1, hooks.ended)
        assertEquals(setOf("hardcover", "jellyfin"), queued())
        assertEquals(listOf(AccountTier.PRO, AccountTier.FREE), hooks.read)
    }

    /** Lapsed while the app was closed: held for the return (iOS setup), only the queued downloads go */
    @Test fun aLapseInTheFirstReading_holdsTheQueue() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)
        fillEveryLane()

        sync.record(AccountTier.FREE)
        sync.applyQueued()

        assertEquals(AccountTier.FREE, accounts.account.value?.tier)
        assertEquals("the next sync is a first sync", 1, hooks.ended)
        assertEquals(setOf("move", "upload", "parked-upload", "preference", "artwork", "old-artwork", "hardcover", "jellyfin"), queued())
    }

    @Test fun proToLite_dropsTheFileUploads_parkedOnesToo() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)
        fillEveryLane()

        sync.record(AccountTier.PRO)
        sync.record(AccountTier.LITE)
        sync.applyQueued()

        assertEquals(AccountTier.LITE, accounts.account.value?.tier)
        assertEquals(0, hooks.ended)
        assertEquals(setOf("move", "preference", "download", "hardcover", "jellyfin"), queued())
    }

    /** iOS drops what the LITE policy refuses whenever it pops it, at launch too */
    @Test fun aFirstReadingOfLite_dropsTheFileUploads() = runBlocking {
        val sync = sync(Accounts(AccountTier.PRO))
        fillEveryLane()

        sync.record(AccountTier.LITE)
        sync.applyQueued()

        assertEquals(setOf("move", "preference", "download", "hardcover", "jellyfin"), queued())
    }

    /** Lapsed at launch (held), then back as LITE: the held uploads go, a cover would hold back every listing */
    @Test fun aReturnAsLite_dropsTheHeldUploads() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)
        fillEveryLane()

        sync.record(AccountTier.FREE)
        sync.applyQueued()
        assertTrue("held", "artwork" in queued())

        sync.record(AccountTier.LITE)
        sync.applyQueued()
        assertEquals(AccountTier.LITE, accounts.account.value?.tier)
        assertEquals(setOf("move", "preference", "hardcover", "jellyfin"), queued())
    }

    @Test fun aReturn_wakesTheEngineForTheHeldWork() = runBlocking {
        val accounts = Accounts(AccountTier.FREE)
        val sync = sync(accounts)
        queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)

        sync.record(AccountTier.FREE)
        sync.applyQueued()
        assertEquals(0, wakes)

        sync.record(AccountTier.PRO)
        sync.applyQueued()
        assertEquals(AccountTier.PRO, accounts.account.value?.tier)
        assertEquals(1, wakes)
        assertEquals(setOf("move"), queued())
    }

    @Test fun theSameTier_writesNothing() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)

        sync.record(AccountTier.PRO)
        sync.record(AccountTier.PRO)
        sync.applyQueued()

        assertEquals(0, accounts.tierWrites)
        assertEquals(0, wakes)
    }

    /** An answer to a call made before a sign-in or sign-out belongs to the account before */
    @Test fun anAnswerFromAnEarlierEpoch_isDropped() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val sync = sync(accounts)
        val started = sync.currentEpoch()
        sync.newEpoch()

        assertFalse(sync.record(AccountTier.FREE, started))
        sync.applyQueued()
        assertEquals(AccountTier.PRO, accounts.account.value?.tier)
        assertTrue(hooks.read.isEmpty())
    }

    /** A lapse read just before a sign-out isn't applied to whoever signs in next */
    @Test fun aReadingQueuedBeforeANewEpoch_isNotApplied() = runBlocking {
        val sync = sync(Accounts(AccountTier.PRO))
        fillEveryLane()

        sync.record(AccountTier.PRO)
        sync.record(AccountTier.FREE)
        sync.newEpoch()
        sync.applyQueued()

        assertEquals(0, hooks.ended)
        assertEquals(9, queued().size)
    }

    /** A new account's first reading isn't compared with the last account's */
    @Test fun afterANewEpoch_theNextReadingIsAFirstOne() = runBlocking {
        val sync = sync(Accounts(AccountTier.PRO))
        fillEveryLane()
        sync.record(AccountTier.PRO)
        sync.applyQueued()

        sync.newEpoch()
        sync.record(AccountTier.FREE)
        sync.applyQueued()

        assertTrue("held, not wiped", "move" in queued())
    }

    /**
     * The launch gate opens once a reading's tier is stored: once per reading, before the queue changes for it
     * (a lapse's session end waits on a pass that may be waiting on the gate), and for a dropped reading too
     */
    @Test fun start_tellsWhenEachTierIsStored_beforeTheQueueChanges() = runBlocking {
        val accounts = Accounts(AccountTier.PRO)
        val stored = AtomicInteger()
        val storedWhenSyncEnded = AtomicInteger(-1)
        val hooks = object : SyncSessionHooks {
            override suspend fun syncEnded() { storedWhenSyncEnded.set(stored.get()) }
            override suspend fun tierRead(tier: AccountTier) {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val sync = AccountTierSync(accounts, repository, hooks, wakeEngine = {})
            sync.start(scope) { stored.incrementAndGet() }

            sync.record(AccountTier.FREE)
            withTimeout(5_000) { while (storedWhenSyncEnded.get() < 0) delay(10) }
            assertEquals(1, storedWhenSyncEnded.get())

            accounts.account.value = null
            sync.record(AccountTier.PRO)
            withTimeout(5_000) { while (stored.get() < 2) delay(10) }
            delay(100)
            assertEquals(2, stored.get())
        } finally {
            scope.cancel()
        }
    }

    @Test fun signedOut_aReadingChangesNothing() = runBlocking {
        val accounts = Accounts(null)
        val sync = sync(accounts)
        queue("move", SyncTaskFactory.QUEUE_SYNC, SyncTaskFactory.JOB_MOVE)

        sync.record(AccountTier.PRO)
        sync.record(AccountTier.FREE)
        sync.applyQueued()

        assertNull(accounts.account.value)
        assertEquals(0, hooks.ended)
        assertEquals(setOf("move"), queued())
        assertTrue(hooks.read.isEmpty())
    }
}
