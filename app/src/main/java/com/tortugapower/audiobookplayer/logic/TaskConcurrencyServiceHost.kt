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
        
        fun start(context: Context) {
            val intent = Intent(context, TaskConcurrencyServiceHost::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TaskConcurrencyServiceHost::class.java)
            context.stopService(intent)
        }
    }

    private val TAG = "TaskConcurrencyServiceHost"
    private lateinit var taskConcurrencyManager: TaskConcurrencyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var connectivityManager: android.net.ConnectivityManager? = null
    // Nudge the worker manager when the network changes so uploads held on cellular resume once
    // an un-metered connection is available (the task Flow won't re-emit on a network change alone).
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            taskConcurrencyManager.requestWorkerScan()
        }
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: android.net.NetworkCapabilities
        ) {
            taskConcurrencyManager.requestWorkerScan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "⚙️ TaskConcurrencyServiceHost.onCreate() - Initializing task concurrency manager")
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
            PreferenceUploadProcessor(this),
            PreferenceFetchProcessor(this)
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

        // Observe active queues to update notification
        serviceScope.launch {
            taskConcurrencyManager.activeQueues.collectLatest { activeQueues ->
                if (activeQueues.isEmpty()) {
                    updateNotification("Idle")
                } else {
                    updateNotification("Processing: ${activeQueues.joinToString(", ")}")
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // Android 15 calls this when the dataSync time budget runs out MID-RUN; not stopping within
    // a few seconds crashes the service ("did not stop within its timeout"). Remaining tasks stay
    // queued in Room and resume on the next start.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync budget expired (type=$fgsType) — stopping; queued tasks resume later")
        stopSelf()
    }

    override fun onDestroy() {
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
