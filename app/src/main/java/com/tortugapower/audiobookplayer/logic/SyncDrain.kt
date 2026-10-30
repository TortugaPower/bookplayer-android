package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tortugapower.audiobookplayer.BookPlayerApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A sync drain (SyncDrainPolicy): runs the engine while nothing else keeps the process alive, until no lane works,
 * or until something else takes over (the app on screen, playback in the foreground, the sync service). Android ends
 * a job after 10 minutes and WorkManager runs it again: a download resumes where it stopped (DownloadResume), an
 * upload from the parts S3 holds. It never keeps running while something else keeps the process alive: Android 16
 * counts a job that runs beside a foreground service, or carries on after the app it started under leaves the screen,
 * against the app's job quota. A pending drain Android starts while the app is on screen (or playback or the service
 * holds the process) ends at once, a few milliseconds of job time; cancelling the pending ones each time the app opens
 * would cost a WorkManager write every time.
 */
internal class SyncDrain(
    /** The app on screen, playback in the foreground, or the sync service up */
    private val keptAlive: StateFlow<Boolean>,
    private val scanned: StateFlow<Boolean>,
    private val lanes: StateFlow<Map<String, LaneState>>,
    private val startEngine: () -> Unit,
    /** The running engine's lanes pick again by the wall clock (SyncRuntime.rescan) */
    private val rescan: suspend () -> Unit,
    private val isStorageCritical: () -> Boolean = { StorageMonitor.isCritical },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Drains running now (the one on any network and the one on Wi-Fi can overlap), and [draining] with it; and the
    // ones that have returned (one per drain this process ran)
    private var running = 0
    private val returned = HashSet<UUID>()
    private val lock = Any()

    private val _draining = MutableStateFlow(false)

    /** Whether a drain runs: it keeps the process alive, transfers included, so the service isn't started meanwhile */
    val draining: StateFlow<Boolean> = _draining.asStateFlow()

    /** Runs WorkManager's drain [id] */
    suspend fun run(id: UUID) {
        synchronized(lock) {
            // WorkManager runs a drain Android stopped again, under the same id
            returned -= id
            running++
            _draining.value = true
        }
        try {
            // Nothing runs on a critically full disk (every task ends in a DB write): the next app open takes it from there
            if (isStorageCritical()) return
            startEngine()
            // A fresh process builds its engine first: no lane working means nothing until it has looked at the queue.
            // Something taking over meanwhile (the app opened) ends it then, not once the engine is built
            withTimeoutOrNull(ENGINE_SCAN_TIMEOUT_MS) {
                combine(scanned, keptAlive) { scanned, keptAlive -> scanned || keptAlive }.first { it }
            } ?: return
            if (keptAlive.value) return
            // A live process's lanes wait on a clock that stops while the device sleeps: the retry this drain came for
            // can still be waiting there
            rescan()
            combine(keptAlive, lanes) { keptAlive, lanes ->
                keptAlive || lanes.values.none { it.holdsDrain() }
            }.first { it }
        } finally {
            // With the count: [draining] ending sets off the next schedule, which must know this drain has returned
            synchronized(lock) {
                returned += id
                running--
                _draining.value = running > 0
            }
        }
    }

    /** Whether WorkManager's drain [id] has run here and returned: WorkManager lists it as running a moment longer */
    fun hasReturned(id: UUID): Boolean = synchronized(lock) { id in returned }

    // A lane working, or one whose retry is due, about to pick it
    private fun LaneState.holdsDrain() = this == LaneState.Working || (this is LaneState.Waiting && until <= clock())

    companion object {
        // Building the engine sets up its network clients and runs its start-up steps: well under this
        private const val ENGINE_SCAN_TIMEOUT_MS = 60_000L
    }
}

