package com.tortugapower.audiobookplayer.logic

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TaskConcurrencyServiceHost : Service() {

    companion object {
        private const val CHANNEL_ID = "task_concurrency_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_RESUME_UPLOADS = "com.tortugapower.audiobookplayer.action.RESUME_UPLOADS"

        // How long activeQueues must stay empty before the service stops itself. Long enough to
        // ride out the gaps between chained tasks (fetch → downloads, the per-queue 5s failure
        // pause) so a running sync doesn't churn stop/start; short enough that an idle app stops
        // burning the Android 15+ dataSync budget within the first minute.
        private const val IDLE_STOP_GRACE_MS = 60_000L

        // Cheap running check so bursty enqueues (an import staging dozens of files) don't spam
        // startForegroundService while the service is already up. Racy by nature — a stale false
        // just means one redundant, idempotent start().
        @Volatile
        private var isAlive = false

        fun start(context: Context) {
            if (isAlive) return
            val intent = Intent(context, TaskConcurrencyServiceHost::class.java)
            // A background start of a dataSync FGS throws ForegroundServiceStartNotAllowedException
            // on API 31+ (e.g. a progress-sync task enqueued from playback while the app is
            // backgrounded). Swallow it: the task stays queued in Room and runs on the next
            // foreground start — never crash for it.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("TaskConcurrencyServiceHost", "Sync service not started (likely a background start)", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TaskConcurrencyServiceHost::class.java)
            context.stopService(intent)
        }

        /**
         * Nudge the worker manager to re-scan pending queues — used when "Upload using cellular data"
         * is switched ON so held file uploads resume immediately instead of waiting for the next
         * network change or sync-task write. Safe to call from the foreground (the user is in Settings).
         */
        fun resumeUploads(context: Context) {
            val intent = Intent(context, TaskConcurrencyServiceHost::class.java).apply {
                action = ACTION_RESUME_UPLOADS
            }
            runCatching { context.startService(intent) }
        }
    }

    private val TAG = "TaskConcurrencyServiceHost"
    private lateinit var taskConcurrencyManager: TaskConcurrencyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var connectivityManager: android.net.ConnectivityManager? = null
    // Tracks the active network's metered state so we only react to a real metered→unmetered flip.
    private var lastNotMetered: Boolean? = null
    // Nudge the worker manager when the network changes so uploads held on cellular resume once
    // an un-metered connection is available (the task Flow won't re-emit on a network change alone).
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (::taskConcurrencyManager.isInitialized) taskConcurrencyManager.requestWorkerScan()
        }
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: android.net.NetworkCapabilities
        ) {
            // Capability callbacks fire constantly on the active network (bandwidth estimate,
            // validation, etc.), and each scan hits the DB. Only nudge on a metered→unmetered
            // transition — the one change that can release cellular-held uploads.
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
        Log.d(TAG, "⚙️ TaskConcurrencyServiceHost.onCreate() - Initializing task concurrency manager")
        isAlive = true
        createNotificationChannel()

        val db = AppDatabase.getDatabase(this)
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        val accountRepository = com.tortugapower.audiobookplayer.repository.RoomAccountRepository(db.accountDao())

        // Register all available processors
        val processors = listOf(
            FetchContentsProcessor(this, repository, PlaybackManagerSyncCoordinator),
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
            PreferenceUploadProcessor(),
            PreferenceFetchProcessor(this, repository)
        )

        taskConcurrencyManager = TaskConcurrencyManager(this, repository, accountRepository, processors)
        Log.d(TAG, "🚀 Triggering taskConcurrencyManager.startProcessing()")
        taskConcurrencyManager.startProcessing()

        // Resume cellular-held uploads promptly when the network changes (e.g. Wi-Fi returns).
        connectivityManager = (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.also {
            runCatching { it.registerDefaultNetworkCallback(networkCallback) }
        }

        // Android 15 gives dataSync services a 6h/day budget; once it's exhausted this throws
        // ForegroundServiceStartNotAllowedException ("time limit already exhausted") — and with
        // START_STICKY that used to be a crash LOOP (Play pre-launch review hit it: BOOKPLAYER-9).
        // Degrade instead: stop cleanly (which also satisfies the startForegroundService
        // obligation) and let the next explicit start retry once the budget resets.
        try {
            startForeground(NOTIFICATION_ID, createNotification("Starting sync..."))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground promotion denied (dataSync budget exhausted?): ${e.message}")
            // Cleared BEFORE stopping (same as the idle-stop/onTimeout paths, and the Wear host's
            // twin catch): a waker start() during teardown must not be skipped by the fast-path.
            isAlive = false
            stopSelf()
            return
        }

        // Observe account changes to update NetworkClient token
        serviceScope.launch {
            accountRepository.getAccountFlow().collect { account ->
                Log.d(TAG, "👤 Account updated, setting NetworkClient token")
                NetworkClient.setToken(account?.apiToken)
            }
        }

        // Observe active queues to update the notification — and to stop the service once work
        // dries up. Android 15+ gives dataSync services a 6h/day budget that burns on wall-clock
        // time, not work: an always-on "Idle" host exhausts it daily and gets killed mid-run
        // (ANDROID-BOOKPLAYER-8) or refused promotion (ANDROID-BOOKPLAYER-9). collectLatest makes
        // the grace delay self-cancelling — any queue going active restarts the block.
        serviceScope.launch {
            taskConcurrencyManager.activeQueues.collectLatest { activeQueues ->
                if (activeQueues.isEmpty()) {
                    updateNotification("Idle")
                    delay(IDLE_STOP_GRACE_MS)
                    Log.d(TAG, "💤 No active queues for ${IDLE_STOP_GRACE_MS / 1000}s — stopping service to preserve the dataSync budget")
                    // Cleared BEFORE stopping so a task enqueued mid-teardown isn't skipped by
                    // start()'s isAlive fast-path — its startForegroundService recreates us.
                    isAlive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification("Processing: ${activeQueues.joinToString(", ")}")
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME_UPLOADS && ::taskConcurrencyManager.isInitialized) {
            taskConcurrencyManager.requestWorkerScan()
        }
        // NOT_STICKY: a sticky null-intent restart arrives from the BACKGROUND, where dataSync
        // promotion is refused (budget/exemption) — it can only churn, never do useful work.
        // Every real producer (app start, SyncEngineWaker on task enqueue, settings toggles)
        // starts the service explicitly anyway.
        return START_NOT_STICKY
    }

    // Android 15 calls this when the dataSync time budget runs out MID-RUN; not stopping within
    // a few seconds crashes the service ("did not stop within its timeout"). Demote SYNCHRONOUSLY
    // here — stopSelf() alone leaves the demotion to onDestroy, and any main-thread congestion
    // past the grace window is the ANDROID-BOOKPLAYER-8 crash. Remaining tasks stay queued in
    // Room and resume on the next start.
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
            val name = "Background Tasks"
            val descriptionText = "Shows progress of background synchronization and tasks"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BookPlayer Sync")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
