package com.tortugapower.audiobookplayer.wear.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.ArtworkUploadProcessor
import com.tortugapower.audiobookplayer.logic.DeleteBookmarkProcessor
import com.tortugapower.audiobookplayer.logic.DeleteExternalResourceProcessor
import com.tortugapower.audiobookplayer.logic.DeleteProcessor
import com.tortugapower.audiobookplayer.logic.DownloadFileProcessor
import com.tortugapower.audiobookplayer.logic.ExternalUpdateProcessor
import com.tortugapower.audiobookplayer.logic.FetchContentsProcessor
import com.tortugapower.audiobookplayer.logic.HardcoverProcessor
import com.tortugapower.audiobookplayer.logic.MatchUuidsProcessor
import com.tortugapower.audiobookplayer.logic.MetadataUploadProcessor
import com.tortugapower.audiobookplayer.logic.MoveProcessor
import com.tortugapower.audiobookplayer.logic.RenameFolderProcessor
import com.tortugapower.audiobookplayer.logic.SetBookmarkProcessor
import com.tortugapower.audiobookplayer.logic.SetExternalResourceToDownloadProcessor
import com.tortugapower.audiobookplayer.logic.ShallowDeleteProcessor
import com.tortugapower.audiobookplayer.logic.StreamFileUploadProcessor
import com.tortugapower.audiobookplayer.logic.SyncIdentifiersProcessor
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyManager
import com.tortugapower.audiobookplayer.logic.UpdateProcessor
import com.tortugapower.audiobookplayer.logic.UploadExternalResourceProcessor
import com.tortugapower.audiobookplayer.logic.UploadFileProcessor
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The watch's sync foreground service — the Wear counterpart of the phone's `TaskConcurrencyServiceHost`.
 * It wires the SAME `:core` sync processors and `TaskConcurrencyManager` (the engine + all processors live
 * in `:core`; only this foreground `Service` host is per-target), so the standalone watch fills its Room DB
 * from the BookPlayer backend exactly like the phone.
 *
 * Differences from the phone host: it omits the phone-only
 * [com.tortugapower.audiobookplayer.logic.PlaybackManagerSyncCoordinator] (`FetchContentsProcessor`'s
 * coordinator defaults to null), the auth token is kept current at the app level ([com.tortugapower.audiobookplayer.wear.WearApp])
 * rather than here, and its ongoing notification is deliberately minimal (`IMPORTANCE_MIN` + static text —
 * the watch doesn't surface a queued-task count). [WearApp] starts/stops it based on tier + app usage.
 */
class WearSyncServiceHost : Service() {

    private val TAG = "WearSyncServiceHost"
    private lateinit var taskConcurrencyManager: TaskConcurrencyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var connectivityManager: android.net.ConnectivityManager? = null
    // Same wake path as the phone host: uploads held on a metered network (UploadDataPolicy) can
    // only resume when something re-scans the queue — the task Flow doesn't re-emit on a network
    // change. Without this, a held upload on an LTE watch waits for an app restart.
    private var lastNotMetered: Boolean? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (::taskConcurrencyManager.isInitialized) taskConcurrencyManager.requestWorkerScan()
        }
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: android.net.NetworkCapabilities,
        ) {
            // Only nudge on the metered→unmetered flip (capability callbacks fire constantly).
            val notMetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val becameUnmetered = notMetered && lastNotMetered != true
            lastNotMetered = notMetered
            if (becameUnmetered && ::taskConcurrencyManager.isInitialized) {
                taskConcurrencyManager.requestWorkerScan()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "⚙️ WearSyncServiceHost.onCreate() - starting sync engine")
        isAlive = true
        createNotificationChannel()

        val db = AppDatabase.getDatabase(this)
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        val accountRepository = RoomAccountRepository(db.accountDao())

        // Same processor set as the phone. FetchContentsProcessor gets the watch's playback coordinator so
        // it re-arms the on-watch player to the server's last-played item after a contents fetch (matching
        // iOS's handleSyncedLastPlayed) instead of leaving the stale locally-restored book.
        val processors = listOf(
            FetchContentsProcessor(this, repository, WearPlaybackSyncCoordinator),
            SyncIdentifiersProcessor(this, repository),
            MetadataUploadProcessor(this, repository),
            UploadFileProcessor(this, repository),
            StreamFileUploadProcessor(this, repository),
            UpdateProcessor(),
            MoveProcessor(),
            DeleteProcessor(),
            ShallowDeleteProcessor(),
            RenameFolderProcessor(),
            ArtworkUploadProcessor(this),
            DeleteBookmarkProcessor(),
            SetBookmarkProcessor(),
            DownloadFileProcessor(this),
            MatchUuidsProcessor(this, repository),
            HardcoverProcessor(this),
            UploadExternalResourceProcessor(),
            DeleteExternalResourceProcessor(),
            SetExternalResourceToDownloadProcessor(),
            ExternalUpdateProcessor(this),
        )

        taskConcurrencyManager = TaskConcurrencyManager(this, repository, accountRepository, processors)
        taskConcurrencyManager.startProcessing()

        connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        connectivityManager?.let { runCatching { it.registerDefaultNetworkCallback(networkCallback) } }

        // Same Android 15 dataSync-budget guard as the phone host (TaskConcurrencyServiceHost):
        // promotion can throw once the 6h/day budget is exhausted even when the START was legal.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground promotion denied (dataSync budget exhausted?): ${e.message}")
            isAlive = false
            stopSelf()
            return
        }

        // Stop once work dries up — same budget preservation as the phone host: the 6h/day
        // dataSync budget burns on wall-clock time, not work, so an idle host exhausts it and
        // gets killed mid-run. collectLatest makes the grace delay self-cancelling.
        serviceScope.launch {
            taskConcurrencyManager.activeQueues.collectLatest { activeQueues ->
                if (activeQueues.isEmpty()) {
                    delay(IDLE_STOP_GRACE_MS)
                    Log.d(TAG, "💤 No active queues for ${IDLE_STOP_GRACE_MS / 1000}s — stopping to preserve the dataSync budget")
                    // Cleared BEFORE stopping so a task enqueued mid-teardown isn't skipped by
                    // start()'s isAlive fast-path — its startForegroundService recreates us.
                    isAlive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    // NOT_STICKY: a sticky null-intent restart arrives from the BACKGROUND, where dataSync
    // promotion is refused — it can only churn. Real producers (WearApp foreground gate,
    // SyncEngineWaker on task enqueue) start the service explicitly.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // Android 15 mid-run budget expiry: stop within the grace window or the system crashes the
    // service. Demote SYNCHRONOUSLY — leaving it to onDestroy risks blowing the grace window on
    // a congested main thread (the ANDROID-BOOKPLAYER-8 crash). Queued tasks stay in Room and
    // resume on the next start.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync budget expired (type=$fgsType) — stopping; queued tasks resume later")
        isAlive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isAlive = false
        connectivityManager?.let { runCatching { it.unregisterNetworkCallback(networkCallback) } }
        taskConcurrencyManager.stopProcessing()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN: the FGS's required notification stays as unobtrusive as possible on the watch.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wear_sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = getString(R.string.wear_sync_channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wear_sync_notification_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wear_task_concurrency_channel"
        private const val NOTIFICATION_ID = 1001

        // Same values/semantics as the phone host (TaskConcurrencyServiceHost).
        private const val IDLE_STOP_GRACE_MS = 60_000L

        @Volatile
        private var isAlive = false

        fun start(context: Context) {
            if (isAlive) return
            val intent = Intent(context, WearSyncServiceHost::class.java)
            // Callers only start this from the foreground, but guard defensively: a background
            // startForegroundService for a dataSync FGS throws ForegroundServiceStartNotAllowedException
            // on API 31+. Swallowing it just defers sync to the next foreground — never crash for it.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("WearSyncServiceHost", "Sync service not started (likely a background start)", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearSyncServiceHost::class.java))
        }
    }
}
