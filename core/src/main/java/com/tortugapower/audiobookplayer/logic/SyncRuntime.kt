package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.ConnectivityManager
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
    private val networkWatches = HashMap<TaskConcurrencyManager, SyncNetworkWatch>()
    // The failures already reported in this process
    private val reported = HashSet<String>()

    private val _engine = MutableStateFlow<TaskConcurrencyManager?>(null)

    /** The engine, while one runs */
    val engine: StateFlow<TaskConcurrencyManager?> = _engine.asStateFlow()

    /** What each lane with a worker is doing (working, or waiting out a backoff): none while no engine runs */
    @OptIn(ExperimentalCoroutinesApi::class)
    val laneStates: StateFlow<Map<String, LaneState>> = _engine
        .flatMapLatest { it?.laneStates ?: flowOf(emptyMap()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Whether the running engine has looked at the queue once ([TaskConcurrencyManager.scanned]): false while none runs */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scanned: StateFlow<Boolean> = _engine
        .flatMapLatest { it?.scanned ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The drains the queue needs ([TaskConcurrencyManager.nextDrain]). Null until an engine has looked at the queue
     * ([scanned]): before its start-up steps, nothing is known, and a process a drain just started would otherwise
     * cancel that very drain
     */
    suspend fun nextDrain(): SyncDrainPolicy.Needs? = _engine.value?.takeIf { it.scanned.value }?.nextDrain()

    /** A sync drain's start: the running engine's lanes pick again by the wall clock ([TaskConcurrencyManager.rescan]) */
    suspend fun rescan() {
        _engine.value?.rescan()
    }

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
        val watch = SyncNetworkWatch(engine)
        if (watch.register(connectivity)) networkWatches[engine] = watch
    }

    private fun unwatchNetwork(engine: TaskConcurrencyManager) {
        val watch = networkWatches.remove(engine) ?: return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        watch.unregister(connectivity)
    }
}
