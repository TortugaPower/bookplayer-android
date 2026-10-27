package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** An Application running the process's sync engine: its sync service reads the lanes from here */
interface SyncRuntimeOwner {
    val syncRuntime: SyncRuntime
}

/**
 * The process's one sync engine, as on iOS: built once, off the main thread, then running for the life of the
 * process. A foreground service only keeps the process alive while lanes work; the engine never lives inside one.
 * So a refused, timed-out or stopped service doesn't stop it (a task Android freezes mid-run goes back to pending at
 * the next process start), and no second engine can race this one over the same tasks: its start resets the tasks a
 * killed process left running, which assumes none still runs. A full disk is the engine's own to wait out
 * (TaskConcurrencyManager.startProcessing).
 *
 * Never fatal: the engine starts at every process start, playback-only ones included, so a crash in it would stop
 * playback every time. A failure building it, or in its own work once running (its start-up steps, its queue
 * watcher), is reported to [onFailed] (once per process for the same failure) and the engine retires. It isn't rebuilt
 * for every new task, which would loop on a failure that happens every time: the next app open builds a new one
 * ([onAppOpened]). A full disk isn't a failure: the engine waits for space, and a build it broke runs again on
 * the next [start].
 *
 * @param buildEngine the target's engine, with its processors, handing the engine the `onFailure` it's given.
 *   Called on [buildDispatcher]: building the network clients sets up TLS, which stalled the main thread
 *   (ANDROID-BOOKPLAYER-2E).
 * @param onFailed told about a failure building or running the engine, so the target can report it (`:core` never
 *   initializes Sentry).
 */
class SyncRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val buildEngine: (onFailure: (Throwable) -> Unit) -> TaskConcurrencyManager,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onFailed: (Throwable) -> Unit = {},
) {
    // An engine runs, or is being built
    private val started = AtomicBoolean(false)
    // The last engine failed: only an app open builds a new one
    private val failed = AtomicBoolean(false)
    private val _engine = MutableStateFlow<TaskConcurrencyManager?>(null)
    // Each running engine's network callback, removed when it retires
    private val networkCallbacks = ConcurrentHashMap<TaskConcurrencyManager, SyncNetworkCallback>()
    // The failures already reported in this process
    private val reported = Collections.synchronizedSet(mutableSetOf<String>())

    /** The engine, once built */
    val engine: StateFlow<TaskConcurrencyManager?> = _engine.asStateFlow()

    /** The engine's lanes with a worker: none until it's built */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeQueues: StateFlow<Set<String>> = _engine
        .flatMapLatest { it?.activeQueues ?: flowOf(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Builds and starts the engine, unless one runs, is being built, or the last one failed */
    fun start() {
        if (failed.get() || !started.compareAndSet(false, true)) return
        scope.launch(buildDispatcher) { build() }
    }

    /** The app was opened: an engine is built if none runs, after a failure too */
    fun onAppOpened() {
        failed.set(false)
        start()
    }

    private fun build() {
        val engine = try {
            lateinit var built: TaskConcurrencyManager
            // Its failures name it: a late report from an engine already replaced retires nothing
            buildEngine { e -> scope.launch(buildDispatcher) { retire(built, e) } }.also { built = it }
        } catch (e: Throwable) {
            started.set(false)
            if (e is CancellationException) throw e
            fail(e, "Sync engine not built")
            return
        }
        _engine.value = engine
        // Before it starts: a failure in its start-up always finds the callback to remove
        watchNetwork(engine)
        engine.startProcessing()
    }

    /** [engine] failed in its own work: it stops for good, its workers waited on, and its network callback goes. Once per engine. */
    private suspend fun retire(engine: TaskConcurrencyManager, e: Throwable) {
        if (!_engine.compareAndSet(engine, null)) return
        engine.stopAndWait()
        unwatchNetwork(engine)
        started.set(false)
        fail(e, "Sync engine failed")
    }

    private fun fail(e: Throwable, what: String) {
        // A full disk is recorded and waited out, not a failure: the next start builds again
        if (StorageMonitor.reportFailure(context, e)) return
        failed.set(true)
        Log.e("SyncRuntime", what, e)
        if (reported.add("${e.javaClass.name}: ${e.message}")) onFailed(e)
    }

    private fun watchNetwork(engine: TaskConcurrencyManager) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = SyncNetworkCallback(engine)
        callback.onRegistering(connectivity.activeNetwork)
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallbacks[engine] = callback }
            .onFailure { Log.w("SyncRuntime", "Network callback not registered", it) }
    }

    private fun unwatchNetwork(engine: TaskConcurrencyManager) {
        val callback = networkCallbacks.remove(engine) ?: return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}

/**
 * The engine's view of the device's network. Any network becoming the default rescans the lanes: the task list
 * doesn't re-emit for a network change, and uploads held on cellular may go on Wi-Fi. A new default network, once
 * validated, also cuts backoffs short (NetworkChangeDetector), since a task that failed on the old one may go
 * through on it.
 */
class SyncNetworkCallback(private val engine: TaskConcurrencyManager) : ConnectivityManager.NetworkCallback() {
    private val changes = NetworkChangeDetector<Network>()
    // The default network's metered state: only a metered → unmetered flip can release held uploads
    private var lastNotMetered: Boolean? = null

    /** Right before registering: the network up now, whose first callback is no change */
    fun onRegistering(activeNetwork: Network?) = changes.onRegistering(activeNetwork)

    override fun onAvailable(network: Network) {
        changes.onAvailable(network)
        engine.requestWorkerScan()
    }

    override fun onLost(network: Network) = changes.onLost(network)

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (changes.onCapabilitiesChanged(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) {
            engine.retryWaitingNow()
        }
        // Capability callbacks fire constantly (bandwidth estimates, validation), and each scan reads the queue
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val becameUnmetered = notMetered && lastNotMetered != true
        lastNotMetered = notMetered
        if (becameUnmetered) engine.requestWorkerScan()
    }
}
