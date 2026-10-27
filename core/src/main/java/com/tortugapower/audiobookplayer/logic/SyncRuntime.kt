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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
 * The process's one sync engine, as on iOS: built once, off the main thread, as soon as storage isn't critically
 * full, then running for the life of the process. A foreground service only keeps the process alive while lanes
 * work; the engine never lives inside one. So a refused, timed-out or stopped service doesn't stop it (a task
 * Android freezes mid-run goes back to pending at the next process start), and no second engine can race this one
 * over the same tasks: its start resets the tasks a killed process left running, which assumes none still runs.
 *
 * @param storageCritical StorageMonitor's critical flag. Nothing is built while the disk is full: the engine's start
 *   writes to the queue, and every task ends in a write. Once built, the engine looks at its lanes again whenever
 *   space comes back.
 * @param buildEngine the target's engine, with its processors. Called once, on [buildDispatcher]: building the
 *   network clients sets up TLS, which stalled the main thread (ANDROID-BOOKPLAYER-2E).
 * @param onBuildFailed told when [buildEngine] (or the engine's start) threw for a reason other than a full disk, so
 *   the target can report it (`:core` never initializes Sentry). The next [start] tries again.
 */
class SyncRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storageCritical: Flow<Boolean>,
    private val buildEngine: () -> TaskConcurrencyManager,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onBuildFailed: (Throwable) -> Unit = {},
) {
    private val started = AtomicBoolean(false)
    private val _engine = MutableStateFlow<TaskConcurrencyManager?>(null)

    /** The engine, once built */
    val engine: StateFlow<TaskConcurrencyManager?> = _engine.asStateFlow()

    /** The engine's lanes with a worker: none until it's built */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeQueues: StateFlow<Set<String>> = _engine
        .flatMapLatest { it?.activeQueues ?: flowOf(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Builds and starts the engine, the first time; a later call does nothing, unless that build failed */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(buildDispatcher) {
            storageCritical.first { !it }
            var engine: TaskConcurrencyManager? = null
            try {
                engine = buildEngine()
                engine.startProcessing()
            } catch (e: Throwable) {
                engine?.stopProcessing()
                // The next new task, or the next app open, tries again
                started.set(false)
                if (e is CancellationException) throw e
                // Never fatal: this runs at every process start, playback-only ones included, and a crash here
                // would stop playback each time. A full disk is recorded (the engine waits for space); anything
                // else is reported.
                if (!StorageMonitor.reportFailure(context, e)) {
                    Log.e("SyncRuntime", "Sync engine not built", e)
                    onBuildFailed(e)
                }
                return@launch
            }
            _engine.value = engine
            watchNetwork(engine)
            // A full disk holds every lane, and a write that failed on it may have stopped the task watcher: when
            // space is back, the watcher runs again and the lanes look at their tasks
            storageCritical.distinctUntilChanged().drop(1).filter { !it }.collect {
                engine.resumeWatching()
                engine.requestWorkerScan()
            }
        }
    }

    // Registered for the life of the process, like the engine
    private fun watchNetwork(engine: TaskConcurrencyManager) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = SyncNetworkCallback(engine)
        callback.onRegistering(connectivity.activeNetwork)
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w("SyncRuntime", "Network callback not registered", it) }
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
