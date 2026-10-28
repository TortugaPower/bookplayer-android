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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An Application running the process's sync engine: its sync service reads the lanes from here */
interface SyncRuntimeOwner {
    val syncRuntime: SyncRuntime
}

/**
 * The process's one sync engine, as on iOS: built off the main thread, then running for the life of the process. A
 * foreground service only keeps the process alive while lanes work; the engine never lives inside one. So a refused,
 * timed-out or stopped service doesn't stop it (a task Android freezes mid-run goes back to pending at the next
 * process start), and no second engine can race this one over the same tasks: its start resets the tasks a killed
 * process left running, which assumes none still runs.
 *
 * Never fatal: the engine starts at every process start, playback-only ones included, so a crash in it would stop
 * playback every time. A failure building it, or in its own work once running (its start-up steps, its queue
 * watcher), is reported to [onFailed] (once per process for the same failure) and the engine retires. It isn't rebuilt
 * for every new task, which would loop on a failure that happens every time: the next app open builds a new one
 * ([onAppOpened]). A full disk isn't a failure: the engine waits it out, and a build it broke runs again when space
 * is back.
 *
 * Every change of state happens on one thread, one at a time ([transition]); slow work (building an engine, winding
 * down a failed one) runs elsewhere and posts its outcome back as another change. So no two can interleave: an app
 * open while a failed engine winds down, for one, asks for a new engine once it's down.
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
    private sealed interface State {
        /** Nothing built yet */
        data object Idle : State
        data object Building : State
        data class Running(val engine: TaskConcurrencyManager) : State
        /** A failed engine winding down; [rebuild] once an app open asked for a new one meanwhile */
        data class Retiring(val engine: TaskConcurrencyManager, val rebuild: Boolean) : State
        /** The last engine failed: only an app open builds again */
        data object Failed : State
        /** A full disk broke the build: space coming back builds again */
        data object WaitingForSpace : State
    }

    // Every read and write of the state below happens here, one change at a time
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var state: State = State.Idle
    private val networkCallbacks = HashMap<TaskConcurrencyManager, SyncNetworkCallback>()
    // The failures already reported in this process
    private val reported = HashSet<String>()

    private val _engine = MutableStateFlow<TaskConcurrencyManager?>(null)

    /** The engine, while one runs */
    val engine: StateFlow<TaskConcurrencyManager?> = _engine.asStateFlow()

    /** The engine's lanes with a worker: none while no engine runs */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeQueues: StateFlow<Set<String>> = _engine
        .flatMapLatest { it?.activeQueues ?: flowOf(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        scope.launch {
            StorageMonitor.state.map { it.isCritical }.distinctUntilChanged().filter { !it }.collect {
                transition { if (state == State.WaitingForSpace) build() }
            }
        }
    }

    /** Builds and starts the engine, unless one runs, is on its way, or the last one failed (see [onAppOpened]) */
    fun start() = transition {
        if (state == State.Idle || state == State.WaitingForSpace) build()
    }

    /**
     * The app was opened: an engine is built if none runs, after a failure too. A build already on its way is this
     * open's (at a cold start it usually is): if it fails, the next open builds again. Building again at once would
     * mostly repeat a failure that happens every time (a full disk has its own retry). An open while a failed engine
     * winds down builds once it's down.
     */
    fun onAppOpened() = transition {
        when (val current = state) {
            State.Idle, State.Failed, State.WaitingForSpace -> build()
            is State.Retiring -> state = current.copy(rebuild = true)
            State.Building, is State.Running -> Unit
        }
    }

    private fun transition(change: () -> Unit) {
        scope.launch(serial) { change() }
    }

    // The steps below run inside a transition

    private fun build() {
        state = State.Building
        scope.launch(buildDispatcher) {
            // Its failures name it: a late report from an engine already replaced retires nothing
            var built: TaskConcurrencyManager? = null
            val outcome = runCatching { buildEngine { e -> transition { built?.let { engineFailed(it, e) } } } }
            outcome.getOrNull()?.let { built = it }
            transition { outcome.fold(onSuccess = ::run, onFailure = ::buildFailed) }
        }
    }

    private fun run(engine: TaskConcurrencyManager) {
        state = State.Running(engine)
        // Before it starts: a failure in its start-up always finds the callback to remove
        watchNetwork(engine)
        engine.startProcessing()
        // Last: whoever reads the engine finds it started, its network watched
        _engine.value = engine
    }

    private fun buildFailed(e: Throwable) {
        state = when {
            e is CancellationException -> State.Idle
            StorageMonitor.reportFailure(context, e) -> State.WaitingForSpace
            else -> State.Failed.also { report(e, "Sync engine not built") }
        }
    }

    private fun engineFailed(engine: TaskConcurrencyManager, e: Throwable) {
        // A second report from the same engine, or a late one from an engine already replaced
        if ((state as? State.Running)?.engine !== engine) return
        report(e, "Sync engine failed")
        state = State.Retiring(engine, rebuild = false)
        _engine.value = null
        scope.launch(buildDispatcher) {
            engine.stopAndWait()
            transition { retired(engine) }
        }
    }

    private fun retired(engine: TaskConcurrencyManager) {
        unwatchNetwork(engine)
        val current = state
        if (current is State.Retiring && current.engine === engine) {
            if (current.rebuild) build() else state = State.Failed
        }
    }

    private fun report(e: Throwable, what: String) {
        Log.e("SyncRuntime", what, e)
        if (reported.add(signature(e))) onFailed(e)
    }

    // The same failure: the same exception, its root cause thrown from the same place. Not the message, which may
    // name a row or a path that changes every time.
    private fun signature(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.take(8).last()
        return "${e.javaClass.name}|${root.javaClass.name}@${root.stackTrace.firstOrNull()}"
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