/**
 * Schedules the sync drains the stored queue needs (SyncDrainPolicy.Needs), each a unique work: one on any network,
 * and one that waits for an unmetered network, for book uploads held for Wi-Fi. Called only while no drain runs in
 * this process (SyncKeepAlive), the one WorkManager runs them in. WorkManager lists a drain as running from just
 * before its worker runs until a moment after it returns: one that has returned here is done (left alone, a lane
 * waiting out a backoff would change no more, and nothing would schedule the next); any other is starting, and is left
 * to run (replacing it would stop it; its end schedules the next). A pending drain is kept when it runs no later
 * than needed (an early run finds nothing due and reschedules), replaced when it would run too late, and cancelled
 * when nothing needs it. A failure is reported once per process (each schedule would report it again).
 * Never on a critically full disk: WorkManager's own database would be the first write to fail. And with nothing
 * needed, WorkManager isn't started just to look: a drain an earlier process left runs, finds nothing, and ends.
 */
internal class SyncDrainScheduler(
    /** Null while nothing is known yet (no engine has looked at the queue): nothing changes then */
    private val needs: suspend () -> SyncDrainPolicy.Needs?,
    private val workManager: () -> WorkManager,
    /** Whether a drain has run in this process and returned (SyncDrain.hasReturned) */
    private val hasReturned: (UUID) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isStorageCritical: () -> Boolean = { StorageMonitor.isCritical },
    private val isWorkManagerStarted: () -> Boolean = { WorkManager.isInitialized() },
    private val onFailed: (Throwable) -> Unit = {},
) {
    private val reported = HashSet<String>()

    suspend fun schedule() {
        if (isStorageCritical()) return
        try {
            val needs = needs() ?: return
            if (needs == SyncDrainPolicy.Needs.NONE && !isWorkManagerStarted()) return
            val workManager = workManager()
            update(workManager, ANY_NETWORK, needs.anyNetwork, NetworkType.CONNECTED)
            update(workManager, UNMETERED, needs.unmeteredOnly, NetworkType.UNMETERED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the next app open runs the queue anyway
            Log.w(TAG, "Couldn't schedule the sync drains", e)
            if (synchronized(reported) { reported.add("${e.javaClass.name}: ${e.message}") }) onFailed(e)
        }
    }

    private suspend fun update(workManager: WorkManager, name: String, at: Long?, network: NetworkType) {
        val drains = workManager.getWorkInfosForUniqueWorkFlow(name).first()
        if (drains.any { it.state == WorkInfo.State.RUNNING && !hasReturned(it.id) }) return
        val existing = drains.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (at == null) {
            if (existing.isNotEmpty()) workManager.cancelUniqueWork(name)
            return
        }
        val now = clock()
        val due = maxOf(at, now)
        // A few seconds late is on time: WorkManager stamps the enqueue a moment after the delay was worked out, and
        // Android doesn't start jobs to the second anyway
        if (existing.any { it.nextScheduleTimeMillis <= due + ON_TIME_SLACK_MS && it.constraints.requiredNetworkType == network }) return
        val drain = OneTimeWorkRequestBuilder<SyncDrainWorker>()
            .setConstraints(Constraints(requiredNetworkType = network))
            .setInitialDelay(due - now, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, drain)
        Log.d(TAG, "🌙 Sync drain $name scheduled in ${(due - now) / 1000}s")
    }

    companion object {
        private const val TAG = "SyncDrainScheduler"
        private const val ON_TIME_SLACK_MS = 10_000L
        internal const val ANY_NETWORK = "sync-drain"
        internal const val UNMETERED = "sync-drain-unmetered"
    }
}

/**
 * WorkManager's entry to a sync drain (SyncDrain). WorkManager stores this class's name in its own database and
 * resolves it after an app update, so it never moves or gets renamed (its consumer rules keep worker names;
 * scripts/audit-mapping.sh pins it).
 */
class SyncDrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            (applicationContext as? BookPlayerApplication)?.syncDrain?.run(id)
        } catch (e: CancellationException) {
            // Android ended it (its 10 minutes, a lost network, its quota): WorkManager runs it again
            Log.w("SyncDrainWorker", "Sync drain stopped (reason $stopReason)")
            throw e
        }
        return Result.success()
    }
}
