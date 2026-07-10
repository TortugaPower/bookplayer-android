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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "⚙️ WearSyncServiceHost.onCreate() - starting sync engine")
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

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        taskConcurrencyManager.stopProcessing()
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

        fun start(context: Context) {
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
