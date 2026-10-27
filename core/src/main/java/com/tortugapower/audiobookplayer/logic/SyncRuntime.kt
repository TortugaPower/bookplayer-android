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
 * watcher), is reported to [onFailed]; the engine retires and the next [start] (a new task, an app open) builds a
 * new one. A full disk isn't a failure: the engine waits for space.
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
    private val started = AtomicBoolean(false)
    private val _engine = MutableStateFlow<TaskConcurrencyManager?>(null)
    private var networkCallback: SyncNetworkCallback? = null

    /** The engine, once built */
    val engine: StateFlow<TaskConcurrencyManager?> = _engine.asStateFlow()

    /** The engine's lanes with a worker: none until it's built */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeQueues: StateFlow<Set<String>> = _engine
        .flatMapLatest { it?.activeQueues ?: flowOf(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Builds and starts the engine, unless one runs (or is being built); after a failure, builds a new one */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(buildDispatcher) {
            var engine: TaskConcurrencyManager? = null
            try {
                // The engine only reaches its onFailure once it has started, by then it's in [_engine]
                engine = buildEngine { e -> scope.launch(buildDispatcher) { retire(e) } }
                _engine.value = engine
                engine.startProcessing()
            } catch (e: Throwable) {
                engine?.stopProcessing()
                _engine.value = null
                // The next new task, or the next app open, tries again
                started.set(false)
                if (e is CancellationException) throw e
                if (!StorageMonitor.reportFailure(context, e)) {
                    Log.e("SyncRuntime", "Sync engine not built", e)
                    onFailed(e)
                }
                return@launch
            }
            watchNetwork(engine)
        }
    }

    /**
     * The engine failed in its own work: its workers stop (waited on a while, so a new engine's start doesn't reset a
     * row one of them still runs), it stops, and the next [start] builds a new one. Once per engine.
     */
    private suspend fun retire(e: Throwable) {
        val engine = _engine.value ?: return
        if (!_engine.compareAndSet(engine, null)) return
        engine.cancelAllLanes()
        engine.stopProcessing()
        unwatchNetwork()
        started.set(false)
        onFailed(e)
    }

    // Registered while the engine runs
    private fun watchNetwork(engine: TaskConcurrencyManager) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = SyncNetworkCallback(engine)
        callback.onRegistering(connectivity.activeNetwork)
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w("SyncRuntime", "Network callback not registered", it) }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
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
