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
import com.tortugapower.audiobookplayer.logic.SyncIdentifiersProcessor
import com.tortugapower.audiobookplayer.logic.TaskConcurrencyManager
import com.tortugapower.audiobookplayer.logic.UpdateProcessor
import com.tortugapower.audiobookplayer.logic.UploadExternalResourceProcessor
import com.tortugapower.audiobookplayer.logic.UploadFileProcessor
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The watch's sync foreground service — the Wear counterpart of the phone's `TaskConcurrencyServiceHost`.
 * It wires the SAME `:core` sync processors and `TaskConcurrencyManager` (the engine + all processors live
 * in `:core`; only this foreground `Service` host is per-target), so the standalone watch fills its Room DB
 * from the BookPlayer backend exactly like the phone.
 *
 * Differences from the phone host: its notification taps through to the Wear [MainActivity] and uses
 * Wear-owned strings, and it omits the phone-only [com.tortugapower.audiobookplayer.logic.PlaybackManagerSyncCoordinator]
 * (that cross-device last-played reconciliation is a phone concern; `FetchContentsProcessor`'s coordinator
 * defaults to null and simply skips it). Started/stopped by [WearApp] when the account enters/leaves the
 * standalone (PRO) tier, so it never runs a foreground service in remote/sign-in mode.
 */
class WearSyncServiceHost : Service() {

    private val TAG = "WearSyncServiceHost"
    private lateinit var taskConcurrencyManager: TaskConcurrencyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "⚙️ WearSyncServiceHost.onCreate() - starting sync engine")
        createNotificationChannel()

        val db = AppDatabase.getDatabase(this)
        val repository = RoomSyncTaskRepository(db.syncTaskDao())
        val accountRepository = RoomAccountRepository(db.accountDao())

        // Same processor set as the phone, minus the phone-only playback-sync coordinator (defaulted null).
        val processors = listOf(
            FetchContentsProcessor(this, repository),
            SyncIdentifiersProcessor(this, repository),
            MetadataUploadProcessor(this, repository),
            UploadFileProcessor(this, repository),
            UpdateProcessor(),
            MoveProcessor(),
            DeleteProcessor(),
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

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.wear_sync_status_starting)))

        // Keep NetworkClient's auth token in sync with the signed-in account (mirrors the phone host).
        serviceScope.launch {
            accountRepository.getAccountFlow().collect { account ->
                NetworkClient.setToken(account?.apiToken)
            }
        }

        // Reflect active work in the notification text.
        serviceScope.launch {
            taskConcurrencyManager.activeQueues.collectLatest { activeQueues ->
                val text = if (activeQueues.isEmpty()) {
                    getString(R.string.wear_sync_status_idle)
                } else {
                    getString(R.string.wear_sync_status_processing, activeQueues.joinToString(", "))
                }
                updateNotification(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        taskConcurrencyManager.stopProcessing()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wear_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.wear_sync_channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wear_sync_notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "wear_task_concurrency_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WearSyncServiceHost::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearSyncServiceHost::class.java))
        }
    }
}
