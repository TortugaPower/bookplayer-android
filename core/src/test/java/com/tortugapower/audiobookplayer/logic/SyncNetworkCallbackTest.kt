package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.ConnectivityManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Collections

/**
 * What the device's network changes tell the engine (SyncNetworkWatch): a new validated network cuts backoffs short,
 * the app's default one, or on Android 12+ the physical one under a VPN
 */
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

    private fun caps(validated: Boolean, vararg transports: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        transports.forEach { transport -> shadowOf(it).addTransportType(transport) }
    }

    private val wifi: Network = ShadowNetwork.newInstance(1)
    private val cellular: Network = ShadowNetwork.newInstance(2)
    private val vpn: Network = ShadowNetwork.newInstance(3)
    private val wifiAgain: Network = ShadowNetwork.newInstance(4)
    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private suspend fun retried() = withTimeout(5_000) { while (!pushes.ran.contains("push")) delay(20) }

    private suspend fun notRetried(why: String) {
        delay(500)
        assertTrue(why, pushes.ran.isEmpty())
    }

    /** The physical network, as the device reports it: available, then its capabilities */
    private fun PhysicalNetworkCallback.connects(network: Network, validated: Boolean = true) {
        onAvailable(network)
        onCapabilitiesChanged(network, caps(validated, NetworkCapabilities.TRANSPORT_WIFI))
    }

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

    /**
     * Behind an always-on VPN the default network stays the VPN through a lost connection: the physical network under
     * it coming back is what retries the waiting tasks
     */
    @Test fun behindAVpn_theConnectionComingBack_retriesTheWaitingTasks() = runBlocking {
        val physical = PhysicalNetworkCallback(engine)
        physical.onRegistering(vpn, caps(true, NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        physical.connects(wifi)
        notRetried("the network already up")

        physical.onLost(wifi)
        physical.connects(wifiAgain, validated = false)
        notRetried("not before it's validated")

        physical.onCapabilitiesChanged(wifiAgain, caps(true, NetworkCapabilities.TRANSPORT_WIFI))
        retried()
    }

    @Test fun behindAVpnWithNoConnectionUnderIt_theFirstOneIsAChange() = runBlocking {
        val physical = PhysicalNetworkCallback(engine)
        physical.onRegistering(vpn, caps(true, NetworkCapabilities.TRANSPORT_VPN))

        physical.connects(wifi)
        retried()
    }

    @Test fun withoutAVpn_theActiveNetworkIsThePhysicalOne() = runBlocking {
        val physical = PhysicalNetworkCallback(engine)
        physical.onRegistering(wifi, caps(true, NetworkCapabilities.TRANSPORT_WIFI))
        physical.connects(wifi)
        notRetried("the network already up")

        physical.connects(cellular)
        retried()
    }

    /** Without a VPN both follow the same network: the second change finds nothing left to retry */
    @Test fun withoutAVpn_bothSeeTheChange_andTheTasksRetryOnce() = runBlocking {
        val default = SyncNetworkCallback(engine)
        val physical = PhysicalNetworkCallback(engine)
        default.onRegistering(wifi)
        physical.onRegistering(wifi, caps(true, NetworkCapabilities.TRANSPORT_WIFI))

        default.onAvailable(cellular)
        default.onCapabilitiesChanged(cellular, caps(true, NetworkCapabilities.TRANSPORT_CELLULAR))
        physical.connects(cellular)
        retried()

        delay(500)
        assertEquals(listOf("push"), pushes.ran.toList())
    }

    // :core's tests run at its minSdk unless told otherwise
    @Config(sdk = [35])
    @Test fun onAndroid12_thePhysicalNetworkIsFollowedToo() {
        val watch = SyncNetworkWatch(engine)

        assertTrue(watch.register(connectivity))
        assertEquals(
            setOf(SyncNetworkCallback::class, PhysicalNetworkCallback::class),
            shadowOf(connectivity).networkCallbacks.map { it::class }.toSet(),
        )

        watch.unregister(connectivity)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    /** Android 9-11 have no public way to follow the network under a VPN: the default network only, as before */
    @Config(sdk = [30])
    @Test fun beforeAndroid12_onlyTheDefaultNetwork() {
        assertTrue(SyncNetworkWatch(engine).register(connectivity))

        assertEquals(listOf(SyncNetworkCallback::class), shadowOf(connectivity).networkCallbacks.map { it::class })
    }

    /** Starting behind a VPN that carries a connection: the physical network the watch is first told of is that one */
    @Config(sdk = [35])
    @Test fun theWatch_startsFromTheConnectionUnderAVpn() = runBlocking {
        val active = connectivity.activeNetwork!!
        shadowOf(connectivity).setNetworkCapabilities(
            active, caps(true, NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
        )
        SyncNetworkWatch(engine).register(connectivity)
        val physical = shadowOf(connectivity).networkCallbacks.filterIsInstance<PhysicalNetworkCallback>().single()

        physical.connects(wifi)
        notRetried("the connection already up")

        physical.onLost(wifi)
        physical.connects(wifiAgain)
        retried()
    }
}
