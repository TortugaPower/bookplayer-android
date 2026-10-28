package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Collections

/** What the device's network changes tell the engine: a new validated network cuts backoffs short */
@RunWith(RobolectricTestRunner::class)
class SyncNetworkCallbackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var engine: TaskConcurrencyManager
    private val pushes = Pushes()

    private class NoAccount : AccountRepository {
        override fun getAccountFlow(): Flow<AccountEntity?> = flowOf(null)
        override suspend fun getAccount(): AccountEntity? = null
        override suspend fun saveAccount(account: AccountEntity) = Unit
        override suspend fun deleteAccount() = Unit
    }

    private class Pushes : TaskProcessor {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun process(task: SyncTaskEntity): Boolean {
            ran += task.id
            return true
        }
        override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_EXTERNAL_UPDATE
    }

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        // A media-server push waiting out an hour's backoff
        db.syncTaskDao().insertTask(
            SyncTaskEntity(
                id = "push", taskID = "push", queueKey = "jellyfin", jobType = SyncTaskFactory.JOB_EXTERNAL_UPDATE,
                position = 0, payload = "{}", failureStreak = 8, nextAttemptAt = System.currentTimeMillis() + 60 * 60 * 1_000L,
            )
        )
        engine = TaskConcurrencyManager(context, repository, NoAccount(), listOf(pushes))
        engine.startProcessing()
        withTimeout(5_000) { while (engine.laneStates.value["jellyfin"] !is LaneState.Waiting) delay(20) }
    }

    @After fun tearDown() {
        engine.stopProcessing()
        db.close()
    }

    private fun caps(validated: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val wifi: Network = ShadowNetwork.newInstance(1)
    private val cellular: Network = ShadowNetwork.newInstance(2)

    @Test fun theNetworkAlreadyUp_cutsNoBackoffShort() = runBlocking {
        val callback = SyncNetworkCallback(engine)
        callback.onRegistering(wifi)
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))

        delay(500)
        assertTrue(pushes.ran.isEmpty())
    }

    @Test fun aNewNetwork_retriesTheWaitingTasks_onceValidated() = runBlocking {
        val callback = SyncNetworkCallback(engine)
        callback.onRegistering(wifi)
        callback.onAvailable(wifi)

        callback.onAvailable(cellular)
        callback.onCapabilitiesChanged(cellular, caps(validated = false))
        delay(500)
        assertTrue("not before it's validated", pushes.ran.isEmpty())

        callback.onCapabilitiesChanged(cellular, caps(validated = true))
        withTimeout(5_000) { while (!pushes.ran.contains("push")) delay(20) }
    }
}
